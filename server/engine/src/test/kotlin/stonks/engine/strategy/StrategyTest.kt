package stonks.engine.strategy

import stonks.engine.core.Rng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class StrategyTest {
    /** Sums a strategy's curve log returns over its full duration (no market factors). */
    private fun totalLogReturn(type: StrategyType, seed: Long): Double {
        val rng = Rng(seed)
        val engine = StrategyEngine(StrategyInstance.sample(type, rng), rng)
        val days = engine.current.durationDays
        var total = 0.0
        repeat(days) {
            engine.beginDay()
            for (t in 0 until 450) total += engine.tickReturn(t, 450, 1.0, 0.0, rng.gaussian())
            engine.endDay()
        }
        return total
    }

    private fun median(type: StrategyType) = (1L..41L).map { totalLogReturn(type, it) }.sorted()[20]

    @Test
    fun `growth strategies rise and decline strategies fall`() {
        assertTrue(median(StrategyType.STEADY_GROWTH) > 0)
        assertTrue(median(StrategyType.PARABOLIC) > 0.5, "parabolic should at least ~1.6x")
        assertTrue(median(StrategyType.DEATH_SPIRAL) < -0.3, "death spiral should lose ~25%+")
        assertTrue(median(StrategyType.SLOW_DECLINE) < 0)
        assertTrue(median(StrategyType.BLOW_OFF_TOP) < median(StrategyType.PARABOLIC))
    }

    @Test
    fun `stagnant stays range bound`() {
        val results = (1L..41L).map { abs(totalLogReturn(StrategyType.STAGNANT, it)) }.sorted()
        assertTrue(results[20] < 0.15, "median |move| ${results[20]}")
    }

    @Test
    fun `strategies transition when their duration elapses`() {
        val rng = Rng(1)
        val engine = StrategyEngine(StrategyInstance.sample(StrategyType.PARABOLIC, rng), rng)
        val duration = engine.current.durationDays
        repeat(duration + 1) { engine.beginDay(); engine.endDay() }
        assertTrue(engine.history.size == 2)
        assertTrue(engine.history[1].startDay == duration)
    }
}
