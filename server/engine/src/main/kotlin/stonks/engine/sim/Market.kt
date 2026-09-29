package stonks.engine.sim

import stonks.engine.book.Fill
import stonks.engine.clock.Session
import stonks.engine.clock.SessionKind
import stonks.engine.company.Company
import stonks.engine.core.Rng
import stonks.engine.snapshot.Snapshot
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.readNullableLong
import stonks.engine.snapshot.writeList
import stonks.engine.snapshot.writeNullableLong
import stonks.engine.book.TimeInForce
import stonks.engine.core.Cents
import stonks.engine.core.Side
import stonks.engine.trading.AccountEvent
import stonks.engine.trading.Authorization
import stonks.engine.trading.EngineEvent
import stonks.engine.trading.Leg
import stonks.engine.trading.LegSpec
import stonks.engine.trading.LegStatus
import stonks.engine.trading.Ledger
import stonks.engine.trading.OrderKind
import stonks.engine.trading.OrderRequest
import stonks.engine.trading.Plan
import stonks.engine.trading.PlayerGateway
import stonks.engine.trading.Structure
import kotlin.math.abs
import kotlin.math.ceil
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * The whole regional market: every ticker plus the shared factors and regime.
 *
 * Live mode calls [beginSession], then [step] every 5 seconds, then [endSession].
 * Backfill uses [runSession], which simulates tickers in parallel; results are
 * identical either way because tickers only interact through pre-generated factors.
 *
 * While closed, the full state can be saved with [writeSnapshot] and restored with
 * [readSnapshot]; a restored market continues exactly as the original would have.
 */
