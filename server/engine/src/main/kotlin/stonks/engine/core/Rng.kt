package stonks.engine.core

import java.util.SplittableRandom
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deterministic random source. All distributions are implemented here rather than
 * delegated to JDK defaults so that a seed produces identical worlds across JVMs.
 */
class Rng(seed: Long) {
    private val r = SplittableRandom(seed)

    fun nextDouble(): Double = r.nextDouble()
    fun nextDouble(from: Double, until: Double): Double = from + (until - from) * r.nextDouble()
    fun nextInt(from: Int, until: Int): Int = r.nextInt(from, until)
    fun nextLong(): Long = r.nextLong()
    fun chance(p: Double): Boolean = r.nextDouble() < p

    /** Standard normal via Box–Muller (one value per call; simple and deterministic). */
    fun gaussian(): Double {
        var u1 = r.nextDouble()
        while (u1 <= Double.MIN_VALUE) u1 = r.nextDouble()
        val u2 = r.nextDouble()
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
            p *= r.nextDouble()
        } while (p > l)
        return k - 1
    }

    fun <T> weighted(items: Map<T, Double>): T {
        val total = items.values.sum()
        var x = r.nextDouble() * total
        for ((item, w) in items) {
            x -= w
            if (x < 0) return item
        }
        return items.keys.last()
    }

    fun <T> pick(items: List<T>): T = items[r.nextInt(items.size)]

    companion object {
        /** Derives an independent child seed (SplitMix64 finalizer). */
        fun derive(seed: Long, stream: Long): Long {
            var z = seed + stream * -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
