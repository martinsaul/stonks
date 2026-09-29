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
    data class Sent(val expiresAt: Instant, val devCode: String?) : OtpRequestResult
    data class Rejected(val reason: String) : OtpRequestResult
}

sealed interface VerifyResult {
    data class LoggedIn(val session: AuthSession, val accountId: Long, val newAccount: Boolean) : VerifyResult
    /** The alias trap sprang: registration refused, main account badged. */
    data object AliasRefused : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/**
 * Passwordless email sign-in. Registration and login are the same flow: whoever proves
 * control of an inbox gets a device-bound session for its account (created on first use).
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
) {
    private val random = SecureRandom()
    private val key = SecretKeySpec(pepper.toByteArray(), "HmacSHA256")

    fun requestOtp(rawEmail: String): OtpRequestResult {
        val check = emailPolicy.check(rawEmail)
        if (check is EmailCheck.Rejected) return OtpRequestResult.Rejected(check.reason)
        check as EmailCheck.Ok

        if (check.hasPlusTag) {
            // A +tag alias is only worth a code when it aliases an existing account: then
            // the trap is armed. Otherwise it is simply refused.
            val base = db.read { c -> accounts.byCanonical(c, check.canonical) }
            if (base == null) return OtpRequestResult.Rejected("Email aliases (+tags) are not allowed.")
        }

        val now = clock.instant()
        val code = "%06d".format(random.nextInt(1_000_000))
        val expires = now.plus(OTP_TTL)
        db.tx { c ->
            c.update(
                "insert into otp_challenges (email, code_hash, created_at, expires_at) values (?, ?, ?, ?)",
                check.email, hash(check.email, code), now, expires,
            )
        }
        sender.sendOtp(check.email, code)
        return OtpRequestResult.Sent(expires, if (devMode) code else null)
    }

    fun verify(rawEmail: String, code: String, publicKeySpki: String, userAgent: String?, ip: String?): VerifyResult {
        val check = emailPolicy.check(rawEmail) as? EmailCheck.Ok ?: return VerifyResult.Rejected(INVALID)
        val publicKey = RequestSignature.parsePublicKey(publicKeySpki)
            ?: return VerifyResult.Rejected("publicKey must be a base64url SPKI ECDSA P-256 key.")
        if (!CODE.matches(code)) return VerifyResult.Rejected(INVALID)
        val now = clock.instant()

        return db.tx { c ->
            val challenge = c.query(
                """select id, code_hash, attempts from otp_challenges
                   where email = ? and consumed_at is null and expires_at > ?
                   order by created_at desc limit 1 for update""",
                check.email, now,
            ) { rs -> rs.singleOrNull { Triple(it.getLong(1), it.getBytes(2), it.getInt(3)) } }
                ?: return@tx VerifyResult.Rejected(INVALID)

            val (challengeId, expected, attempts) = challenge
            if (attempts >= MAX_ATTEMPTS) return@tx VerifyResult.Rejected(INVALID)
            if (!MessageDigest.isEqual(expected, hash(check.email, code))) {
                c.update("update otp_challenges set attempts = attempts + 1 where id = ?", challengeId)
                return@tx VerifyResult.Rejected(INVALID)
            }
            c.update("update otp_challenges set consumed_at = ? where id = ?", now, challengeId)

            val existing = accounts.byCanonical(c, check.canonical)
            when {
                existing != null && existing.email != check.email -> {
                    // Alias of an existing account: refuse and shame the original, benignly.
                    accounts.awardBadge(c, existing.id, Badge.NICE_TRY, "attempted: ${check.email}")
                    events.append(c, "auth.alias_trap", existing.id, buildJsonObject { put("attempted", JsonPrimitive(check.email)) })
                    VerifyResult.AliasRefused
                }
                existing != null -> {
                    accounts.touchLogin(c, existing.id, now)
                    val session = sessions.create(c, existing.id, publicKey.encoded, now, userAgent, ip)
                    events.append(c, "auth.login", existing.id, buildJsonObject { put("ip", JsonPrimitive(ip)) })
                    VerifyResult.LoggedIn(session, existing.id, newAccount = false)
                }
                else -> {
                    val account = accounts.create(c, check.email, check.canonical)
                    accounts.touchLogin(c, account.id, now)
                    val session = sessions.create(c, account.id, publicKey.encoded, now, userAgent, ip)
                    events.append(c, "account.registered", account.id, buildJsonObject { put("ip", JsonPrimitive(ip)) })
                    VerifyResult.LoggedIn(session, account.id, newAccount = true)
                }
            }
        }
    }

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
