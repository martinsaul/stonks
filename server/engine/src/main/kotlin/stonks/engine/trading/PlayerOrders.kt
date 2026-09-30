package stonks.engine.trading

import stonks.engine.book.Fill
import stonks.engine.book.Order
import stonks.engine.book.OrderBook
import stonks.engine.book.OrderType
import stonks.engine.book.TimeInForce
import stonks.engine.core.Cents
import stonks.engine.core.Side
import stonks.engine.core.TraderId
import stonks.engine.core.TraderKind
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.readNullableLong
import stonks.engine.snapshot.writeList
import stonks.engine.snapshot.writeNullableLong
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.TreeMap
import kotlin.math.ceil
import kotlin.math.roundToLong

/** What player orders need from the rest of the market (accounts, other prices). */
interface PlayerGateway {
    /**
     * Checks buying power (and borrow availability) for executing [qty] of [leg] at
     * about [price]. On approval, [reserve] holds buying power while it rests.
     * Returns null when approved, else a reason.
     */
    fun authorize(leg: Leg, qty: Long, price: Cents, reserve: Boolean): String?

    /** Books a fill; returns (commission, realized). */
    fun settle(leg: Leg, qty: Long, price: Cents): Pair<Cents, Cents>

    /** Re-sizes or releases the leg's buying-power reservation. */
    fun reserve(leg: Leg, qty: Long, price: Cents)
    fun release(leg: Leg)

    /** (fame, shame), each 0..1: how the crowd reacts to this player. */
    fun reputation(accountId: Long): Pair<Double, Double> = 0.0 to 0.0
}

/**
 * All player orders for one ticker: scheduling, stop triggers, trailing, algo slicing,
 * OCO/OTO/bracket links and time-in-force. Executable orders go through the ticker's
 * [book]; fills come back through [onFills].
 */
class PlayerOrders(private val ticker: String, private val book: OrderBook) {
    val legs = TreeMap<Long, Leg>()
    val events = ArrayList<EngineEvent>()
    private var fillSeq = 0L
    lateinit var gateway: PlayerGateway

    /** Last completed tick's traded volume and its moving average (for VWAP). */
    private var lastTickVolume = 0L
    private var avgTickVolume = 0.0

    /** The session (game day) and tick currently being processed. */
    private var day = 0
    private var tick = 0

    /** Sets the current session/tick (fills and child activations are stamped with it). */
    fun at(day: Int, tick: Int) {
        this.day = day
        this.tick = tick
    }

    fun add(leg: Leg) {
        legs[leg.id] = leg
        leg.status = if (leg.parentId != null) LegStatus.WAITING else LegStatus.QUEUED
        update(leg)
    }

    /** Orders taking part in the opening auction (due at tick -1, market/limit only). */
    fun auctionOrders(day: Int, prices: (Side) -> Cents?): List<Order> {
        at(day, -1)
        val out = ArrayList<Order>()
        for (leg in dueQueued(day, -1)) {
            if (leg.spec.kind != OrderKind.MARKET && leg.spec.kind != OrderKind.LIMIT) continue
            val px = leg.executableLimit ?: prices(leg.spec.side) ?: continue
            guarded(leg) {
                if (approve(leg, leg.remaining, px, reserve = false)) out += bookOrder(leg, leg.remaining)
            }
        }
        return out
    }

    /**
     * After the auction: unfilled market orders are cancelled; limit orders continue into
     * the continuous session at tick 0 (resting in the book, with buying power held).
     */
    fun afterAuction(auctionOrders: List<Order>) {
        for (o in auctionOrders) {
            val leg = legs[o.id] ?: continue
            if (leg.status.done) continue
            if (leg.executableLimit == null) {
                finish(leg, LegStatus.CANCELLED, "Not matched in the opening auction")
            } else {
                leg.executeDay = day
                leg.executeTick = 0
            }
        }
    }

    /**
     * Executes everything due at [tick]: queued market/limit orders, newly triggered
     * stops, algo slices. Liquidations go first, then arrival order.
     */
    fun execute(day: Int, tick: Int, fills: MutableList<Fill>) {
        at(day, tick)
        val due = dueQueued(day, tick).sortedWith(compareBy({ !it.liquidation }, { it.executeDay }, { it.executeTick }, { it.id }))
        for (leg in due) {
            if (leg.status != LegStatus.QUEUED) continue
            guarded(leg) {
                when {
                    leg.spec.kind.isStop && !leg.triggered -> {
                        leg.status = LegStatus.ARMED
                        update(leg)
                    }
                    leg.spec.kind.isAlgo -> {
                        leg.status = LegStatus.WORKING
                        update(leg)
                    }
                    else -> submit(leg, leg.remaining, fills)
                }
            }
        }
        for (leg in legs.values.filter { it.status == LegStatus.WORKING && it.spec.kind.isAlgo }) {
            guarded(leg) {
                val slice = algoSlice(leg)
                leg.algoTicksDone++
                if (slice > 0) submit(leg, slice, fills, algo = true)
            }
        }
    }

