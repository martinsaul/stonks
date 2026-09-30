package stonks.engine.world

import stonks.engine.company.Sector
import stonks.engine.core.Rng
import stonks.engine.strategy.StrategyType
import kotlin.math.ln

/**
 * Company event catalog (docs/DESIGN.md, "Events"). Jumps are simple returns drawn
 * uniformly from [minJump, maxJump]; some events can also change the strategy.
 */
enum class EventType(
    val category: NewsCategory,
    val major: Boolean,
    val minJump: Double,
    val maxJump: Double,
    /** Chance of switching strategy, and to what (weighted). */
    val strategyChance: Double = 0.0,
    val strategies: Map<StrategyType, Double> = emptyMap(),
) {
    ANALYST_UPGRADE(NewsCategory.ANALYST, false, 0.01, 0.04),
    ANALYST_DOWNGRADE(NewsCategory.ANALYST, false, -0.04, -0.01),
    PRODUCT_NEWS_GOOD(NewsCategory.CORPORATE, false, 0.01, 0.05),
    PRODUCT_NEWS_BAD(NewsCategory.CORPORATE, false, -0.05, -0.01),
    PRODUCT_HIT(NewsCategory.CORPORATE, true, 0.05, 0.20, 0.25, mapOf(StrategyType.STEADY_GROWTH to 2.0, StrategyType.PARABOLIC to 1.0)),
    PRODUCT_FLOP(NewsCategory.CORPORATE, true, -0.20, -0.05, 0.25, mapOf(StrategyType.SLOW_DECLINE to 2.0, StrategyType.STAGNANT to 1.0)),
    SCANDAL(NewsCategory.CORPORATE, true, -0.40, -0.10, 0.35, mapOf(StrategyType.SLOW_DECLINE to 2.0, StrategyType.DEATH_SPIRAL to 1.0, StrategyType.VOLATILE to 1.0)),
    REG_APPROVAL(NewsCategory.REGULATORY, true, 0.20, 0.60, 0.30, mapOf(StrategyType.STEADY_GROWTH to 2.0, StrategyType.PARABOLIC to 1.0)),
    REG_REJECTION(NewsCategory.REGULATORY, true, -0.60, -0.20, 0.30, mapOf(StrategyType.SLOW_DECLINE to 2.0, StrategyType.VOLATILE to 1.0)),
    CEO_EXIT(NewsCategory.CORPORATE, true, -0.10, -0.03, 0.15, mapOf(StrategyType.VOLATILE to 1.0, StrategyType.STAGNANT to 1.0)),
    STAR_CEO(NewsCategory.CORPORATE, true, 0.03, 0.10, 0.30, mapOf(StrategyType.TURNAROUND to 1.0, StrategyType.STEADY_GROWTH to 1.0)),
    LAWSUIT_WON(NewsCategory.LEGAL, true, 0.05, 0.15),
    LAWSUIT_LOST(NewsCategory.LEGAL, true, -0.15, -0.05),
    SECONDARY_OFFERING(NewsCategory.CORPORATE, true, -0.10, -0.03),
    BUYBACK(NewsCategory.CORPORATE, true, 0.01, 0.04),
    /** Handled specially: a takeover bid at a premium, closing (or breaking) later. */
    BUYOUT_OFFER(NewsCategory.DEAL, true, 0.0, 0.0);

    fun sampleJump(rng: Rng): Double = ln(1 + rng.nextDouble(minJump, maxJump))

    companion object {
        val minor = entries.filter { !it.major }

        /** Major event odds for a sector (regulatory news is mostly a pharma thing). */
        fun majorWeights(sector: Sector): Map<EventType, Double> = entries.filter { it.major }.associateWith { t ->
            when (t) {
                REG_APPROVAL, REG_REJECTION -> if (sector == Sector.PHARMA) 4.0 else 0.3
                BUYOUT_OFFER -> 0.6
                SECONDARY_OFFERING, BUYBACK -> 0.8
                else -> 1.0
            }
        }
    }
}

/** What happens on an agenda entry. */
enum class AgendaKind { EVENT, RUMOR, JUMP }

/**
 * Something scheduled for a ticker at ([day], [tick]); tick -1 = before the open.
 * EVENT applies [type]'s effects; RUMOR only publishes a headline; JUMP applies
 * [jump] with [headline] (lifecycle news).
 */
data class AgendaItem(
    val day: Int,
    val tick: Int,
    val kind: AgendaKind,
    val type: EventType? = null,
    val headline: String? = null,
    val jump: Double = 0.0,
    val category: NewsCategory = NewsCategory.CORPORATE,
)
