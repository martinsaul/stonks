package stonks.engine.sim

import stonks.engine.book.Auction
import stonks.engine.book.Fill
import stonks.engine.book.Order
import stonks.engine.book.OrderBook
import stonks.engine.book.OrderType
import stonks.engine.book.TimeInForce
import stonks.engine.clock.Session
import stonks.engine.company.Company
import stonks.engine.core.Cents
import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.core.TraderId
import stonks.engine.core.toCentsCeil
import stonks.engine.core.toCentsFloor
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.readNullableLong
import stonks.engine.snapshot.writeList
import stonks.engine.snapshot.writeNullableLong
import stonks.engine.strategy.StrategyEngine
import stonks.engine.trading.PlayerOrders
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** What happened to one ticker during one tick. */
data class TickReport(
    val ticker: String,
    val tick: Int,
    val fills: List<Fill>,
    val last: Cents,
    val bid: Cents?,
    val ask: Cents?,
)

/**
 * Simulates one ticker: its strategy curve, market-maker ladder, background order flow
 * and the order book that players trade against.
 *
 * Price formation (docs/DESIGN.md, "Price model"): each tick the reference price moves
 * by the curve's return, market makers re-quote around it, then orders match. Any
 * displacement of the mid caused by trading is carried into the reference permanently.
 *
 * Random draws never depend on book state, so two runs with the same seed stay coupled
 * even when one of them receives extra (player) orders.
 */
