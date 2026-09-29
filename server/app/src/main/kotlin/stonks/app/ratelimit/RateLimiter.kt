package stonks.app.ratelimit

import java.util.concurrent.ConcurrentHashMap

/** Classic token bucket. Thread-safe. */
class TokenBucket(private val capacity: Double, private val perSecond: Double, nowNanos: Long) {
    private var tokens = capacity
    private var last = nowNanos

    /** Takes [cost] tokens if available; otherwise returns seconds until they will be. */
    @Synchronized
    fun take(cost: Double, nowNanos: Long): Double {
        tokens = minOf(capacity, tokens + (nowNanos - last) / 1e9 * perSecond)
        last = nowNanos
        if (tokens >= cost) {
            tokens -= cost
            return 0.0
        }
        return (cost - tokens) / perSecond
    }

    @Synchronized
    fun isFull(nowNanos: Long): Boolean = tokens + (nowNanos - last) / 1e9 * perSecond >= capacity
}

/**
 * A family of token buckets keyed by string (session id, account id, IP, email…).
 * Idle, full buckets are evicted periodically.
 */
class RateLimiter(
    private val capacity: Double,
    private val perSecond: Double,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    @Volatile private var nextSweep = 0L

    /** Returns 0 when allowed, otherwise the seconds to wait. */
    fun take(key: String, cost: Double = 1.0): Double {
        val now = nanoTime()
        if (now >= nextSweep) sweep(now)
        return buckets.computeIfAbsent(key) { TokenBucket(capacity, perSecond, now) }.take(cost, now)
    }

    private fun sweep(now: Long) {
        nextSweep = now + 60_000_000_000L
        buckets.entries.removeIf { it.value.isFull(now) }
    }

    companion object {
        /** A limiter allowing [count] events per [seconds], with the full count as burst. */
        fun perWindow(count: Int, seconds: Long, nanoTime: () -> Long = System::nanoTime) =
            RateLimiter(count.toDouble(), count.toDouble() / seconds, nanoTime)
    }
}

/** Caps concurrent in-flight work per key. */
class InFlightLimiter(private val max: Int) {
    private val counts = ConcurrentHashMap<String, Int>()

    fun tryAcquire(key: String): Boolean {
        var ok = false
        counts.compute(key) { _, n ->
            val c = n ?: 0
            if (c < max) { ok = true; c + 1 } else c
        }
        return ok
    }

    fun release(key: String) {
        counts.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
    }
}
