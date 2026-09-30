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
import stonks.engine.world.CompanyStatus
import stonks.engine.world.Corporate
import stonks.engine.world.Headlines
import stonks.engine.world.Ipo
import stonks.engine.world.NewsCategory
import stonks.engine.world.NewsDraft
import stonks.engine.world.NewsItem
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
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
    ) {
        assignDividendPayers()
    }

    private val tickerMap = LinkedHashMap<String, TickerSim>().apply { tickerList.forEach { put(it.company.ticker, it) } }

    /** Listed tickers in listing order. Changes only while the market is closed (IPOs, delistings). */
    val tickers: Map<String, TickerSim> get() = tickerMap

    // ---- World state (news, macro, lifecycle) --------------------------------------
    /** Macro and lifecycle randomness, separate from the price factors. */
    private var worldRng = Rng(Rng.derive(seed, 7))

    /** Central bank rate (annual), drives margin interest. */
    var benchmarkRate: Double = config.benchmarkRate
        private set

    /** Game day of the next scheduled rate decision. */
    var nextRateDay: Int = RATE_INTERVAL
        private set

    private var nextNewsId = 1L
    private val marketNews = ArrayList<NewsDraft>()
    private val newsOut = ArrayList<NewsItem>()

    /** Dividends owed at a pay date, fixed at the ex-date. */
    data class DividendDue(val accountId: Long, val ticker: String, val amount: Cents, val payDay: Int)
    private val dividendsDue = ArrayList<DividendDue>()

    data class Delisting(val ticker: String, val name: String, val day: Int, val price: Cents, val reason: String)
    /** Every company that left the market, oldest first. */
    val delistings = ArrayList<Delisting>()

    data class PendingIpo(val day: Int, val company: Company)
    val ipoPipeline = ArrayList<PendingIpo>()

    /** Tickers ever used (never reused, so history stays unambiguous). */
    private val usedTickers = HashSet<String>().apply { addAll(tickerMap.keys) }
    private var nextListingSerial = 0L
    private var candleSink: CandleSink? = null

    /** News since the last call, oldest first. */
    fun drainNews(): List<NewsItem> {
        collectNews()
        val out = ArrayList(newsOut)
        newsOut.clear()
        return out
    }

    private fun collectNews() {
        val drafts = ArrayList<NewsDraft>(marketNews)
        marketNews.clear()
        for (t in tickerMap.values) {
            drafts += t.corp.news
            t.corp.news.clear()
        }
        for (d in drafts) newsOut += NewsItem(nextNewsId++, d.day, d.tick, d.ticker, d.sector, d.category, d.headline, d.sentiment)
        // Nobody is draining (e.g. a long backfill): keep only recent items.
        if (newsOut.size > MAX_BUFFERED_NEWS) newsOut.subList(0, newsOut.size - MAX_BUFFERED_NEWS).clear()
    }

    /** About 25% of companies pay dividends: all aristocrats, then eligible others at random. */
    private fun assignDividendPayers() {
        val all = tickerMap.values.map { it.corp }
        val target = (all.size * DIVIDEND_SHARE).roundToLong().toInt()
        val payers = all.filter { it.aristocrat }.toMutableSet()
        val eligible = all.filter { !it.aristocrat && it.canPayDividends }.toMutableList()
        while (payers.size < target && eligible.isNotEmpty()) payers += eligible.removeAt(worldRng.nextInt(0, eligible.size))
        all.forEach { it.assignDividendPolicy(it in payers) }
    }

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

    private fun borrowPool(t: TickerSim): Long = (t.corp.shares * config.borrowPoolFraction).toLong()

    fun borrowRate(ticker: String): Double {
        val t = tickers[ticker] ?: return 0.0
        val pool = borrowPool(t).coerceAtLeast(1)
        // (pool uses current shares, which change with offerings, buybacks and splits)
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
        candleSink = sink
        tickers.values.forEach { it.candles.sink = sink }
    }

    fun beginSession(session: Session): Map<String, List<Fill>> {
        check(current == null) { "session already open" }
        lastSessionClose?.let { require(!session.open.isBefore(it)) { "session $session precedes the last close $it" } }
        maybeChangeRegime()
        regimeHistory += regime
        val factors = SessionFactors.generate(marketRng, session.ticks)
        val gapDays = gapDays(session)
        val weekend = session.kind == SessionKind.WEEKEND

        // Before the open: macro news, corporate lifecycle, then per-share actions.
        val marketGap = if (!weekend && day >= nextRateDay) rateDecision() else 0.0
        val sectorGap = if (!weekend) sectorNews() else emptyMap()
        val tickerGap = HashMap<String, Double>()
        lifecycle(tickerGap)
        listIpos()
        applySplits()
        payDividends()
        recordDividendEntitlements(weekend)

        current = session
        val fills = tickers.mapValues { (ticker, t) ->
            val extra = marketGap * t.company.marketBeta + (sectorGap[t.company.sector] ?: 0.0) * t.company.sectorBeta + (tickerGap[ticker] ?: 0.0)
            t.beginSession(session, factors, regime, gapDays, day, extra)
        }
        collectNews()
        return fills
    }

    // ---- Central bank ------------------------------------------------------------

    /** Applies a scheduled decision; returns the market's log-return reaction. */
    private fun rateDecision(): Double {
        val weights = when (regime) {
            MarketRegime.BULL -> doubleArrayOf(2.0, 8.0, 50.0, 30.0, 10.0)
            MarketRegime.BUBBLE -> doubleArrayOf(1.0, 4.0, 35.0, 40.0, 20.0)
            MarketRegime.NEUTRAL -> doubleArrayOf(5.0, 20.0, 50.0, 20.0, 5.0)
            MarketRegime.BEAR -> doubleArrayOf(15.0, 35.0, 40.0, 8.0, 2.0)
            MarketRegime.CRASH -> doubleArrayOf(40.0, 35.0, 20.0, 4.0, 1.0)
        }
        val moves = doubleArrayOf(-0.005, -0.0025, 0.0, 0.0025, 0.005)
        val choice = worldRng.weighted(moves.indices.associateWith { weights[it] })
        nextRateDay = day + RATE_INTERVAL
        return setBenchmarkRate(benchmarkRate + moves[choice])
    }

    /**
     * Sets the benchmark rate (clamped to 0–10%) with news and a market reaction (log
     * return, surprise-weighted). Used by scheduled decisions and admin overrides.
     */
    fun setBenchmarkRate(rate: Double): Double {
        val next = (Math.round(rate.coerceIn(0.0, MAX_RATE) * 10_000) / 10_000.0)
        val change = next - benchmarkRate
        benchmarkRate = next
        val pct = "%.2f%%".format(next * 100)
        val points = "%.2f".format(abs(change) * 100)
        val headline = when {
            change > 0 -> "Fedora Reserve raises the benchmark rate by $points points to $pct"
            change < 0 -> "Fedora Reserve cuts the benchmark rate by $points points to $pct"
            else -> "Fedora Reserve holds the benchmark rate at $pct"
        }
        val reaction = -change * 2 + worldRng.gaussian() * 0.003
        marketNews += NewsDraft(day, -1, null, null, NewsCategory.MACRO, headline, reaction)
        return reaction
    }

    /** Occasional sector-wide news (weekdays, ~1 in 15 sessions). */
    private fun sectorNews(): Map<stonks.engine.company.Sector, Double> {
        if (!worldRng.chance(1.0 / 15)) return emptyMap()
        val sector = worldRng.pick(stonks.engine.company.Sector.entries)
        val good = worldRng.chance(0.5)
        val jump = worldRng.nextDouble(0.01, 0.03) * if (good) 1 else -1
        marketNews += NewsDraft(day, -1, null, sector, NewsCategory.SECTOR, Headlines.sector(sector, good, worldRng), jump)
        return mapOf(sector to jump)
    }

    // ---- Corporate lifecycle -------------------------------------------------------

    private fun lifecycle(tickerGap: MutableMap<String, Double>) {
        for (t in tickerMap.values.toList()) {
            val corp = t.corp
            val deal = corp.deal
            when {
                deal != null && day >= deal.closeDay -> {
                    if (deal.completes) {
                        val how = if (deal.takePrivate) "taken private" else "acquired"
                        marketNews += NewsDraft(day, -1, t.company.ticker, t.company.sector, NewsCategory.DEAL,
                            "${t.company.name} deal closes: ${t.company.ticker} delisted, holders receive ${Headlines.money(deal.offer)} per share")
                        delist(t, deal.offer, "Company $how at ${Headlines.money(deal.offer)} per share")
                    } else {
                        corp.breakDeal(day)
                    }
                }
                corp.status == CompanyStatus.DISTRESS && day >= corp.distressEnd -> when (corp.resolveDistress()) {
                    Corporate.Outcome.BANKRUPTCY -> {
                        marketNews += NewsDraft(day, -1, t.company.ticker, t.company.sector, NewsCategory.DISTRESS,
                            "${t.company.name} files for bankruptcy; shares cancelled and ${t.company.ticker} delisted", -1.0)
                        delist(t, 0, "Bankruptcy: shares cancelled")
                    }
                    Corporate.Outcome.BAILOUT -> corp.bailout(day)
                    Corporate.Outcome.TAKEN_PRIVATE -> tickerGap[t.company.ticker] = corp.takePrivateOffer(day, t.reference)
                }
            }
        }
    }

    /** Removes a company: orders cancelled, positions settled at [price], replacement IPO scheduled. */
    private fun delist(t: TickerSim, price: Cents, reason: String) {
        val ticker = t.company.ticker
        for (a in ledger.accounts.values) {
            t.playerOrders.cancelAll(a.id, "Cancelled: $ticker delisted")
            val qty = a.quantity(ticker)
            if (qty == 0L) continue
            val realized = try { ledger.closeOut(a, ticker, price) } catch (_: ArithmeticException) { 0L }
            marketEvents += AccountEvent(a.id, AccountEvent.Kind.DELISTED,
                "$ticker delisted ($reason). Your ${abs(qty)} ${if (qty > 0) "shares" else "short shares"} settled at ${Headlines.money(price)}; realized ${Headlines.money(realized)}")
        }
        ledger.shortInterest.remove(ticker)
        // Dividends already owed (past the ex-date) are still paid on the pay date.
        tickerMap.remove(ticker)
        delistings += Delisting(ticker, t.company.name, day, price, reason)
        val listingDay = day + worldRng.nextInt(2, 6)
        val company = Ipo.create(listingDay, usedTickers, (tickerMap.values.map { it.company.name } + ipoPipeline.map { it.company.name }).toSet(), worldRng)
        usedTickers += company.ticker
        ipoPipeline += PendingIpo(listingDay, company)
        marketNews += NewsDraft(day, Int.MAX_VALUE, company.ticker, company.sector, NewsCategory.LISTING,
            "${company.name} (${company.ticker}) sets IPO at ${Headlines.money(company.initialPrice)} per share; trading begins in ${listingDay - day} sessions")
    }

    private fun listIpos() {
        val due = ipoPipeline.filter { it.day <= day }
        if (due.isEmpty()) return
        ipoPipeline.removeAll(due.toSet())
        for (ipo in due) {
            val t = TickerSim(ipo.company, config, Rng.derive(seed, IPO_SEED_BASE + nextListingSerial++), listedDay = day)
            t.candles.sink = candleSink
            tickerMap[ipo.company.ticker] = t
            marketNews += NewsDraft(day, -1, ipo.company.ticker, ipo.company.sector, NewsCategory.LISTING,
                "${ipo.company.name} (${ipo.company.ticker}) debuts today; opening auction prices the IPO")
        }
    }

    private fun applySplits() {
        for (t in tickerMap.values) {
            val corp = t.corp
            if (corp.splitDay < 0 || day < corp.splitDay) continue
            val ticker = t.company.ticker
            val ratio = corp.splitRatio
            for (a in ledger.accounts.values) t.playerOrders.cancelAll(a.id, "Cancelled: $ticker split")
            t.applySplit()
            val changed = ledger.split(ticker, ratio, t.last)
            val what = if (ratio >= 1) "${ratio.toInt()}-for-1 split" else "1-for-${(1 / ratio).roundToLong()} reverse split"
            for ((id, qty) in changed) marketEvents += AccountEvent(id, AccountEvent.Kind.SPLIT, "$ticker $what: you now hold $qty")
            // Dividends owed per share were fixed in cash already; nothing else to adjust.
        }
    }

    /** Ex-date: holders (and short sellers) as of the open are owed the dividend on the pay date. */
    private fun recordDividendEntitlements(weekend: Boolean) {
        if (weekend) return
        for (t in tickerMap.values) {
            val corp = t.corp
            if (corp.exDay !in 0..day) continue
            val ticker = t.company.ticker
            for (a in ledger.accounts.values) {
                val qty = a.quantity(ticker)
                if (qty == 0L) continue
                val amount = try { Math.multiplyExact(qty, corp.declaredDividend) } catch (_: ArithmeticException) { continue }
                dividendsDue += DividendDue(a.id, ticker, amount, corp.payDay)
            }
        }
    }

    private fun payDividends() {
        val due = dividendsDue.filter { it.payDay <= day }
        if (due.isEmpty()) return
        dividendsDue.removeAll(due.toSet())
        for (d in due) {
            val a = ledger.account(d.accountId) ?: continue
            try { ledger.payDividend(a, d.amount) } catch (_: ArithmeticException) { continue }
            val text = if (d.amount >= 0) "Dividend received: ${Headlines.money(d.amount)} from ${d.ticker}"
                else "Dividend charged on your ${d.ticker} short: ${Headlines.money(-d.amount)}"
            marketEvents += AccountEvent(a.id, AccountEvent.Kind.DIVIDEND, text)
        }
    }

    fun step(): List<TickReport> {
        val reports = tickers.values.map { it.step() }
        checkMargins()
        if (tickers.values.any { it.corp.news.isNotEmpty() }) collectNews()
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
            // An account whose figures can't be computed is skipped rather than
            // allowed to halt the market.
            try {
                checkMargin(a)
            } catch (_: ArithmeticException) {
            }
        }
    }

    private fun checkMargin(a: stonks.engine.trading.Account) {
        run {
            val f = ledger.figures(a, prices)
            if (f.equity >= f.maintenanceRequirement) return
            if (tickers.values.any { it.playerOrders.hasPendingLiquidation(a.id) }) return

            tickers.values.forEach { it.playerOrders.cancelAll(a.id, "Cancelled: margin call") }
            val (ticker, pos) = a.positions.entries.maxBy { (t, p) ->
                val value = Math.multiplyExact(abs(p.quantity), prices[t] ?: 0)
                val loss = if (p.quantity > 0) p.costBasis - value else value - p.costBasis
                loss.toDouble() + value / 1e6 // largest loss first, then largest position
            }.toPair()
            val price = prices[ticker] ?: return
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
        ledger.accrueDaily(prices, benchmarkRate, ::borrowRate)
        ledger.accounts.values.filter { it.plan != plans[it.id] }
            .forEach { marketEvents += AccountEvent(it.id, AccountEvent.Kind.PLAN_LOST, it.plan.name) }
        lastSessionClose = s.close
        sessionsCompleted++
        current = null
        collectNews()
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
        // v3: world state. (Undrained news is not kept: drain before snapshotting.)
        out.writeLong(worldRng.state)
        out.writeDouble(benchmarkRate)
        out.writeInt(nextRateDay)
        out.writeLong(nextNewsId)
        out.writeList(dividendsDue) { writeLong(it.accountId); writeUTF(it.ticker); writeLong(it.amount); writeInt(it.payDay) }
        out.writeList(delistings) { writeUTF(it.ticker); writeUTF(it.name); writeInt(it.day); writeLong(it.price); writeUTF(it.reason) }
        out.writeList(ipoPipeline) { writeInt(it.day); it.company.writeTo(this) }
        out.writeList(usedTickers.sorted()) { writeUTF(it) }
        out.writeLong(nextListingSerial)
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

        /** Game days between central bank decisions (~4 real weeks). */
        const val RATE_INTERVAL = 48
        const val MAX_RATE = 0.10
        const val DIVIDEND_SHARE = 0.25
        private const val MAX_BUFFERED_NEWS = 5_000
        private const val IPO_SEED_BASE = 1_000_000L

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
            val tickers = input.readList { TickerSim.readFrom(this, config, version, seed) }
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
                if (version >= 3) {
                    it.worldRng = Rng.restore(input.readLong())
                    it.benchmarkRate = input.readDouble()
                    it.nextRateDay = input.readInt()
                    it.nextNewsId = input.readLong()
                    it.dividendsDue += input.readList { DividendDue(readLong(), readUTF(), readLong(), readInt()) }
                    it.delistings += input.readList { Delisting(readUTF(), readUTF(), readInt(), readLong(), readUTF()) }
                    it.ipoPipeline += input.readList { PendingIpo(readInt(), Company.readFrom(this)) }
                    it.usedTickers += input.readList { readUTF() }
                    it.nextListingSerial = input.readLong()
                } else {
                    // Upgraded world: corporate life starts now.
                    it.benchmarkRate = config.benchmarkRate
                    it.nextRateDay = completed + RATE_INTERVAL
                    it.assignDividendPayers()
                }
            }
        }
    }
}