class Market private constructor(
    tickerList: List<TickerSim>,
    val config: MarketConfig,
    val seed: Long,
    private var marketRng: Rng,
) {
    constructor(companies: List<Company>, config: MarketConfig = MarketConfig(), seed: Long) : this(
        companies.withIndex().map { (i, c) -> TickerSim(c, config, Rng.derive(seed, 1000L + i)) },
        config,
        seed,
        Rng(Rng.derive(seed, 0)),
    )

    val tickers: Map<String, TickerSim> = tickerList.associateBy { it.company.ticker }

    /** Player accounts. Mutated only on the simulation thread. */
    val ledger = Ledger()

    /** Last player input applied (for replay after a restart). */
    var lastInputSeq: Long = 0

    private var nextLiquidationId = LIQUIDATION_ID_BASE
    private val marketEvents = ArrayList<EngineEvent>()

    /** Last prices of every ticker, read live. */
    val prices: Map<String, Cents> = object : AbstractMap<String, Cents>() {
        override val entries: Set<Map.Entry<String, Cents>>
            get() = tickers.mapValues { it.value.last }.entries
        override fun get(key: String): Cents? = tickers[key]?.last
        override fun containsKey(key: String) = key in tickers
    }

    private val gateway = object : PlayerGateway {
        override fun authorize(leg: Leg, qty: Long, price: Cents, reserve: Boolean): String? {
            val a = ledger.account(leg.accountId) ?: return "No trading account."
            val t = tickers.getValue(leg.ticker)
            val openSame = t.playerOrders.openQuantity(a.id, leg.spec.side, except = leg.id)
            if (leg.spec.side == Side.SELL) {
                val shortQty = maxOf(0, qty - maxOf(0, a.quantity(leg.ticker) - openSame))
                if (shortQty > 0 && ledger.borrowAvailable(leg.ticker, borrowPool(t)) < shortQty) {
                    return "Not enough ${leg.ticker} shares available to borrow for a short sale."
                }
            }
            val req = ledger.requirement(a, leg.ticker, leg.spec.side, qty, price, openSame)
            val fees = a.plan.commission(qty, price, leg.filled == 0L, leg.notional)
            return when (val r = ledger.authorize(a, prices, req, fees, excludeReservation = leg.id)) {
                is Authorization.Denied -> r.reason
                is Authorization.Approved -> {
                    if (reserve) a.reservations[leg.id] = req else a.reservations.remove(leg.id)
                    null
                }
            }
        }

        override fun settle(leg: Leg, qty: Long, price: Cents): Pair<Cents, Cents> {
            val a = ledger.account(leg.accountId) ?: return 0L to 0L
            val planBefore = a.plan
            val commission = a.plan.commission(qty, price, leg.filled == 0L, leg.notional)
            val realized = ledger.applyFill(a, leg.ticker, leg.spec.side, qty, price, commission)
            if (a.plan != planBefore) marketEvents += AccountEvent(a.id, AccountEvent.Kind.PLAN_UPGRADED, a.plan.name)
            return commission to realized
        }

        override fun reserve(leg: Leg, qty: Long, price: Cents) {
            val a = ledger.account(leg.accountId) ?: return
            val openSame = tickers.getValue(leg.ticker).playerOrders.openQuantity(a.id, leg.spec.side, except = leg.id)
            a.reservations[leg.id] = ledger.requirement(a, leg.ticker, leg.spec.side, qty, price, openSame)
        }

        override fun release(leg: Leg) {
            ledger.account(leg.accountId)?.reservations?.remove(leg.id)
        }
    }

    init {
        tickers.values.forEach { it.playerOrders.gateway = gateway }
    }

    /** Game day of the open session, or of the next session while closed. */
    val day: Int get() = sessionsCompleted

    private fun borrowPool(t: TickerSim): Long = (t.company.sharesOutstanding * config.borrowPoolFraction).toLong()

    fun borrowRate(ticker: String): Double {
        val t = tickers[ticker] ?: return 0.0
        val pool = borrowPool(t).coerceAtLeast(1)
        return Ledger.borrowRate((ledger.shortInterest[ticker] ?: 0).toDouble() / pool)
    }

    // ---- Player inputs (applied on the simulation thread, logged by the app) --------

    fun openAccount(accountId: Long, cash: Cents): Boolean {
        if (ledger.account(accountId) != null) return false
        ledger.open(accountId, cash)
        marketEvents += AccountEvent(accountId, AccountEvent.Kind.OPENED, cash.toString())
        return true
    }

    /**
     * Places a request. Leg ids are `groupId * 4 + index`. It executes at
     * ([executeDay], [executeTick]); tick -1 is that session's opening auction.
     * Returns a rejection reason, or null.
     */
    fun placeOrder(groupId: Long, accountId: Long, request: OrderRequest, executeDay: Int, executeTick: Int): String? {
        request.validate()?.let { return it }
        val t = tickers[request.ticker] ?: return "Unknown ticker ${request.ticker}."
        ledger.account(accountId) ?: return "No trading account."
        val ids = request.legs.indices.map { groupId * 4 + it }
        request.legs.forEachIndexed { i, spec ->
            val parent = when (request.structure) {
                Structure.OTO, Structure.BRACKET -> if (i == 0) null else ids[0]
                else -> null
            }
            val oco = when (request.structure) {
                Structure.OCO -> groupId
                Structure.BRACKET -> if (i == 0) null else groupId
                else -> null
            }
            val leg = Leg(ids[i], groupId, accountId, request.ticker, spec, request.structure, parent, oco, executeDay)
            leg.executeDay = executeDay
            leg.executeTick = executeTick
            t.playerOrders.add(leg)
        }
        return null
    }

    fun cancelOrder(accountId: Long, id: Long): Boolean = tickers.values.any { it.playerOrders.cancel(id, accountId) }

    /** Events produced since the last call (fills, order updates, account changes). */
    fun drainEvents(): List<EngineEvent> {
        val out = ArrayList<EngineEvent>(marketEvents)
        marketEvents.clear()
        tickers.values.forEach { out += it.playerOrders.drainEvents() }
        return out
    }

    var regime: MarketRegime = MarketRegime.NEUTRAL
        private set

    /** Regime of every session run so far, oldest first. */
    val regimeHistory = mutableListOf<MarketRegime>()

    /** Number of sessions (game days) completed. */
    var sessionsCompleted: Int = 0
        private set

    /** Close of the last completed session. */
    var lastSessionClose: Instant? = null
        private set

    private var current: Session? = null

    val session: Session? get() = current

    /** Index of the next tick to simulate in the open session. */
    val tick: Int get() = tickers.values.first().tick

    /** Routes every ticker's closed candles to [sink]. */
    fun setCandleSink(sink: CandleSink?) {
        tickers.values.forEach { it.candles.sink = sink }
    }

    fun beginSession(session: Session): Map<String, List<Fill>> {
        check(current == null) { "session already open" }
        lastSessionClose?.let { require(!session.open.isBefore(it)) { "session $session precedes the last close $it" } }
        maybeChangeRegime()
        regimeHistory += regime
        val factors = SessionFactors.generate(marketRng, session.ticks)
        val gapDays = gapDays(session)
        current = session
        return tickers.mapValues { (_, t) -> t.beginSession(session, factors, regime, gapDays, day) }
    }

    fun step(): List<TickReport> {
        val reports = tickers.values.map { it.step() }
        checkMargins()
        return reports
    }

    /**
     * Steps every ticker until [targetTick] (exclusive). Runs tickers in parallel on
     * [executor] only when no player accounts exist; with players, ticks run in lockstep
     * so accounts see a consistent market.
     */
    fun fastForward(targetTick: Int, executor: ExecutorService? = null) {
        val s = checkNotNull(current) { "market closed" }
        val target = minOf(targetTick, s.ticks)
        if (tick >= target) return
        if (ledger.accounts.isNotEmpty() || executor == null) {
            while (tick < target) step()
            return
        }
        val work = tickers.values.map { t -> Callable { while (t.tick < target) t.step() } }
        executor.invokeAll(work).forEach { it.get() }
    }

    /** Liquidates accounts below maintenance margin, one position at a time. */
    private fun checkMargins() {
        if (ledger.accounts.isEmpty()) return
        for (a in ledger.accounts.values) {
            if (a.positions.isEmpty()) continue
            val f = ledger.figures(a, prices)
            if (f.equity >= f.maintenanceRequirement) continue
            if (tickers.values.any { it.playerOrders.hasPendingLiquidation(a.id) }) continue

            tickers.values.forEach { it.playerOrders.cancelAll(a.id, "Cancelled: margin call") }
            val (ticker, pos) = a.positions.entries.maxBy { (t, p) ->
                val value = abs(p.quantity) * (prices[t] ?: 0)
                val loss = if (p.quantity > 0) p.costBasis - value else value - p.costBasis
                loss.toDouble() + value / 1e6 // largest loss first, then largest position
            }.toPair()
            val price = prices[ticker] ?: continue
            val m = if (pos.quantity > 0) a.plan.maintenanceMargin else Plan.SHORT_MAINTENANCE
            val shortfall = f.maintenanceRequirement - f.equity
            val qty = if (f.equity <= 0) abs(pos.quantity)
                else ceil(shortfall / (m * price) * 1.25).toLong().coerceIn(1, abs(pos.quantity))
            val side = if (pos.quantity > 0) Side.SELL else Side.BUY
            val id = nextLiquidationId++
            val leg = Leg(
                id, id, a.id, ticker, LegSpec(side, qty, OrderKind.MARKET, tif = TimeInForce.IOC),
                Structure.SINGLE, null, null, day, liquidation = true,
            )
            leg.executeDay = day
            leg.executeTick = tick
            tickers.getValue(ticker).playerOrders.add(leg)
            marketEvents += AccountEvent(a.id, AccountEvent.Kind.MARGIN_CALL, "Liquidating $qty $ticker")
        }
    }

    fun endSession() {
        val s = checkNotNull(current) { "market closed" }
        tickers.values.forEach { it.endSession() }
        val plans = ledger.accounts.values.associate { it.id to it.plan }
        ledger.accrueDaily(prices, config.benchmarkRate, ::borrowRate)
        ledger.accounts.values.filter { it.plan != plans[it.id] }
            .forEach { marketEvents += AccountEvent(it.id, AccountEvent.Kind.PLAN_LOST, it.plan.name) }
        lastSessionClose = s.close
        sessionsCompleted++
        current = null
    }

    /** Runs a full session for every ticker, in parallel when [executor] is given. */
    fun runSession(session: Session, executor: ExecutorService? = null) {
        beginSession(session)
        fastForward(session.ticks, executor)
        endSession()
    }

    fun writeSnapshot(out: DataOutputStream) {
        check(current == null) { "snapshots are taken while the market is closed" }
        out.writeInt(Snapshot.MAGIC)
        out.writeInt(Snapshot.VERSION)
        out.writeLong(seed)
        out.writeLong(marketRng.state)
        out.writeUTF(regime.name)
        out.writeList(regimeHistory) { writeUTF(it.name) }
        out.writeInt(sessionsCompleted)
        out.writeNullableLong(lastSessionClose?.toEpochMilli())
        out.writeList(tickers.values.toList()) { it.writeTo(this) }
        ledger.writeTo(out)
        out.writeLong(nextLiquidationId)
        out.writeLong(lastInputSeq)
        out.flush()
    }

    private fun maybeChangeRegime() {
        if (!marketRng.chance(1.0 / regime.expectedDays)) return
        regime = marketRng.weighted(
            when (regime) {
                MarketRegime.NEUTRAL -> mapOf(MarketRegime.BULL to 40.0, MarketRegime.BEAR to 40.0, MarketRegime.BUBBLE to 10.0, MarketRegime.CRASH to 10.0)
                MarketRegime.BULL -> mapOf(MarketRegime.NEUTRAL to 60.0, MarketRegime.BUBBLE to 25.0, MarketRegime.BEAR to 15.0)
                MarketRegime.BEAR -> mapOf(MarketRegime.NEUTRAL to 60.0, MarketRegime.CRASH to 20.0, MarketRegime.BULL to 20.0)
                MarketRegime.CRASH -> mapOf(MarketRegime.BEAR to 60.0, MarketRegime.NEUTRAL to 40.0)
                MarketRegime.BUBBLE -> mapOf(MarketRegime.CRASH to 40.0, MarketRegime.BULL to 40.0, MarketRegime.NEUTRAL to 20.0)
            },
        )
    }

    /** Gap size in game days: a longer close realizes more variance, capped at one weekend. */
    private fun gapDays(session: Session): Double {
        val prev = lastSessionClose ?: return config.gapVarianceDays
        val hours = Duration.between(prev, session.open).toHours().coerceAtLeast(1)
        val scale = if (session.kind == SessionKind.WEEKDAY_B) 0.5 else minOf(2.0, hours / 8.0)
        return config.gapVarianceDays * scale
    }

    companion object {
        /** Liquidation order ids live far above player order ids (groupId * 4 + leg). */
        const val LIQUIDATION_ID_BASE = 4_000_000_000_000_000_000L

        fun readSnapshot(input: DataInputStream, config: MarketConfig = MarketConfig()): Market {
            require(input.readInt() == Snapshot.MAGIC) { "not a stonks snapshot" }
            val version = input.readInt()
            require(version in Snapshot.MIN_VERSION..Snapshot.VERSION) { "unsupported snapshot version $version" }
            val seed = input.readLong()
            val rng = Rng.restore(input.readLong())
            val regime = MarketRegime.valueOf(input.readUTF())
            val history = input.readList { MarketRegime.valueOf(readUTF()) }
            val completed = input.readInt()
            val lastClose = input.readNullableLong()?.let(Instant::ofEpochMilli)
            val tickers = input.readList { TickerSim.readFrom(this, config, version) }
            return Market(tickers, config, seed, rng).also {
                it.regime = regime
                it.regimeHistory += history
                it.sessionsCompleted = completed
                it.lastSessionClose = lastClose
                if (version >= 2) {
                    it.ledger.readFrom(input)
                    it.nextLiquidationId = input.readLong()
                    it.lastInputSeq = input.readLong()
                } else {
                    tickers.forEach { t -> t.alignDay(completed) }
                }
            }
        }
    }
}
