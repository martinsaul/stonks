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
import stonks.engine.strategy.StrategyEngine
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
    private val rng = Rng(seed)
    val book = OrderBook()
    val strategy = StrategyEngine(company.initialStrategy, Rng(Rng.derive(seed, 1)))
    val candles = CandleAggregator(config.retention)

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
    private var tick = 0

    val isOpen: Boolean get() = session != null
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

    fun beginSession(session: Session, factors: SessionFactors, regime: MarketRegime, gapDays: Double): List<Fill> {
        check(this.session == null) { "session already open" }
        this.session = session
        this.factors = factors
        this.regime = regime
        tick = 0
        strategy.beginDay()
        candles.beginSession(session.open)

        // Overnight / break gap.
        val vm = session.kind.volatilityMultiplier * regime.volMultiplier
        val dtGap = gapDays / 252.0
        val common = company.marketBeta * config.marketFactorVol * sqrt(dtGap) * factors.gapMarket +
            company.sectorBeta * config.sectorFactorVol * sqrt(dtGap) * factors.gapSector.getValue(company.sector)
        reference *= exp(strategy.gapReturn(gapDays, vm, rng.gaussian()) + common * vm)

        return runOpeningAuction()
    }

    private fun runOpeningAuction(): List<Fill> {
        if (pending.isEmpty()) return emptyList()
        val queued = pending.toList()
        pending.clear()
        queued.forEachIndexed { i, o -> o.sequence = i.toLong() }

        computeLadder()
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
            val displacement = result.price - reference
            reference += displacement
            last = result.price
        }
        // Leftovers continue into the continuous book (which may still cross them).
        for (o in queued) {
            if (o.remaining > 0 && o.type == OrderType.LIMIT && o.tif != TimeInForce.IOC) {
                val rest = Order(o.id, o.trader, o.side, o.type, o.remaining, o.limitPrice, o.tif)
                book.submit(rest)
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

        // 2. Market makers re-quote around the reference.
        val fills = ArrayList<Fill>()
        fills += requote()
        val bidBefore = book.bestBid
        val askBefore = book.bestAsk

        // 3. Queued external orders, then background flow.
        for (o in pending) fills += book.submit(o)
        pending.clear()
        backgroundFlow(fills)

        // 4. Carry trading displacement into the reference permanently: the reference
        //    moves by however far the consumed side's touch moved. A sweep therefore
        //    re-centres the next quotes around where it ended, with no snap-back.
        if (fills.isNotEmpty()) {
            last = fills.last().price
            val askMove = touchMove(askBefore, book.bestAsk, Side.SELL, fills)
            val bidMove = touchMove(bidBefore, book.bestBid, Side.BUY, fills)
            reference = max(1.0, reference + askMove + bidMove)
        }

        val time = s.tickTime(tick)
        candles.onTick(time, s.open, fills, last)
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

    fun endSession() {
        checkNotNull(session) { "market closed" }
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
}
