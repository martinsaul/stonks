package stonks.app.auth

import stonks.app.AppConfig
import stonks.app.api.ApiException
import stonks.app.api.tooManyRequests
import stonks.app.api.unauthorized
import stonks.app.ratelimit.InFlightLimiter
import stonks.app.ratelimit.RateLimiter
import java.time.Clock

/** The authenticated caller. */
data class Principal(val accountId: Long, val sessionId: String)

/**
 * Verifies signed requests and enforces rate limits. Ktor-independent so the HTTP
 * and WebSocket paths share it.
 *
 * Order matters: the cheap per-IP bucket runs before any cryptography; a nonce is only
 * recorded once the signature is valid (so forged requests cannot burn nonces).
 */
class Guard(
    private val sessions: SessionStore,
    private val limits: AppConfig.Limits,
    private val clock: Clock,
    private val skewMillis: Long = 30_000,
) {
    private val bootMillis = clock.millis()
    private val nonces = NonceCache(skewMillis)
    private val ipLimiter = RateLimiter(limits.ipBurst, limits.ipPerSecond)
    private val sessionLimiter = RateLimiter(limits.sessionBurst, limits.sessionPerSecond)
    private val accountLimiter = RateLimiter(limits.accountBurst, limits.accountPerSecond)
    private val inFlight = InFlightLimiter(limits.inFlightPerAccount)
    val otpPerEmail = RateLimiter.perWindow(limits.otpPerEmail, 15 * 60)
    val otpPerIp = RateLimiter.perWindow(limits.otpPerIpHour, 60 * 60)
    val verifyPerIp = RateLimiter.perWindow(limits.verifyPerIpHour, 60 * 60)

    fun checkIp(ip: String) {
        val wait = ipLimiter.take(ip)
        if (wait > 0) throw tooManyRequests(wait)
    }

    fun limit(limiter: RateLimiter, key: String, message: String) {
        val wait = limiter.take(key)
        if (wait > 0) throw tooManyRequests(wait, message)
    }

    fun authenticate(
        sessionId: String?,
        timestamp: String?,
        nonce: String?,
        signature: String?,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
    ): Principal {
        val now = clock.millis()
        if (sessionId == null || timestamp == null || nonce == null || signature == null) {
            throw unauthorized("auth_required", "Signed request headers are required.", now)
        }
        val ts = timestamp.toLongOrNull() ?: throw unauthorized("bad_timestamp", "Invalid timestamp.", now)
        if (kotlin.math.abs(now - ts) > skewMillis) throw unauthorized("clock_skew", "Timestamp outside the allowed window.", now)
        // Nonces are held in memory; anything signed before this process started could
        // have been seen by a previous instance, so refuse it.
        if (ts < bootMillis) throw unauthorized("clock_skew", "Request predates server start; please retry.", now)
        if (!RequestSignature.isValidNonce(nonce)) throw unauthorized("bad_nonce", "Invalid nonce.", now)

        val session = sessions.find(sessionId, clock.instant())
            ?: throw unauthorized("session_invalid", "Session expired or revoked. Please sign in again.", now)
        val canonical = RequestSignature.canonical(method, pathAndQuery, ts, nonce, body)
        if (!RequestSignature.verify(session.publicKey, canonical, signature)) {
            throw unauthorized("bad_signature", "Signature verification failed.", now)
        }
        if (!nonces.claim(session.id, nonce, now)) throw unauthorized("replayed", "This request was already used.", now)
        sessions.touch(session.id, clock.instant())
        return Principal(session.accountId, session.id)
    }

    /** Charges [cost] against the session's and account's budgets. */
    fun charge(p: Principal, cost: Double = 1.0) {
        val s = sessionLimiter.take(p.sessionId, cost)
        if (s > 0) throw tooManyRequests(s)
        val a = accountLimiter.take(p.accountId.toString(), cost)
        if (a > 0) throw tooManyRequests(a)
    }

    fun enter(p: Principal) {
        if (!inFlight.tryAcquire(p.accountId.toString())) {
            throw ApiException(io.ktor.http.HttpStatusCode.TooManyRequests, "too_many_in_flight", "Too many concurrent requests.", mapOf("Retry-After" to "1"))
        }
    }

    fun leave(p: Principal) = inFlight.release(p.accountId.toString())
}
