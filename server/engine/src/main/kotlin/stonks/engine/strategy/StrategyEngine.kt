package stonks.engine.strategy

import stonks.engine.core.Rng
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.writeList
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

data class StrategyRecord(val instance: StrategyInstance, val startDay: Int)

/**
 * Owns a company's current strategy and its internal curve state, and produces the
 * idiosyncratic part of each tick's log return.
 *
 * The curve is independent of the traded price; its returns are applied on top of
 * wherever players left the price.
 */
class StrategyEngine(
    initial: StrategyInstance,
    private var rng: Rng,
    private val transitions: Map<StrategyType, Map<StrategyType, Double>> = Transitions.defaults,
) {
    var current: StrategyInstance = initial
        private set
    private var elapsedDays = 0
    private var gameDay = 0
    private var ouDeviation = 0.0

    /** Called when a strategy completes, before the next one is chosen. */
    var onComplete: (StrategyInstance) -> Unit = {}

    val history = mutableListOf(StrategyRecord(initial, 0))

    fun writeTo(out: DataOutputStream) {
        out.writeLong(rng.state)
        current.writeTo(out)
        out.writeInt(elapsedDays)
        out.writeInt(gameDay)
        out.writeDouble(ouDeviation)
        out.writeList(history) { it.instance.writeTo(this); writeInt(it.startDay) }
    }

    internal fun restore(input: DataInputStream) {
        rng = Rng.restore(input.readLong())
        current = StrategyInstance.readFrom(input)
        elapsedDays = input.readInt()
        gameDay = input.readInt()
        ouDeviation = input.readDouble()
        history.clear()
        history += input.readList { StrategyRecord(StrategyInstance.readFrom(this), readInt()) }
    }

    val progressDays: Int get() = elapsedDays

    /** Replaces the current strategy immediately (events, admin). */
    fun force(next: StrategyInstance) {
        current = next
        elapsedDays = 0
        ouDeviation = 0.0
        history += StrategyRecord(next, gameDay)
    }

    /** Advances to the next game day, transitioning if the strategy has run its course. */
    fun beginDay() {
        if (elapsedDays >= current.durationDays) {
            val done = current
            onComplete(done)
            val nextType = rng.weighted(transitions.getValue(done.type))
            force(StrategyInstance.sample(nextType, rng))
        }
    }

    fun endDay() {
        elapsedDays++
        gameDay++
    }

    /**
     * Idiosyncratic log return for one tick.
     *
     * @param tick tick index within the session
     * @param ticksInSession session length in ticks (one game day)
     * @param volMultiplier session/regime volatility multiplier
     * @param shock a standard normal draw
     */
    fun tickReturn(tick: Int, ticksInSession: Int, volMultiplier: Double, driftAdd: Double, shock: Double): Double {
        val dt = 1.0 / (252.0 * ticksInSession)
        val dayFraction0 = elapsedDays + tick.toDouble() / ticksInSession
        val dayFraction1 = elapsedDays + (tick + 1).toDouble() / ticksInSession
        val progress = dayFraction0 / current.durationDays
        val sigma = current.volAt(progress) * volMultiplier
        val diffusion = sigma * sqrt(dt) * shock

        val theta = current.reversionSpeed
        return if (theta > 0.0) {
            val delta = -theta * ouDeviation * dt + diffusion
            ouDeviation += delta
            delta + driftAdd * dt
        } else {
            (current.driftAt(progress) + driftAdd) * dt + diffusion +
                current.levelAt(dayFraction1) - current.levelAt(dayFraction0)
        }
    }

    /**
     * Log return for the closed period before a session (overnight/break gap). Variance
     * only: a game day's drift is fully applied during its session.
     */
    fun gapReturn(closedDayFraction: Double, volMultiplier: Double, shock: Double): Double {
        val dt = closedDayFraction / 252.0
        val progress = elapsedDays.toDouble() / current.durationDays
        return current.volAt(progress) * volMultiplier * sqrt(dt) * shock
    }
}
