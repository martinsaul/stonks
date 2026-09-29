package stonks.engine.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deterministic random source (SplitMix64). All distributions are implemented here so
 * that a seed produces identical worlds across JVMs, and the whole generator state is a
 * single [state] value that can be snapshotted and restored.
 */
class Rng(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        return mix64(state)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT
    fun nextDouble(from: Double, until: Double): Double = from + (until - from) * nextDouble()

    /** Uniform in [from, until). */
    fun nextInt(from: Int, until: Int): Int {
        require(until > from) { "empty range" }
        val bound = (until.toLong() - from)
        // Rejection sampling avoids modulo bias.
        val limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound)
        while (true) {
            val r = nextLong() ushr 1
            if (r < limit) return (from + r % bound).toInt()
        }
    }

    fun chance(p: Double): Boolean = nextDouble() < p

    /** Standard normal via Box–Muller (one value per call; simple and deterministic). */
    fun gaussian(): Double {
        var u1 = nextDouble()
        while (u1 <= Double.MIN_VALUE) u1 = nextDouble()
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    fun logNormal(median: Double, sigma: Double): Double = median * exp(sigma * gaussian())

    /** Poisson via Knuth; fine for the small rates used per tick. */
    fun poisson(lambda: Double): Int {
        if (lambda <= 0.0) return 0
        val l = exp(-lambda)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= nextDouble()
        } while (p > l)
        return k - 1
    }

    fun <T> weighted(items: Map<T, Double>): T {
        val total = items.values.sum()
        var x = nextDouble() * total
        for ((item, w) in items) {
            x -= w
            if (x < 0) return item
        }
        return items.keys.last()
    }

    fun <T> pick(items: List<T>): T = items[nextInt(0, items.size)]

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        private fun mix64(input: Long): Long {
            var z = input
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** Derives an independent child seed. */
        fun derive(seed: Long, stream: Long): Long = mix64(seed + stream * GOLDEN_GAMMA)

        /** Recreates a generator from a saved [state]. */
        fun restore(state: Long): Rng = Rng(state)
    }
}
