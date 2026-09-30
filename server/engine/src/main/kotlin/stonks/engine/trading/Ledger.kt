package stonks.engine.trading

import stonks.engine.core.Cents
import stonks.engine.core.Side
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.writeList
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** A holding. [quantity] < 0 is a short. [costBasis] is the total cost of |quantity| shares. */
class Position(var quantity: Long = 0, var costBasis: Long = 0) {
    val averagePrice: Double get() = if (quantity == 0L) 0.0 else costBasis.toDouble() / abs(quantity)
}

class Account(val id: Long, var cash: Cents, var plan: Plan = Plan.ROOKIE) {
    val positions = TreeMap<String, Position>()
    /** Buying power held for open orders, by order (leg) id. */
    val reservations = HashMap<Long, Cents>()
    /** Lifetime realized profit net of commissions. Drives plan unlocks. */
    var lifetimeRealized: Cents = 0
    var totalCommissions: Cents = 0
    var totalInterest: Cents = 0

    fun position(ticker: String): Position = positions.getOrPut(ticker) { Position() }
    fun quantity(ticker: String): Long = positions[ticker]?.quantity ?: 0
}

/** Outcome of a buying-power check. */
sealed interface Authorization {
    data class Approved(val requirement: Cents) : Authorization
    data class Denied(val reason: String) : Authorization
}

data class AccountFigures(
    val cash: Cents,
    val longValue: Cents,
    val shortValue: Cents,
    val equity: Cents,
    val initialRequirement: Cents,
    val maintenanceRequirement: Cents,
    val reserved: Cents,
) {
    val buyingPowerEquity: Cents get() = equity - initialRequirement - reserved
    val marginDebt: Cents get() = maxOf(0, -cash)
}

/**
 * All player accounts: cash, positions, margin and fees. Owned by the simulation
 * thread; prices are passed in as `ticker -> last price`.
 *
 * Cash may go negative: that is a margin loan, charged interest daily. Shorts credit
 * their proceeds to cash; the short's market value is subtracted from equity.
 */
class Ledger {
    val accounts = HashMap<Long, Account>()
    /** Shares sold short by players, per ticker (for the borrow pool). */
    val shortInterest = HashMap<String, Long>()

    fun open(id: Long, cash: Cents): Account = accounts.getOrPut(id) { Account(id, cash) }
    fun account(id: Long): Account? = accounts[id]

    fun figures(a: Account, prices: Map<String, Cents>): AccountFigures {
        var longValue = 0L
        var shortValue = 0L
        var initial = 0.0
        var maintenance = 0.0
        for ((ticker, p) in a.positions) {
            if (p.quantity == 0L) continue
            val value = Math.multiplyExact(abs(p.quantity), prices[ticker] ?: 0)
            if (p.quantity > 0) {
                longValue = Math.addExact(longValue, value)
                initial += value * a.plan.initialMargin
                maintenance += value * a.plan.maintenanceMargin
            } else {
                shortValue = Math.addExact(shortValue, value)
                initial += value * Plan.SHORT_INITIAL
                maintenance += value * Plan.SHORT_MAINTENANCE
            }
        }
        return AccountFigures(
            cash = a.cash,
            longValue = longValue,
            shortValue = shortValue,
            equity = Math.subtractExact(Math.addExact(a.cash, longValue), shortValue),
            initialRequirement = ceil(initial).toLong(),
            maintenanceRequirement = ceil(maintenance).toLong(),
            reserved = a.reservations.values.sum(),
        )
    }

    /**
     * Initial margin an order would add: the part that opens or extends a position.
     * Closing quantity needs none. [openSells] / [openBuys] are other working orders
     * on the same side, which may already claim the closable quantity.
     */
    fun requirement(a: Account, ticker: String, side: Side, qty: Long, price: Cents, openSameSide: Long = 0): Cents {
        val held = a.quantity(ticker)
        val closable = when (side) {
            Side.SELL -> maxOf(0, held - openSameSide)
            Side.BUY -> maxOf(0, -held - openSameSide)
        }
        val opening = maxOf(0, qty - closable)
        val fraction = if (side == Side.BUY) a.plan.initialMargin else Plan.SHORT_INITIAL
        return ceil(Math.multiplyExact(opening, price) * fraction).toLong()
    }

