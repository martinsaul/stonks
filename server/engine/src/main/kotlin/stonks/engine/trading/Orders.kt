package stonks.engine.trading

import stonks.engine.book.TimeInForce
import stonks.engine.core.Cents
import stonks.engine.core.Side

enum class OrderKind { MARKET, LIMIT, STOP, STOP_LIMIT, TRAILING_STOP, TRAILING_STOP_LIMIT, TWAP, VWAP;
    val isStop: Boolean get() = this == STOP || this == STOP_LIMIT || this == TRAILING_STOP || this == TRAILING_STOP_LIMIT
    val isAlgo: Boolean get() = this == TWAP || this == VWAP
}

/** How the legs of a request relate. */
enum class Structure {
    /** One order. */
    SINGLE,
    /** Two orders; the first to fill cancels the other. */
    OCO,
    /** A parent; the other legs activate once it has filled. */
    OTO,
    /** Entry, take-profit and stop-loss; the exits activate after the entry fills and cancel each other. */
    BRACKET,
}

enum class LegStatus {
    /** A child waiting for its parent to fill. */
    WAITING,
    /** In the intake queue, waiting for its execution tick. */
    QUEUED,
    /** A stop waiting for its trigger. */
    ARMED,
    /** Resting in the book, or an algo order running. */
    WORKING,
    FILLED, CANCELLED, EXPIRED, REJECTED;

    val done: Boolean get() = ordinal >= FILLED.ordinal
}

data class LegSpec(
    val side: Side,
    val quantity: Long,
    val kind: OrderKind,
    val limit: Cents? = null,
    val stop: Cents? = null,
    val trailAmount: Cents? = null,
    /** Trailing distance as a percentage (e.g. 5.0 = 5%). */
    val trailPercent: Double? = null,
    /** For trailing stop-limits: limit distance beyond the stop, cents. */
    val limitOffset: Cents? = null,
    val tif: TimeInForce = TimeInForce.DAY,
    /** Algo orders: how many ticks to spread execution over. */
    val durationTicks: Int? = null,
)

data class OrderRequest(val ticker: String, val structure: Structure, val legs: List<LegSpec>) {
    companion object {
        /**
         * Hard input bounds. Keeping every price and quantity within these guarantees
         * `quantity × price` (at most 10^17 cents) can't overflow a Long.
         */
        const val MAX_PRICE: Long = 100_000_000 // $1,000,000.00 per share, in cents
        const val MAX_QUANTITY: Long = 1_000_000_000
    }

    /** Returns a reason the request is invalid, or null. */
    fun validate(): String? {
        if (legs.isEmpty()) return "An order needs at least one leg."
        legs.forEach { l -> validateLeg(l)?.let { return it } }
        return when (structure) {
            Structure.SINGLE -> if (legs.size != 1) "A single order has exactly one leg." else null
            Structure.OCO -> when {
                legs.size != 2 -> "An OCO order has exactly two legs."
                legs.any { it.kind.isAlgo } -> "Algo orders can't be part of an OCO."
                else -> null
            }
            Structure.OTO -> if (legs.size !in 2..3) "An OTO order has a parent and one or two children." else null
            Structure.BRACKET -> {
                val (entry, tp, sl) = legs.takeIf { it.size == 3 } ?: return "A bracket has entry, take-profit and stop-loss legs."
                when {
                    entry.kind != OrderKind.MARKET && entry.kind != OrderKind.LIMIT -> "A bracket entry must be a market or limit order."
                    tp.kind != OrderKind.LIMIT -> "A bracket take-profit must be a limit order."
                    !sl.kind.isStop -> "A bracket stop-loss must be a stop order."
                    tp.side == entry.side || sl.side == entry.side -> "Bracket exits must be on the opposite side of the entry."
                    else -> null
                }
            }
        }
    }