class TickerSim(
    val company: Company,
    private val config: MarketConfig,
    seed: Long,
) {
    private var rng = Rng(seed)
    val book = OrderBook()
    val strategy = StrategyEngine(company.initialStrategy, Rng(Rng.derive(seed, 1)))
    val candles = CandleAggregator(company.ticker, config.retention)
    val playerOrders = PlayerOrders(company.ticker, book)

    /** Index of the current (or next) session: the game day. */
    var day = 0
        private set

    /** Reference price in cents (fractional; the book quotes whole cents). */
    var reference: Double = company.initialPrice.toDouble()
        private set
    var last: Cents = company.initialPrice
        private set

    private var nextNpcOrderId = -1L
    private val pending = ArrayList<Order>()
    private val bidPrices = LongArray(config.makerLevels)
    private val bidSizes = LongArray(config.makerLevels)
    private val askPrices = LongArray(config.makerLevels)
    private val askSizes = LongArray(config.makerLevels)
    private var bidLevels = 0

    private var session: Session? = null
    private var factors: SessionFactors? = null
    private var regime = MarketRegime.NEUTRAL
    /** Index of the next tick to simulate in the current session. */
    var tick = 0
        private set

    /** Close of the previous session; null before the first session. */
    var previousClose: Cents? = null
        private set
    var dayOpen: Cents? = null
        private set
    var dayHigh: Cents? = null
        private set
    var dayLow: Cents? = null
        private set
    var dayVolume: Long = 0
        private set

    val isOpen: Boolean get() = session != null

    /** Sets the game day (for snapshots that predate day tracking). */
    internal fun alignDay(day: Int) {
        this.day = day
    }
    private val regimeDrift: Double get() = regime.driftAdd * company.marketBeta
    val marketCap: Double get() = reference / 100.0 * company.sharesOutstanding

    /**
     * Queues an externally submitted (player) order. It is matched on the next tick, or
     * in the opening auction if the market is closed. Order ids must be positive.
     */
    fun submit(order: Order) {
        require(order.id > 0) { "external order ids must be positive" }
        pending += order
    }

    fun cancel(orderId: Long): Order? =
        book.cancel(orderId) ?: pending.firstOrNull { it.id == orderId }?.also { pending.remove(it) }

    fun beginSession(session: Session, factors: SessionFactors, regime: MarketRegime, gapDays: Double, day: Int = this.day): List<Fill> {
        check(this.session == null) { "session already open" }
        this.session = session
        this.day = day
        this.factors = factors
        this.regime = regime
        tick = 0
        dayOpen = null; dayHigh = null; dayLow = null; dayVolume = 0
        strategy.beginDay()
        candles.beginSession(session.open)

        // Overnight / break gap.
        val vm = session.kind.volatilityMultiplier * regime.volMultiplier
        val dtGap = gapDays / 252.0
        val common = company.marketBeta * config.marketFactorVol * sqrt(dtGap) * factors.gapMarket +
            company.sectorBeta * config.sectorFactorVol * sqrt(dtGap) * factors.gapSector.getValue(company.sector)
        reference *= exp(strategy.gapReturn(gapDays, vm, rng.gaussian()) + common * vm)

        // Without an auction the market opens at the gapped level; bars before the first
        // trade must not carry yesterday's close (that would draw a false opening wick).
        val fills = runOpeningAuction()
        if (fills.isEmpty()) last = max(1L, reference.roundToLong())
        return fills
    }

    private fun runOpeningAuction(): List<Fill> {
        computeLadder()
        val players = playerOrders.auctionOrders(day) { side ->
            if (side == Side.BUY) askPrices[0] else bidPrices.takeIf { bidLevels > 0 }?.get(0)
        }
        if (pending.isEmpty() && players.isEmpty()) return emptyList()
        val queued = pending.toList() + players
        pending.clear()
        queued.forEachIndexed { i, o -> o.sequence = i.toLong() }

        val makerOrders = ArrayList<Order>(bidLevels + config.makerLevels)
        for (i in 0 until bidLevels) {
            makerOrders += Order(nextNpcOrderId--, TraderId.MARKET_MAKER, Side.BUY, OrderType.LIMIT, bidSizes[i], bidPrices[i], TimeInForce.GTC)
        }
        for (i in 0 until config.makerLevels) {
            makerOrders += Order(nextNpcOrderId--, TraderId.MARKET_MAKER, Side.SELL, OrderType.LIMIT, askSizes[i], askPrices[i], TimeInForce.GTC)
        }
        val result = Auction.uncross(queued + makerOrders, reference.roundToLong())
        val fills = result?.fills ?: emptyList()
        if (result != null) {
            reference = result.price.toDouble()
            last = result.price
            recordStats(fills)
        }
        playerOrders.onFills(fills)
        playerOrders.afterAuction(players)
        // Raw leftovers (not managed as player legs) continue into the continuous book.
        val managed = players.toHashSet()
        for (o in queued) {
            if (o in managed) continue
            if (o.remaining > 0 && o.type == OrderType.LIMIT && o.tif != TimeInForce.IOC) {
                book.submit(Order(o.id, o.trader, o.side, o.type, o.remaining, o.limitPrice, o.tif))
            }
        }
        return fills
    }

    /** Advances one 5-second tick. */
    fun step(): TickReport {
        val s = checkNotNull(session) { "market closed" }
        val f = checkNotNull(factors)
        val ticks = s.ticks
        val vm = s.kind.volatilityMultiplier * regime.volMultiplier
        val dt = 1.0 / (252.0 * ticks)

        // 1. Curve return.
        val idio = strategy.tickReturn(tick, ticks, vm, regimeDrift, rng.gaussian())
        val common = (company.marketBeta * config.marketFactorVol * f.market[tick] +
            company.sectorBeta * config.sectorFactorVol * f.sector.getValue(company.sector)[tick]) * sqrt(dt) * vm
        reference *= exp(idio + common)

        // 2. Market makers re-quote around the reference (possibly filling resting player orders).
        playerOrders.at(day, tick)
        val fills = ArrayList<Fill>()
        val quoteFills = requote()
        fills += quoteFills
        playerOrders.onFills(quoteFills)
        val bidBefore = book.bestBid
        val askBefore = book.bestAsk

        // 3. Player orders due this tick (settled as they fill), raw orders, then background flow.
        playerOrders.execute(day, tick, fills)
        for (o in pending) fills += book.submit(o)
        pending.clear()
        val bgStart = fills.size
        backgroundFlow(fills)
        playerOrders.onFills(fills.subList(bgStart, fills.size))

        // 4. Carry trading displacement into the reference permanently: the reference
        //    moves by however far the consumed side's touch moved. A sweep therefore
        //    re-centres the next quotes around where it ended, with no snap-back.
        if (fills.isNotEmpty()) {
            last = fills.last().price
            recordStats(fills)
            val askMove = touchMove(askBefore, book.bestAsk, Side.SELL, fills)
            val bidMove = touchMove(bidBefore, book.bestBid, Side.BUY, fills)
            reference = max(1.0, reference + askMove + bidMove)
        }

        val time = s.tickTime(tick)
        candles.onTick(time, s.open, fills, last)
        playerOrders.afterTick(day, tick, last, fills.sumOf { it.quantity })
        val report = TickReport(company.ticker, tick, fills, last, book.bestBid, book.bestAsk)
        tick++
        return report
    }

    /**
     * How far one side's best price moved because it was consumed. An emptied side is
     * treated as having moved to the furthest price traded against it.
     */
    private fun touchMove(before: Cents?, after: Cents?, makerSide: Side, fills: List<Fill>): Double {
        if (before == null) return 0.0
        val takerSide = makerSide.opposite
        val takenFrom = fills.filter { it.takerSide == takerSide && it.takerTrader != TraderId.MARKET_MAKER }
        if (takenFrom.isEmpty()) return 0.0
        val end = after ?: if (makerSide == Side.SELL) takenFrom.maxOf { it.price } else takenFrom.minOf { it.price }
        val move = (end - before).toDouble()
        // Only count moves away from the book (consumption), not refills toward it.
        return if (makerSide == Side.SELL) max(0.0, move) else minOf(0.0, move)
    }

    private fun recordStats(fills: List<Fill>) {
        for (f in fills) {
            if (dayOpen == null) dayOpen = f.price
            dayHigh = maxOf(dayHigh ?: f.price, f.price)
            dayLow = minOf(dayLow ?: f.price, f.price)
            dayVolume += f.quantity
        }
    }

    fun endSession() {
        checkNotNull(session) { "market closed" }
        playerOrders.endSession(day)
        day++
        previousClose = last
        book.clearMakerLadders()
        book.cancelWhere { it.tif == TimeInForce.DAY }
        candles.endSession()
        strategy.endDay()
        session = null
        factors = null
    }

    /** Replaces the maker ladder. Returns fills against crossed resting orders. */
    private fun requote(): List<Fill> {
        computeLadder()
        val a = book.setMakerLadder(Side.SELL, askPrices, askSizes, config.makerLevels)
        val b = book.setMakerLadder(Side.BUY, bidPrices, bidSizes, bidLevels)
        return if (a.isEmpty()) b else if (b.isEmpty()) a else a + b
    }

    /** Fills the ladder arrays around the current reference price. */
    private fun computeLadder() {
        val sigma = strategy.current.volAt(0.0)
        val halfSpread = reference * (config.baseHalfSpreadBps + config.volHalfSpreadBps * sigma) / 10_000.0
        val step = max(1.0, halfSpread)
        val levelNotional = marketCap * config.liquidityFraction
        var lastBid = Long.MAX_VALUE
        var lastAsk = Long.MIN_VALUE
        bidLevels = 0
        for (i in 0 until config.makerLevels) {
            val scale = 1 + config.levelGrowth * i
            val bidPx = minOf((reference - halfSpread - i * step).toCentsFloor(), lastBid - 1)
            if (bidPx >= 1) {
                bidPrices[bidLevels] = bidPx
                bidSizes[bidLevels] = max(1L, (levelNotional * scale / (bidPx / 100.0)).toLong())
                bidLevels++
                lastBid = bidPx
            }
            val askPx = maxOf((reference + halfSpread + i * step).toCentsCeil(), lastAsk + 1)
            askPrices[i] = askPx
            askSizes[i] = max(1L, (levelNotional * scale / (askPx / 100.0)).toLong())
            lastAsk = askPx
        }
    }

    private fun backgroundFlow(into: MutableList<Fill>) {
        val vm = session!!.kind.volatilityMultiplier
        val n = rng.poisson(config.backgroundRate * vm)
        val medianNotional = marketCap * config.liquidityFraction * config.backgroundSizeFraction
        repeat(n) {
            val side = if (rng.chance(0.5)) Side.BUY else Side.SELL
            val notional = rng.logNormal(medianNotional, config.backgroundSizeSigma)
            val qty = max(1L, (notional / (reference / 100.0)).toLong())
            into += book.submit(Order(nextNpcOrderId--, TraderId.BACKGROUND, side, OrderType.MARKET, qty))
        }
    }

    /** Writes this ticker's state. Only valid while the market is closed. */
    fun writeTo(out: DataOutputStream) {
        check(session == null) { "snapshots are taken while the market is closed" }
        company.writeTo(out)
        out.writeLong(rng.state)
        strategy.writeTo(out)
        out.writeDouble(reference)
        out.writeLong(last)
        out.writeNullableLong(previousClose)
        out.writeLong(nextNpcOrderId)
        out.writeList(book.restingOrders().sortedBy { it.sequence }) { writeOrder(it) }
        out.writeList(pending) { writeOrder(it) }
        out.writeInt(day)
        playerOrders.writeTo(out)
    }

    internal fun restore(input: DataInputStream, version: Int) {
        rng = Rng.restore(input.readLong())
        strategy.restore(input)
        reference = input.readDouble()
        last = input.readLong()
        previousClose = input.readNullableLong()
        nextNpcOrderId = input.readLong()
        input.readList { readOrder() }.forEach { book.restoreResting(it) }
        pending.clear()
        pending += input.readList { readOrder() }
        if (version >= 2) {
            day = input.readInt()
            playerOrders.readFrom(input)
        }
    }

    companion object {
        /** Reads a ticker written by [writeTo]. */
        fun readFrom(input: DataInputStream, config: MarketConfig, version: Int): TickerSim =
            TickerSim(Company.readFrom(input), config, seed = 0).also { it.restore(input, version) }

        private fun DataOutputStream.writeOrder(o: Order) {
            writeLong(o.id)
            writeUTF(o.trader.kind.name)
            writeLong(o.trader.id)
            writeUTF(o.side.name)
            writeUTF(o.type.name)
            writeLong(o.quantity)
            writeLong(o.remaining)
            writeNullableLong(o.limitPrice)
            writeUTF(o.tif.name)
            writeLong(o.sequence)
        }

        private fun DataInputStream.readOrder(): Order {
            val id = readLong()
            val trader = TraderId(stonks.engine.core.TraderKind.valueOf(readUTF()), readLong())
            val side = Side.valueOf(readUTF())
            val type = OrderType.valueOf(readUTF())
            val quantity = readLong()
            val remaining = readLong()
            val limit = readNullableLong()
            val tif = TimeInForce.valueOf(readUTF())
            return Order(id, trader, side, type, quantity, limit, tif).also {
                it.remaining = remaining
                it.sequence = readLong()
            }
        }
    }
}
