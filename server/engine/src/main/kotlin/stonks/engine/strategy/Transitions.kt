package stonks.engine.strategy

import stonks.engine.strategy.StrategyType.*

/**
 * Weighted odds for the strategy that follows a completed one. Admin-tunable later;
 * these defaults keep most companies in "normal" regimes most of the time.
 */
object Transitions {
    val defaults: Map<StrategyType, Map<StrategyType, Double>> = mapOf(
        STEADY_GROWTH to mapOf(
            STEADY_GROWTH to 45.0, PARABOLIC to 5.0, STAGNANT to 15.0, CYCLICAL to 10.0,
            SLOW_DECLINE to 12.0, VOLATILE to 8.0, DIVIDEND_ARISTOCRAT to 5.0,
        ),
        PARABOLIC to mapOf(BLOW_OFF_TOP to 40.0, STEADY_GROWTH to 20.0, VOLATILE to 25.0, STAGNANT to 15.0),
        BLOW_OFF_TOP to mapOf(SLOW_DECLINE to 30.0, TURNAROUND to 25.0, VOLATILE to 25.0, STAGNANT to 20.0),
        STAGNANT to mapOf(
            STAGNANT to 30.0, STEADY_GROWTH to 20.0, SLOW_DECLINE to 20.0, CYCLICAL to 15.0,
            VOLATILE to 10.0, PARABOLIC to 5.0,
        ),
        SLOW_DECLINE to mapOf(
            SLOW_DECLINE to 25.0, STAGNANT to 25.0, TURNAROUND to 20.0, DEATH_SPIRAL to 15.0, VOLATILE to 15.0,
        ),
        DEATH_SPIRAL to mapOf(TURNAROUND to 40.0, VOLATILE to 30.0, SLOW_DECLINE to 30.0),
        CYCLICAL to mapOf(CYCLICAL to 40.0, STEADY_GROWTH to 20.0, STAGNANT to 20.0, SLOW_DECLINE to 20.0),
        TURNAROUND to mapOf(STEADY_GROWTH to 40.0, VOLATILE to 20.0, PARABOLIC to 10.0, STAGNANT to 30.0),
        VOLATILE to mapOf(
            VOLATILE to 20.0, STEADY_GROWTH to 20.0, SLOW_DECLINE to 20.0, PARABOLIC to 15.0,
            STAGNANT to 15.0, DEATH_SPIRAL to 10.0,
        ),
        DIVIDEND_ARISTOCRAT to mapOf(DIVIDEND_ARISTOCRAT to 60.0, STEADY_GROWTH to 15.0, STAGNANT to 25.0),
    )

    /** Initial strategy mix for a fresh world. */
    val initial: Map<StrategyType, Double> = mapOf(
        STEADY_GROWTH to 30.0, STAGNANT to 15.0, CYCLICAL to 12.0, DIVIDEND_ARISTOCRAT to 10.0,
        SLOW_DECLINE to 10.0, VOLATILE to 10.0, PARABOLIC to 5.0, TURNAROUND to 5.0, BLOW_OFF_TOP to 3.0,
    )
}