    private fun validateLeg(l: LegSpec): String? {
        fun positive(v: Long?, name: String) = when {
            v == null || v <= 0 -> "$name must be a positive price."
            v > MAX_PRICE -> "$name can't exceed ${'$'}1,000,000 per share."
            else -> null
        }
        if (l.quantity <= 0 || l.quantity > MAX_QUANTITY) return "Quantity must be between 1 and 1,000,000,000 shares."
        return when (l.kind) {
            OrderKind.MARKET -> if (l.tif == TimeInForce.GTC) "Market orders can't be good-till-cancelled." else null
            OrderKind.LIMIT -> positive(l.limit, "Limit")
            OrderKind.STOP -> positive(l.stop, "Stop")
            OrderKind.STOP_LIMIT -> positive(l.stop, "Stop") ?: positive(l.limit, "Limit")
            OrderKind.TRAILING_STOP, OrderKind.TRAILING_STOP_LIMIT -> when {
                (l.trailAmount == null) == (l.trailPercent == null) -> "Give either a trailing amount or a trailing percentage."
                l.trailAmount != null && (l.trailAmount <= 0 || l.trailAmount > MAX_PRICE) -> "Trailing amount must be positive and at most ${'$'}1,000,000."
                l.trailPercent != null && (l.trailPercent <= 0.0 || l.trailPercent > 50.0) -> "Trailing percentage must be between 0 and 50."
                l.kind == OrderKind.TRAILING_STOP_LIMIT && (l.limitOffset == null || l.limitOffset < 0 || l.limitOffset > MAX_PRICE) ->
                    "Give a limit offset (up to ${'$'}1,000,000) for a trailing stop-limit."
                else -> null
            }
            OrderKind.TWAP, OrderKind.VWAP -> when {
                l.durationTicks == null || l.durationTicks !in 12..7200 -> "Algo duration must be between 1 minute and 10 hours."
                l.tif == TimeInForce.IOC -> "Algo orders can't be immediate-or-cancel."
                else -> null
            }
        }?.let { return it } ?: if (l.tif == TimeInForce.IOC && l.kind.isStop) "Stop orders can't be immediate-or-cancel." else null
    }
}

/**
 * One player order (a leg of a request). Its id doubles as the book order id when it
 * rests, so fills route straight back to it.
 */
class Leg(
    val id: Long,
    val groupId: Long,
    val accountId: Long,
    val ticker: String,
    val spec: LegSpec,
    val structure: Structure,
    /** Parent leg that must fill before this activates (OTO / bracket exits). */
    val parentId: Long?,
    /** Legs sharing an OCO group cancel each other on the first fill. */
    val ocoGroup: Long?,
    val createdDay: Int,
    /** Forced liquidation (closing only; skips buying-power checks). */
    val liquidation: Boolean = false,
) {
    var quantity: Long = spec.quantity
    var status: LegStatus = LegStatus.QUEUED
    var filled: Long = 0
    var notional: Long = 0
    var executeDay: Int = 0
    /** Tick index to execute at; -1 = the session's opening auction. */
    var executeTick: Int = 0
    /** Stops: the stop price in force (fixed, or trailing). */
    var activeStop: Cents? = spec.stop
    /** Trailing stops: best price seen since arming. */
    var trailRef: Cents? = null
    /** After a stop triggers: execute as MARKET or LIMIT at [triggeredLimit]. */
    var triggered = false
    var triggeredLimit: Cents? = null
    var algoTicksDone: Int = 0
    var reason: String? = null

    val remaining: Long get() = quantity - filled
    val averagePrice: Double? get() = if (filled == 0L) null else notional.toDouble() / filled

    /** The book-level behaviour right now: a market order or a limit at a price. */
    val executableLimit: Cents? get() = when {
        spec.kind == OrderKind.LIMIT -> spec.limit
        triggered -> triggeredLimit
        else -> null
    }

    fun isDue(day: Int, tick: Int) = day > executeDay || (day == executeDay && tick >= executeTick)
}
