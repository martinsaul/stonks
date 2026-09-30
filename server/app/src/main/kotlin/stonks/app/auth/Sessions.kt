package stonks.app.auth

import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.singleOrNull
import stonks.app.db.update
import java.security.PublicKey
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class AuthSession(
    val id: String,
    val accountId: Long,
    val publicKey: PublicKey,
    val expiresAt: Instant,
)

/**
 * Device-bound sessions. Looked up on every request, so kept in a short-lived cache;
 * revocation evicts immediately on this instance.
 */
class SessionStore(
    private val db: Db,
    private val ttl: Duration = Duration.ofDays(30),
    private val maxPerAccount: Int = 5,
) {
    private val random = SecureRandom()
    private val cache = ConcurrentHashMap<String, Pair<AuthSession, Long>>()
    private val lastSeen = ConcurrentHashMap<String, Instant>()

    fun create(c: Connection, accountId: Long, publicKeyDer: ByteArray, now: Instant, userAgent: String?, ip: String?): AuthSession {
        val id = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = now.plus(ttl)
        c.update(
            "insert into sessions (id, account_id, public_key, created_at, last_seen_at, expires_at, user_agent, ip) values (?, ?, ?, ?, ?, ?, ?, ?)",
            id, accountId, publicKeyDer, now, now, expires, userAgent?.take(200), ip,
        )
        // Keep only the newest sessions per account.
        val stale = c.query(
            "select id from sessions where account_id = ? and revoked_at is null order by created_at desc offset ?",
            accountId, maxPerAccount,
        ) { rs -> rs.list { it.getString(1) } }
        stale.forEach { revoke(c, it, now) }
        return AuthSession(id, accountId, RequestSignature.publicKeyFromDer(publicKeyDer), expires)
    }

    fun find(id: String, now: Instant): AuthSession? {
        val nowMs = now.toEpochMilli()
        val cached = cache[id]
        val session = if (cached != null && cached.second > nowMs) {
            cached.first
        } else {
            // Only real sessions are cached, so random ids cannot grow the cache.
            load(id)?.also {
                if (cache.size >= MAX_CACHED) cache.clear()
                cache[id] = it to nowMs + CACHE_MILLIS
            }
        }
        return session?.takeIf { it.expiresAt.isAfter(now) }
    }

    /** Drops cached sessions of [accountId] (after a ban revoked them in the database). */
    fun forgetAccount(accountId: Long) {
        cache.entries.removeIf { it.value.first.accountId == accountId }
    }

    fun revoke(c: Connection, id: String, now: Instant) {
        c.update("update sessions set revoked_at = ? where id = ? and revoked_at is null", now, id)
        cache.remove(id)
    }

    /** Records activity; flushed to the database in batches by [flushLastSeen]. */
    fun touch(id: String, now: Instant) {
        lastSeen[id] = now
    }

    fun flushLastSeen() {
        if (lastSeen.isEmpty()) return
        val batch = HashMap(lastSeen)
        batch.keys.forEach { lastSeen.remove(it) }
        db.tx { c ->
            c.prepareStatement("update sessions set last_seen_at = ? where id = ?").use { ps ->
                for ((id, at) in batch) {
                    ps.setTimestamp(1, java.sql.Timestamp.from(at))
                    ps.setString(2, id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun load(id: String): AuthSession? = db.read { c ->
        c.query("select * from sessions where id = ? and revoked_at is null", id) { rs ->
            rs.singleOrNull {
                AuthSession(
                    it.getString("id"),
                    it.getLong("account_id"),
                    RequestSignature.publicKeyFromDer(it.getBytes("public_key")),
                    it.instant("expires_at")!!,
                )
            }
        }
    }

    companion object {
        private const val CACHE_MILLIS = 30_000L
        private const val MAX_CACHED = 100_000
    }
}