    /** Checks that [requirement] plus [fees] fit in the account's buying power. */
    fun authorize(a: Account, prices: Map<String, Cents>, requirement: Cents, fees: Cents, excludeReservation: Long? = null): Authorization {
        val f = figures(a, prices)
        val available = f.equity - f.initialRequirement - (f.reserved - (excludeReservation?.let { a.reservations[it] } ?: 0))
        return if (requirement >= 0 && fees >= 0 && Math.addExact(requirement, fees) <= available) Authorization.Approved(requirement)
        else Authorization.Denied("Insufficient buying power (need ${money(requirement + fees)}, have ${money(maxOf(0, available))}).")
    }

    /** Shares still available to borrow for shorting [ticker]. */
    fun borrowAvailable(ticker: String, pool: Long): Long = maxOf(0, pool - (shortInterest[ticker] ?: 0))

    /**
     * Applies a fill. Returns the realized profit (before commission) of any closed
     * quantity.
     */
    fun applyFill(a: Account, ticker: String, side: Side, qty: Long, price: Cents, commission: Cents): Cents {
        require(qty > 0 && price > 0 && commission >= 0) { "invalid fill $qty @ $price" }
        // Overflow check up front, before any state changes.
        Math.addExact(Math.addExact(abs(a.cash), Math.multiplyExact(qty, price)), commission)
        val p = a.position(ticker)
        val shortBefore = maxOf(0, -p.quantity)
        var realized = 0L
        var remaining = qty
        if (side == Side.BUY) {
            a.cash = Math.subtractExact(a.cash, Math.multiplyExact(qty, price))
            if (p.quantity < 0) { // cover
                val c = minOf(remaining, -p.quantity)
                val basis = proportional(p.costBasis, c, -p.quantity)
                realized += basis - Math.multiplyExact(c, price)
                p.costBasis -= basis
                p.quantity += c
                remaining -= c
            }
            if (remaining > 0) {
                p.costBasis = Math.addExact(p.costBasis, Math.multiplyExact(remaining, price))
                p.quantity = Math.addExact(p.quantity, remaining)
            }
        } else {
            a.cash = Math.addExact(a.cash, Math.multiplyExact(qty, price))
            if (p.quantity > 0) { // sell long
                val c = minOf(remaining, p.quantity)
                val basis = proportional(p.costBasis, c, p.quantity)
                realized += Math.multiplyExact(c, price) - basis
                p.costBasis -= basis
                p.quantity -= c
                remaining -= c
            }
            if (remaining > 0) { // open or extend a short
                p.costBasis = Math.addExact(p.costBasis, Math.multiplyExact(remaining, price))
                p.quantity = Math.subtractExact(p.quantity, remaining)
            }
        }
        if (p.quantity == 0L) {
            p.costBasis = 0
            a.positions.remove(ticker)
        }
        val shortAfter = maxOf(0, -(a.positions[ticker]?.quantity ?: 0))
        if (shortAfter != shortBefore) shortInterest.merge(ticker, shortAfter - shortBefore, Long::plus)

        a.cash -= commission
        a.totalCommissions += commission
        a.lifetimeRealized += realized - commission
        val unlocked = Plan.forProfit(a.lifetimeRealized)
        if (unlocked.ordinal > a.plan.ordinal) a.plan = unlocked
        return realized
    }

    /**
     * Closes [a]'s whole position in [ticker] at [price] with no commission (delisting:
     * a cash-out, or 0 for bankruptcy). Returns the realized profit.
     */
    fun closeOut(a: Account, ticker: String, price: Cents): Cents {
        val p = a.positions[ticker] ?: return 0
        val value = Math.multiplyExact(p.quantity, price) // negative for shorts
        val realized = if (p.quantity > 0) value - p.costBasis else p.costBasis + value
        a.cash = Math.addExact(a.cash, value)
        a.positions.remove(ticker)
        a.lifetimeRealized += realized
        val unlocked = Plan.forProfit(a.lifetimeRealized)
        if (unlocked.ordinal > a.plan.ordinal) a.plan = unlocked
        return realized
    }

