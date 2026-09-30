package stonks.app.auth

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import stonks.app.accounts.AccountStore
import stonks.app.accounts.Badge
import stonks.app.accounts.EventLog
import stonks.app.db.Db
import stonks.app.db.query
import stonks.app.db.singleOrNull
import stonks.app.db.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface OtpRequestResult {
    data class Sent(val challengeId: String, val expiresAt: Instant, val devCode: String?) : OtpRequestResult
    data class Rejected(val reason: String) : OtpRequestResult
}

sealed interface VerifyResult {
    data class LoggedIn(val session: AuthSession, val accountId: Long) : VerifyResult
    /** A +tag alias: refused. If it aliases an existing account, that account was badged. */
    data object AliasRefused : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/**
 * Passwordless email sign-in. Registration and login are the same flow: whoever proves
 * control of an inbox gets a device-bound session for its account (created on first use).
 *
 * Anti-abuse properties:
 * - Each request gets its own challenge, addressed by an unguessable id returned only
 *   to the requester. Other people's requests can't replace a player's code, and
 *   wrong guesses against other challenges can't burn its attempts.
 * - Responses never reveal whether an account exists: the +tag rule is enforced at
 *   verification (after proving the inbox), with one error for both alias cases.
 * - Too many requests for one address stop *sending* email but still answer normally
 *   (no lockout, no signal). Per-IP limits sit in front of this.
 */
class AuthService(
    private val db: Db,
    private val emailPolicy: EmailPolicy,
    private val accounts: AccountStore,
    private val sessions: SessionStore,
    private val events: EventLog,
    private val sender: EmailSender,
    pepper: String,
    private val devMode: Boolean,
    private val clock: Clock = Clock.systemUTC(),
    /** When set, every code is this value (temporary, until email delivery exists). */
    private val fixedCode: String? = null,
    private val emailsPerAddress: Int = 5,
) {
    private val random = SecureRandom()
    private val key = SecretKeySpec(pepper.toByteArray(), "HmacSHA256")
    private val sendLimiter = stonks.app.ratelimit.RateLimiter.perWindow(emailsPerAddress, 15 * 60)

    fun requestOtp(rawEmail: String): OtpRequestResult {
        val check = emailPolicy.check(rawEmail)
        if (check is EmailCheck.Rejected) return OtpRequestResult.Rejected(check.reason)
        check as EmailCheck.Ok

        val now = clock.instant()
        val code = fixedCode ?: "%06d".format(random.nextInt(1_000_000))
        val token = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = now.plus(OTP_TTL)
        db.tx { c ->
            c.update(
                "insert into otp_challenges (email, canonical, token, code_hash, created_at, expires_at) values (?, ?, ?, ?, ?, ?)",
                check.email, check.canonical, token, hash(check.email, code), now, expires,
            )
        }
        // Keyed by the canonical address so Gmail dots and +tags share one budget.
        if (sendLimiter.take(check.canonical) == 0.0) sender.sendOtp(check.email, code)
        // The fixed code is public by definition, so it's shown like a dev-mode code.
        return OtpRequestResult.Sent(token, expires, if (devMode || fixedCode != null) code else null)
    }

    fun verify(challengeId: String, code: String, publicKeySpki: String, userAgent: String?, ip: String?): VerifyResult {
        val publicKey = RequestSignature.parsePublicKey(publicKeySpki)
            ?: return VerifyResult.Rejected("publicKey must be a base64url SPKI ECDSA P-256 key.")
        if (!CODE.matches(code) || challengeId.length > 64) return VerifyResult.Rejected(INVALID)
        val now = clock.instant()

        return db.tx { c ->
            val challenge = c.query(
                """select id, email, code_hash, attempts from otp_challenges
                   where token = ? and consumed_at is null and expires_at > ? for update""",
                challengeId, now,
            ) { rs -> rs.singleOrNull { Challenge(it.getLong(1), it.getString(2), it.getBytes(3), it.getInt(4)) } }
                ?: return@tx VerifyResult.Rejected(INVALID)

            if (challenge.attempts >= MAX_ATTEMPTS) return@tx VerifyResult.Rejected(INVALID)
            if (!MessageDigest.isEqual(challenge.codeHash, hash(challenge.email, code))) {
                c.update("update otp_challenges set attempts = attempts + 1 where id = ?", challenge.id)
                return@tx VerifyResult.Rejected(INVALID)
            }
            c.update("update otp_challenges set consumed_at = ? where id = ?", now, challenge.id)

            val check = emailPolicy.check(challenge.email) as? EmailCheck.Ok ?: return@tx VerifyResult.Rejected(INVALID)
            val existing = accounts.byCanonical(c, check.canonical)
            when {
                check.hasPlusTag -> {
                    // +tag aliases never get an account. If it aliases an existing one, the
                    // original earns a benign badge. Same answer either way (no enumeration).
                    if (existing != null) {
                        accounts.awardBadge(c, existing.id, Badge.NICE_TRY, "attempted: ${check.email}")
                        events.append(c, "auth.alias_trap", existing.id, buildJsonObject { put("attempted", JsonPrimitive(check.email)) })
                    }
                    VerifyResult.AliasRefused
                }
                existing?.bannedAt != null -> VerifyResult.Rejected("This account is suspended.")
                existing != null -> {
                    // Includes Gmail dot variants: same inbox, same person, same account.
                    accounts.touchLogin(c, existing.id, now)
                    val session = sessions.create(c, existing.id, publicKey.encoded, now, userAgent, ip)
                    events.append(c, "auth.login", existing.id, buildJsonObject { put("ip", JsonPrimitive(ip)) })
                    VerifyResult.LoggedIn(session, existing.id)
                }
                else -> {
                    val account = accounts.create(c, check.email, check.canonical)
                    accounts.touchLogin(c, account.id, now)
                    val session = sessions.create(c, account.id, publicKey.encoded, now, userAgent, ip)
                    events.append(c, "account.registered", account.id, buildJsonObject { put("ip", JsonPrimitive(ip)) })
                    VerifyResult.LoggedIn(session, account.id)
                }
            }
        }
    }

    /** Deletes expired sign-in codes and long-dead sessions. */
    fun cleanup() {
        val now = clock.instant()
        db.tx { c ->
            c.update("delete from otp_challenges where expires_at < ?", now.minus(Duration.ofDays(1)))
            c.update(
                "delete from sessions where expires_at < ? or revoked_at < ?",
                now.minus(Duration.ofDays(7)), now.minus(Duration.ofDays(30)),
            )
        }
    }

    private data class Challenge(val id: Long, val email: String, val codeHash: ByteArray, val attempts: Int)

    fun logout(sessionId: String, accountId: Long) {
        db.tx { c ->
            sessions.revoke(c, sessionId, clock.instant())
            events.append(c, "auth.logout", accountId)
        }
    }

    private fun hash(email: String, code: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal("$email:$code".toByteArray())
        }

    companion object {
        val OTP_TTL: Duration = Duration.ofMinutes(10)
        const val MAX_ATTEMPTS = 5
        private val CODE = Regex("^[0-9]{6}$")
        private const val INVALID = "Invalid or expired code."

        fun encodeKey(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