    /**
     * Safety net: a failure while processing one order (e.g. arithmetic overflow)
     * rejects that order and nothing else. The simulation must never throw because of
     * player input: the input is already in the replay log, so an exception here would
     * repeat on every restart.
     */
    private inline fun guarded(leg: Leg, block: () -> Unit) {
        try {
            block()
        } catch (e: ArithmeticException) {
            runCatching { finish(leg, LegStatus.REJECTED, "Order exceeds the market's numeric limits.") }
        } catch (e: RuntimeException) {
            runCatching { finish(leg, LegStatus.REJECTED, "Order could not be processed.") }
        }
    }

    /** Checks stops and trailing stops against the tick's last price; triggered ones run next tick. */
    fun afterTick(day: Int, tick: Int, last: Cents, tickVolume: Long) {
        at(day, tick)
        lastTickVolume = tickVolume
        avgTickVolume = if (avgTickVolume == 0.0) tickVolume.toDouble() else avgTickVolume * 0.98 + tickVolume * 0.02
        for (leg in legs.values) {
            if (leg.status != LegStatus.ARMED) continue
            val side = leg.spec.side
            if (leg.spec.kind == OrderKind.TRAILING_STOP || leg.spec.kind == OrderKind.TRAILING_STOP_LIMIT) {
                val ref = leg.trailRef
                leg.trailRef = if (ref == null) last else if (side == Side.SELL) maxOf(ref, last) else minOf(ref, last)
                val r = leg.trailRef!!
                val distance = leg.spec.trailAmount ?: ceil(r * leg.spec.trailPercent!! / 100).toLong()
                val before = leg.activeStop
                leg.activeStop = if (side == Side.SELL) r - distance else r + distance
                if (leg.activeStop != before) update(leg)
            }
            val stop = leg.activeStop ?: continue
            val hit = if (side == Side.SELL) last <= stop else last >= stop
            if (!hit) continue
            leg.triggered = true
            leg.triggeredLimit = when (leg.spec.kind) {
                OrderKind.STOP_LIMIT -> leg.spec.limit
                OrderKind.TRAILING_STOP_LIMIT -> if (side == Side.SELL) stop - leg.spec.limitOffset!! else stop + leg.spec.limitOffset!!
                else -> null
            }
            leg.status = LegStatus.QUEUED
            leg.executeDay = day
            leg.executeTick = tick + 1
            update(leg, "Triggered at ${Ledger.money(last)}")
        }
    }

    /** Routes fills involving player orders back to their legs. */
    fun onFills(fills: List<Fill>) {
        for (f in fills) {
            if (f.takerTrader.kind == TraderKind.PLAYER) fill(f.takerOrderId, f.quantity, f.price, maker = false)
            if (f.makerTrader.kind == TraderKind.PLAYER) fill(f.makerOrderId, f.quantity, f.price, maker = true)
        }
    }

    fun cancel(id: Long, accountId: Long, reason: String = "Cancelled"): Boolean {
        val targets = legs.values.filter { (it.id == id || it.groupId == id) && it.accountId == accountId && !it.status.done }
        targets.forEach { finish(it, LegStatus.CANCELLED, reason) }
        return targets.isNotEmpty()
    }

    fun cancelAll(accountId: Long, reason: String) {
        legs.values.filter { it.accountId == accountId && !it.status.done && !it.liquidation }.forEach { finish(it, LegStatus.CANCELLED, reason) }
    }

    /**
     * Session end. DAY orders that were live this session expire (orders already
     * scheduled for a later session, and children still waiting on a live parent,
     * carry over). GTC orders expire after [GTC_DAYS] game days.
     */
    /** Cancels everything for [accountId], liquidations included (account wiped). */
    fun cancelEverything(accountId: Long, reason: String) {
        legs.values.filter { it.accountId == accountId && !it.status.done }.forEach { finish(it, LegStatus.CANCELLED, reason) }
    }

    fun endSession(day: Int) {
        at(day, Int.MAX_VALUE - 1)
        for (leg in legs.values.toList()) {
            if (leg.status.done || leg.status == LegStatus.WAITING) continue
            val expire = when (leg.spec.tif) {
                TimeInForce.GTC -> day - leg.createdDay >= GTC_DAYS
                else -> leg.executeDay <= day
            }
            if (expire) finish(leg, LegStatus.EXPIRED, "Expired")
        }
        legs.values.removeIf { it.status.done }
    }

