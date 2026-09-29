package stonks.engine.trading

import stonks.engine.core.Cents
import kotlin.math.ceil

/**
 * Commission plans (docs/DESIGN.md, "Commission plans" and "Margin & liquidation").
 * Unlocked by lifetime net realized profit; permanent except when net worth drops to
 * zero or the account resets.
 */
enum class Plan(
    /** Lifetime net realized profit (cents) that unlocks the plan. */
    val unlockAt: Cents,
    /** Equity required to open a long, as a fraction of its value (1 / max leverage). */
    val initialMargin: Double,
    /** Equity below which positions are liquidated, as a fraction of long value. */
    val maintenanceMargin: Double,
    /** Margin loan rate above the benchmark, annualized. */
    val marginSpread: Double,
    /** Per-share rate in hundredths of a cent (0 = flat fee plan). */
    private val perShare: Long,
    /** Minimum commission per order, cents. */
    private val minimum: Cents,
) {
    ROOKIE(0, 0.50, 0.30, 0.04, 0, 495),
    TRADER(25_000_00, 0.50, 0.25, 0.025, 100, 100),
    PRO(250_000_00, 0.25, 0.15, 0.015, 50, 100),
    WHALE(1_000_000_00, 1.0 / 6, 0.10, 0.01, 35, 35);

    /**
     * Commission for one fill of an order.
     *
     * @param firstFill whether the order had no earlier fills (flat fees and minimums apply once)
     * @param orderNotionalBefore notional (cents) already filled on this order
     */
    fun commission(qty: Long, price: Cents, firstFill: Boolean, orderNotionalBefore: Long): Cents {
        val notional = qty * price
        if (this == ROOKIE) return if (firstFill) minimum else 0
        var c = ceil(qty * perShare / 100.0).toLong()
        if (firstFill) c = maxOf(c, minimum)
        c = minOf(c, maxOf(1, notional / 100)) // capped at 1% of the fill's value
        if (this == WHALE) {
            // Exchange fee on large orders: 0.1% of the order's notional above $250k.
            val before = maxOf(0, orderNotionalBefore - LARGE_ORDER)
            val after = maxOf(0, orderNotionalBefore + notional - LARGE_ORDER)
            c += (after - before) / 1000
        }
        return c
    }

    companion object {
        const val SHORT_INITIAL = 0.50
        const val SHORT_MAINTENANCE = 0.30
        private const val LARGE_ORDER = 250_000_00L

        /** The best plan unlocked by [lifetimeProfit]. */
        fun forProfit(lifetimeProfit: Cents): Plan = entries.lastOrNull { lifetimeProfit >= it.unlockAt } ?: ROOKIE
    }
}
