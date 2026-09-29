package stonks.engine.strategy

import stonks.engine.core.Rng
import stonks.engine.snapshot.readDoubles
import stonks.engine.snapshot.writeDoubles
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Long-term valuation strategies (docs/DESIGN.md, "Strategies").
 *
 * A strategy describes the *returns* of a company's pre-computed curve, never a
 * target price level: player-driven moves are permanent.
 */
enum class StrategyType(val durationDays: IntRange) {
    STEADY_GROWTH(120..400),
    PARABOLIC(20..80),
    BLOW_OFF_TOP(40..120),
    STAGNANT(60..250),
    SLOW_DECLINE(60..200),
    DEATH_SPIRAL(20..60),
    CYCLICAL(120..400),
    TURNAROUND(40..120),
    VOLATILE(30..120),
    DIVIDEND_ARISTOCRAT(200..600),
}

/**
 * One sampled run of a strategy. Parameters are drawn once at creation so the same
 * type behaves differently across companies and runs.
 *
 * Drift ([driftAt]) and volatility ([volAt]) are annualized log terms as a function of
 * progress `p` in [0, 1]. [levelAt] is an extra deterministic log-level path (used by
 * CYCLICAL) whose increments are added to returns.
 */
class StrategyInstance(
    val type: StrategyType,
    val durationDays: Int,
    private val p: DoubleArray,
) {
    val durationYears: Double get() = durationDays / 252.0

    fun driftAt(progress: Double): Double = when (type) {
        StrategyType.STEADY_GROWTH, StrategyType.SLOW_DECLINE,
        StrategyType.VOLATILE, StrategyType.DIVIDEND_ARISTOCRAT, StrategyType.CYCLICAL -> p[MU]
        StrategyType.PARABOLIC, StrategyType.DEATH_SPIRAL -> p[MU] * exp(p[K] * progress)
        StrategyType.BLOW_OFF_TOP ->
            if (progress < p[SPLIT]) p[MU] * exp(p[K] * progress / p[SPLIT])
            else ln(1 - p[DROP]) / ((1 - p[SPLIT]) * durationYears)
        StrategyType.TURNAROUND -> if (progress < p[SPLIT]) p[MU] else p[MU2]
        StrategyType.STAGNANT -> 0.0
    }

    fun volAt(progress: Double): Double = when (type) {
        StrategyType.PARABOLIC -> p[SIGMA] * (1 + 0.6 * progress)
        StrategyType.BLOW_OFF_TOP -> if (progress < p[SPLIT]) p[SIGMA] * (1 + 0.6 * progress) else p[SIGMA] * 1.6
        else -> p[SIGMA]
    }

    fun levelAt(elapsedDays: Double): Double =
        if (type == StrategyType.CYCLICAL) p[AMPLITUDE] * sin(2 * PI * elapsedDays / p[PERIOD]) else 0.0

    /** Mean-reversion speed of the curve's own deviation (STAGNANT only), per year. */
    val reversionSpeed: Double get() = if (type == StrategyType.STAGNANT) p[THETA] else 0.0

    /** Death spirals hand the company to Distress when they complete (Milestone 5). */
    val leadsToDistress: Boolean get() = type == StrategyType.DEATH_SPIRAL

    override fun toString() = "$type(${durationDays}d)"

    fun writeTo(out: DataOutputStream) {
        out.writeUTF(type.name)
        out.writeInt(durationDays)
        out.writeDoubles(p)
    }

    companion object {
        fun readFrom(input: DataInputStream) =
            StrategyInstance(StrategyType.valueOf(input.readUTF()), input.readInt(), input.readDoubles())

        private const val MU = 0
        private const val SIGMA = 1
        private const val K = 2
        private const val SPLIT = 3
        private const val DROP = 4
        private const val MU2 = 5
        private const val AMPLITUDE = 6
        private const val PERIOD = 7
        private const val THETA = 8

        fun sample(type: StrategyType, rng: Rng): StrategyInstance {
            val p = DoubleArray(9)
            fun u(a: Double, b: Double) = rng.nextDouble(a, b)
            when (type) {
                StrategyType.STEADY_GROWTH -> { p[MU] = u(0.06, 0.14); p[SIGMA] = u(0.15, 0.25) }
                StrategyType.PARABOLIC -> { p[MU] = u(0.6, 1.2); p[K] = u(2.0, 3.0); p[SIGMA] = u(0.35, 0.45) }
                StrategyType.BLOW_OFF_TOP -> {
                    p[MU] = u(0.6, 1.2); p[K] = u(2.0, 3.0); p[SIGMA] = u(0.35, 0.45)
                    p[SPLIT] = u(0.6, 0.75); p[DROP] = u(0.5, 0.8)
                }
                StrategyType.STAGNANT -> {
                    p[SIGMA] = u(0.12, 0.20)
                    val rangeSd = u(0.04, 0.10)
                    p[THETA] = p[SIGMA] * p[SIGMA] / (2 * rangeSd * rangeSd)
                }
                StrategyType.SLOW_DECLINE -> { p[MU] = u(-0.25, -0.08); p[SIGMA] = u(0.20, 0.30) }
                StrategyType.DEATH_SPIRAL -> { p[MU] = u(-1.5, -0.8); p[K] = u(1.5, 2.5); p[SIGMA] = u(0.5, 0.8) }
                StrategyType.CYCLICAL -> {
                    p[MU] = u(-0.02, 0.08); p[SIGMA] = u(0.18, 0.28)
                    p[AMPLITUDE] = u(0.10, 0.30); p[PERIOD] = u(20.0, 60.0)
                }
                StrategyType.TURNAROUND -> {
                    p[SPLIT] = u(0.3, 0.6); p[MU] = u(-1.5, -0.8); p[MU2] = u(1.0, 2.0); p[SIGMA] = u(0.30, 0.45)
                }
                StrategyType.VOLATILE -> { p[MU] = u(-0.2, 0.2); p[SIGMA] = u(0.6, 1.0) }
                StrategyType.DIVIDEND_ARISTOCRAT -> { p[MU] = u(0.05, 0.10); p[SIGMA] = u(0.10, 0.15) }
            }
            val duration = rng.nextInt(type.durationDays.first, type.durationDays.last + 1)
            return StrategyInstance(type, duration, p)
        }
    }
}