    fun openQuantity(accountId: Long, side: Side, except: Long): Long =
        legs.values.filter { it.accountId == accountId && it.spec.side == side && it.id != except && it.status == LegStatus.WORKING }.sumOf { it.remaining }

    fun hasPendingLiquidation(accountId: Long) = legs.values.any { it.accountId == accountId && it.liquidation && !it.status.done }

    // ---------------------------------------------------------------------------------

    private fun dueQueued(day: Int, tick: Int) = legs.values.filter { it.status == LegStatus.QUEUED && it.isDue(day, tick) }

    private fun approve(leg: Leg, qty: Long, price: Cents, reserve: Boolean): Boolean {
        if (leg.liquidation) return true
        val reason = gateway.authorize(leg, qty, price, reserve) ?: return true
        finish(leg, LegStatus.REJECTED, reason)
        return false
    }

    private fun submit(leg: Leg, qty: Long, fills: MutableList<Fill>, algo: Boolean = false) {
        val limit = if (algo) null else leg.executableLimit
        val estimate = limit ?: preview(leg.spec.side, qty) ?: run {
            if (!algo) finish(leg, LegStatus.CANCELLED, "No liquidity")
            return
        }
        val rests = limit != null && leg.spec.tif != TimeInForce.IOC
        if (!approve(leg, qty, estimate, reserve = rests)) return
        val order = bookOrder(leg, qty, market = limit == null)
        val f = book.submit(order)
        fills += f
        onFills(f) // settle immediately so later orders see the new balances
        if (leg.status.done) return
        when {
            algo -> if (leg.remaining == 0L) finish(leg, LegStatus.FILLED)
                else if (leg.algoTicksDone >= leg.spec.durationTicks!!) finish(leg, LegStatus.CANCELLED, "Algo window ended")
            rests && order.remaining > 0 -> {
                leg.status = LegStatus.WORKING
                update(leg)
            }
            leg.filled > 0 -> finish(leg, LegStatus.CANCELLED, "Partially filled; no more liquidity at an acceptable price")
            else -> finish(leg, LegStatus.CANCELLED, "No liquidity at an acceptable price")
        }
    }

    private fun bookOrder(leg: Leg, qty: Long, market: Boolean = leg.executableLimit == null): Order =
        Order(
            leg.id, TraderId.player(leg.accountId), leg.spec.side,
            if (market) OrderType.MARKET else OrderType.LIMIT, qty,
            if (market) null else leg.executableLimit,
            if (leg.spec.tif == TimeInForce.IOC || market) TimeInForce.IOC else TimeInForce.GTC,
        )

    /** Average price to fill [qty] now, or null if the book is empty on that side. */
    private fun preview(side: Side, qty: Long): Cents? {
        val levels = book.depth(side.opposite, 50)
        if (levels.isEmpty()) return null
        var left = qty
        var cost = 0L
        var worst = levels.first().price
        for (l in levels) {
            val q = minOf(left, l.quantity)
            cost = Math.addExact(cost, Math.multiplyExact(q, l.price))
            left -= q
            worst = l.price
            if (left == 0L) break
        }
        // Unfillable remainder is priced at the worst visible level.
        cost = Math.addExact(cost, Math.multiplyExact(left, worst))
        return (cost.toDouble() / qty).roundToLong().coerceAtLeast(1)
    }

    private fun algoSlice(leg: Leg): Long {
        val total = leg.spec.durationTicks!!
        val ticksLeft = maxOf(1, total - leg.algoTicksDone)
        if (ticksLeft == 1) return leg.remaining
        val even = leg.remaining.toDouble() / ticksLeft
        val weight = if (leg.spec.kind == OrderKind.VWAP && avgTickVolume > 0) (lastTickVolume / avgTickVolume).coerceIn(0.25, 4.0) else 1.0
        return (even * weight).roundToLong().coerceIn(0, leg.remaining)
    }

    private fun fill(id: Long, qty: Long, price: Cents, maker: Boolean) {
        val leg = legs[id] ?: return
        guarded(leg) { settleFill(leg, qty, price, maker) }
    }

    private fun settleFill(leg: Leg, qty: Long, price: Cents, maker: Boolean) {
        val (commission, realized) = gateway.settle(leg, qty, price)
        leg.filled += qty
        leg.notional = Math.addExact(leg.notional, Math.multiplyExact(qty, price))
        events += FillEvent("$ticker-${++fillSeq}", leg.id, leg.accountId, ticker, leg.spec.side, qty, price, commission, realized, maker, leg.liquidation, day, tick)

        // OCO: the first fill cancels the siblings.
        leg.ocoGroup?.let { g ->
            legs.values.filter { it.ocoGroup == g && it.id != leg.id && !it.status.done }.forEach { finish(it, LegStatus.CANCELLED, "Cancelled by OCO") }
        }
        if (leg.remaining == 0L) {
            finish(leg, LegStatus.FILLED)
        } else {
            if (leg.status == LegStatus.WORKING && !leg.spec.kind.isAlgo) leg.executableLimit?.let { gateway.reserve(leg, leg.remaining, it) }
            update(leg)
        }
    }