    /**
     * A split of [ratio] new shares per old share. Fractional shares left over (reverse
     * splits) are settled in cash at [newPrice]. Returns accountId to its new quantity.
     */
    fun split(ticker: String, ratio: Double, newPrice: Cents): Map<Long, Long> {
        val changed = HashMap<Long, Long>()
        for (a in accounts.values) {
            val p = a.positions[ticker] ?: continue
            val exact = abs(p.quantity) * ratio
            val whole = kotlin.math.floor(exact + 1e-9).toLong()
            val fraction = (exact - whole).coerceAtLeast(0.0)
            val inLieu = (fraction * newPrice).roundToLong()
            val basisOut = (p.costBasis * (fraction / exact)).roundToLong()
            val sign = if (p.quantity > 0) 1 else -1
            a.cash = Math.addExact(a.cash, sign * inLieu)
            a.lifetimeRealized += sign * (inLieu - basisOut)
            p.costBasis -= basisOut
            p.quantity = sign * whole
            if (whole == 0L) a.positions.remove(ticker)
            changed[a.id] = p.quantity
        }
        val short = accounts.values.sumOf { maxOf(0, -(it.positions[ticker]?.quantity ?: 0)) }
        if (short == 0L) shortInterest.remove(ticker) else shortInterest[ticker] = short
        return changed
    }

    /** Credits (longs) or charges (shorts) [amount] cents. Returns the cash moved. */
    fun payDividend(a: Account, amount: Cents): Cents {
        a.cash = Math.addExact(a.cash, amount)
        return amount
    }

    /**
     * Daily charges: margin interest on borrowed cash and borrow fees on shorts.
     * [borrowRate] gives each ticker's annualized borrow fee.
     */
    fun accrueDaily(prices: Map<String, Cents>, benchmarkRate: Double, borrowRate: (String) -> Double, daysPerYear: Double = 252.0) {
        for (a in accounts.values) {
            if (a.cash < 0) {
                val interest = (-a.cash * (benchmarkRate + a.plan.marginSpread) / daysPerYear).roundToLong()
                a.cash -= interest
                a.totalInterest += interest
            }
            for ((ticker, p) in a.positions) {
                if (p.quantity >= 0) continue
                val fee = (-p.quantity * (prices[ticker] ?: 0) * borrowRate(ticker) / daysPerYear).roundToLong()
                a.cash -= fee
                a.totalInterest += fee
            }
            // Plans are permanent unless net worth reaches zero.
            if (a.plan != Plan.ROOKIE && figures(a, prices).equity <= 0) a.plan = Plan.ROOKIE
        }
    }

    fun writeTo(out: DataOutputStream) {
        out.writeList(accounts.values.sortedBy { it.id }) { a ->
            writeLong(a.id); writeLong(a.cash); writeUTF(a.plan.name)
            writeLong(a.lifetimeRealized); writeLong(a.totalCommissions); writeLong(a.totalInterest)
            writeList(a.positions.entries.toList()) { (t, p) -> writeUTF(t); writeLong(p.quantity); writeLong(p.costBasis) }
            writeList(a.reservations.entries.sortedBy { it.key }) { (k, v) -> writeLong(k); writeLong(v) }
        }
    }

    fun readFrom(input: DataInputStream) {
        accounts.clear()
        shortInterest.clear()
        input.readList {
            val a = Account(readLong(), readLong(), Plan.valueOf(readUTF()))
            a.lifetimeRealized = readLong(); a.totalCommissions = readLong(); a.totalInterest = readLong()
            readList { a.positions[readUTF()] = Position(readLong(), readLong()) }
            readList { a.reservations[readLong()] = readLong() }
            a
        }.forEach { a ->
            accounts[a.id] = a
            a.positions.forEach { (t, p) -> if (p.quantity < 0) shortInterest.merge(t, -p.quantity, Long::plus) }
        }
    }

    companion object {
        private fun proportional(total: Long, part: Long, whole: Long): Long =
            if (part == whole) total else (total.toDouble() * part / whole).roundToLong()

        fun money(c: Cents): String = "$" + "%,.2f".format(c / 100.0)

        /** Annualized borrow fee from pool utilization (design: 0.5% → 10% → 100%+). */
        fun borrowRate(utilization: Double): Double = when {
            utilization < 0.5 -> 0.005
            utilization < 0.8 -> 0.005 + (utilization - 0.5) / 0.3 * 0.095
            else -> 0.10 + 0.90 * ((minOf(utilization, 1.2) - 0.8) / 0.2).let { it * it }
        }
    }
}
