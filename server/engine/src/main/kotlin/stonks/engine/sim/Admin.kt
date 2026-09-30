package stonks.engine.sim

import stonks.engine.company.Sector
import stonks.engine.core.Cents
import stonks.engine.strategy.StrategyType

/**
 * Game-master actions (docs/DESIGN.md, "Admin console"). Like player inputs, they are
 * logged and replayed, so they must be applied through [Market.admin].
 */
sealed interface AdminAction {
    /** Switch a company's strategy now, or at its next open. */
    data class SetStrategy(val ticker: String, val strategy: StrategyType, val nextSession: Boolean) : AdminAction

    /**
     * A company event (an EventType name, or DISTRESS / BUYOUT) at ([day], [tick]);
     * null day = now (or the next open while closed).
     */
    data class CompanyEvent(val ticker: String, val event: String, val day: Int? = null, val tick: Int? = null) : AdminAction

    /**
     * A price move of [percent] for one ticker, a sector or the whole market, with a
     * headline, now or at ([day], [tick]).
     */
    data class Shock(
        val scope: Scope, val target: String?, val percent: Double, val headline: String,
        val day: Int? = null, val tick: Int? = null,
    ) : AdminAction {
        enum class Scope { TICKER, SECTOR, MARKET }
    }

    data class SetRate(val percent: Double) : AdminAction
    data class SetRegime(val regime: MarketRegime) : AdminAction

    /** Halt or resume one ticker, or all of them when [ticker] is null. */
    data class Halt(val ticker: String?, val halted: Boolean) : AdminAction

    /** Tune a ticker: volatility and depth multipliers, borrow pool fraction. */
    data class Tune(val ticker: String, val volatility: Double?, val depth: Double?, val borrowPool: Double?) : AdminAction

    data class Split(val ticker: String, val ratio: Double) : AdminAction
    data class SpecialDividend(val ticker: String, val amount: Cents) : AdminAction
    data class Buyback(val ticker: String, val percent: Double) : AdminAction

    /** List a new company in [days] sessions (optionally in [sector]). */
    data class Ipo(val sector: Sector?, val days: Int) : AdminAction

    data class IssueBonds(val name: String, val returnPct: Double, val windowDays: Int, val termDays: Int, val capPerPlayer: Cents) : AdminAction

    /** Reverse a player's fill at its price and refund its commission. */
    data class VoidFill(
        val accountId: Long, val ticker: String, val side: stonks.engine.core.Side, val quantity: Long, val price: Cents, val commission: Cents,
    ) : AdminAction

    /** Credit or debit a player's cash (corrections). */
    data class AdjustCash(val accountId: Long, val amount: Cents, val reason: String) : AdminAction
}

/** A market or sector shock waiting for its time. */
data class ScheduledShock(
    val day: Int, val tick: Int, val scope: AdminAction.Shock.Scope, val target: String?, val logReturn: Double, val headline: String,
)