    private fun finish(leg: Leg, status: LegStatus, reason: String? = null) {
        if (leg.status.done) return
        book.cancel(leg.id)
        gateway.release(leg)
        leg.status = status
        leg.reason = reason
        update(leg)
        // Children of a parent that ended with some fill activate for the filled quantity.
        for (child in legs.values.filter { it.parentId == leg.id && it.status == LegStatus.WAITING }) {
            if (leg.filled > 0) {
                if (leg.structure == Structure.BRACKET) child.quantity = leg.filled
                child.status = LegStatus.QUEUED
                child.executeDay = day
                child.executeTick = tick + 1
                update(child)
            } else {
                finish(child, LegStatus.CANCELLED, "Parent order did not fill")
            }
        }
    }

    private fun update(leg: Leg, reason: String? = leg.reason) {
        events += OrderUpdate(
            leg.id, leg.groupId, leg.accountId, ticker, leg.spec.side, leg.spec.kind, leg.quantity, leg.status,
            leg.filled, leg.averagePrice, leg.executableLimit ?: leg.spec.limit, leg.activeStop, reason, leg.liquidation,
        )
    }

    fun drainEvents(): List<EngineEvent> = ArrayList(events).also { events.clear() }

    fun writeTo(out: DataOutputStream) {
        out.writeLong(fillSeq)
        out.writeDouble(avgTickVolume)
        out.writeLong(lastTickVolume)
        out.writeList(legs.values.toList()) { writeLeg(it) }
    }

    fun readFrom(input: DataInputStream) {
        fillSeq = input.readLong()
        avgTickVolume = input.readDouble()
        lastTickVolume = input.readLong()
        legs.clear()
        input.readList { readLeg() }.forEach { legs[it.id] = it }
    }

    private fun DataOutputStream.writeLeg(l: Leg) {
        writeLong(l.id); writeLong(l.groupId); writeLong(l.accountId); writeUTF(l.spec.kind.name)
        writeUTF(l.spec.side.name); writeLong(l.spec.quantity); writeNullableLong(l.spec.limit); writeNullableLong(l.spec.stop)
        writeNullableLong(l.spec.trailAmount); writeBoolean(l.spec.trailPercent != null); writeDouble(l.spec.trailPercent ?: 0.0)
        writeNullableLong(l.spec.limitOffset); writeUTF(l.spec.tif.name); writeInt(l.spec.durationTicks ?: -1)
        writeUTF(l.structure.name); writeNullableLong(l.parentId); writeNullableLong(l.ocoGroup); writeInt(l.createdDay); writeBoolean(l.liquidation)
        writeLong(l.quantity); writeUTF(l.status.name); writeLong(l.filled); writeLong(l.notional); writeInt(l.executeDay); writeInt(l.executeTick)
        writeNullableLong(l.activeStop); writeNullableLong(l.trailRef); writeBoolean(l.triggered); writeNullableLong(l.triggeredLimit)
        writeInt(l.algoTicksDone); writeBoolean(l.reason != null); writeUTF(l.reason ?: "")
    }

    private fun DataInputStream.readLeg(): Leg {
        val id = readLong(); val group = readLong(); val account = readLong(); val kind = OrderKind.valueOf(readUTF())
        val side = Side.valueOf(readUTF()); val qty = readLong(); val limit = readNullableLong(); val stop = readNullableLong()
        val trailAmount = readNullableLong(); val hasPct = readBoolean(); val pct = readDouble()
        val offset = readNullableLong(); val tif = TimeInForce.valueOf(readUTF()); val duration = readInt()
        val spec = LegSpec(side, qty, kind, limit, stop, trailAmount, if (hasPct) pct else null, offset, tif, duration.takeIf { it >= 0 })
        val leg = Leg(id, group, account, ticker, spec, Structure.valueOf(readUTF()), readNullableLong(), readNullableLong(), readInt(), readBoolean())
        leg.quantity = readLong(); leg.status = LegStatus.valueOf(readUTF()); leg.filled = readLong(); leg.notional = readLong()
        leg.executeDay = readInt(); leg.executeTick = readInt()
        leg.activeStop = readNullableLong(); leg.trailRef = readNullableLong(); leg.triggered = readBoolean(); leg.triggeredLimit = readNullableLong()
        leg.algoTicksDone = readInt(); val hasReason = readBoolean(); val reason = readUTF(); leg.reason = if (hasReason) reason else null
        return leg
    }

    companion object {
        const val GTC_DAYS = 90
    }
}
