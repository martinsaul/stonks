package stonks.app.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers (session, nonce) pairs for as long as their timestamp is acceptable, so a
 * captured request can never be replayed. In memory: the backend is a single instance,
 * and requests timestamped before the process started are rejected separately.
 */
class NonceCache(private val windowMillis: Long) {
    private val seen = ConcurrentHashMap<String, Long>()
    @Volatile private var nextSweep = 0L

    /** Records the nonce; returns false if it was already used. */
    fun claim(sessionId: String, nonce: String, nowMillis: Long): Boolean {
        if (nowMillis >= nextSweep) sweep(nowMillis)
        return seen.putIfAbsent("$sessionId:$nonce", nowMillis + 2 * windowMillis) == null
    }

    private fun sweep(nowMillis: Long) {
        nextSweep = nowMillis + windowMillis
        seen.entries.removeIf { it.value < nowMillis }
    }

    val size: Int get() = seen.size
}
