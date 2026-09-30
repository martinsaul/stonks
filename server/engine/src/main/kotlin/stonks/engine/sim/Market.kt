package stonks.engine.sim

import stonks.engine.book.Fill
import stonks.engine.clock.Session
import stonks.engine.clock.SessionKind
import stonks.engine.company.Company
import stonks.engine.company.Sector
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
import stonks.engine.trading.BondHolding
import stonks.engine.trading.BondOffering
import stonks.engine.trading.EconomyAction
import stonks.engine.trading.EconomyRules
import stonks.engine.trading.PositionWatch
import stonks.engine.trading.ResetRecord
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

    /** Split, listing or delisting, for adjusting and annotating history. */
    data class CorporateAction(val ticker: String, val day: Int, val kind: Kind, val ratio: Double = 1.0) {
        enum class Kind { SPLIT, LISTED, DELISTED }
    }
    private val actionsOut = ArrayList<CorporateAction>()

    /** Corporate actions since the last call (not kept in snapshots: drain before saving). */
    fun drainActions(): List<CorporateAction> = ArrayList(actionsOut).also { actionsOut.clear() }

    /** Index level at the last close; the index chain-links through listings and delistings. */
    var indexClose: Double = INDEX_BASE
        private set

    /** Equal-weighted index: yesterday's close times the average move since each stock's previous close. */
    val indexValue: Double get() {
        val moves = tickerMap.values.mapNotNull { t -> t.previousClose?.let { t.last.toDouble() / it } }
        return if (moves.isEmpty()) indexClose else indexClose * moves.average()
    }

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
            val before = a.positions[leg.ticker]?.let { it.quantity to it.averagePrice }
            val realized = ledger.applyFill(a, leg.ticker, leg.spec.side, qty, price, commission)
            watchPosition(a, leg.ticker, before, price)
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

    private fun borrowPool(t: TickerSim): Long = (t.corp.shares * (t.borrowPoolFraction ?: config.borrowPoolFraction)).toLong()

    fun borrowRate(ticker: String): Double {
        val t = tickers[ticker] ?: return 0.0
        val pool = borrowPool(t).coerceAtLeast(1)
        // (pool uses current shares, which change with offerings, buybacks and splits)
        return Ledger.borrowRate((ledger.shortInterest[ticker] ?: 0).toDouble() / pool)
    }

    // ---- Player inputs (applied on the simulation thread, logged by the app) --------

    fun openAccount(accountId: Long, cash: Cents): Boolean {
        if (ledger.account(accountId) != null) return false
        ledger.open(accountId, cash).standing.apply {
            runStartCash = cash
            runStartDay = day
        }
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
        matureBonds()
        applyShocks(-1)

        current = session
        val fills = tickers.mapValues { (ticker, t) ->
            val extra = marketGap * t.company.marketBeta + (sectorGap[t.company.sector] ?: 0.0) * t.company.sectorBeta + (tickerGap[ticker] ?: 0.0)
            t.beginSession(session, factors, regime, gapDays, day, extra)
        }
        collectNews()
        return fills
    }

    // ---- Player economy ------------------------------------------------------------

    /** Admin-issued bond offerings, oldest first. */
    val bondOfferings = ArrayList<BondOffering>()
    private var nextBondId = 1L

    /** Net worth: equity plus bonds at face value. */
    fun netWorth(a: stonks.engine.trading.Account): Cents =
        Math.addExact(ledger.figures(a, prices).equity, a.bondValue)

    /** Current starting cash for [a] (5k + 1k per upgrade level). */
    fun startingCash(a: stonks.engine.trading.Account): Cents = EconomyRules.startingCash(a.standing.cashLevel)

    /** Wall-clock time of the current tick (or last close), epoch ms. */
    private fun nowMillis(): Long = current?.let { it.tickTime(tick).toEpochMilli() } ?: lastSessionClose?.toEpochMilli() ?: 0L

    /**
     * Applies a player economy action at [at] (epoch ms, from the input log). [key] makes
     * the resulting event unique across replays. Returns a rejection reason or null.
     */
    fun economy(accountId: Long, action: EconomyAction, at: Long, key: String): String? {
        val a = ledger.account(accountId) ?: return "No trading account."
        val st = a.standing
        val worth = try { netWorth(a) } catch (_: ArithmeticException) { return "Account can't be valued right now." }
        return when (action) {
            EconomyAction.Claim -> {
                if (worth >= EconomyRules.CLAIM_BELOW) return "Claims are for net worth below ${Ledger.money(EconomyRules.CLAIM_BELOW)}."
                st.lastClaimAt?.let { last ->
                    if (at - last < EconomyRules.CLAIM_INTERVAL_MS) return "You can claim again after ${java.time.Instant.ofEpochMilli(last + EconomyRules.CLAIM_INTERVAL_MS)}."
                }
                a.cash = Math.addExact(a.cash, EconomyRules.CLAIM_AMOUNT)
                st.lastClaimAt = at
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.CLAIMED, "Weekly claim: +${Ledger.money(EconomyRules.CLAIM_AMOUNT)}", EconomyRules.CLAIM_AMOUNT, key)
                null
            }
            EconomyAction.Reset -> {
                if (at < st.nextResetAt) return "Your next reset is available after ${java.time.Instant.ofEpochMilli(st.nextResetAt)}."
                val debt = maxOf(0L, -worth)
                val recent = st.resets.count { it.inDebt && at - it.at <= 180 * EconomyRules.DAY_MS }
                val days = EconomyRules.resetCooldownDays(debt, recent)
                st.resets += ResetRecord(at, debt > 0)
                st.resets.removeIf { at - it.at > 365 * EconomyRules.DAY_MS }
                st.nextResetAt = at + (days * EconomyRules.DAY_MS).toLong()
                val cash = startingCash(a)
                wipe(a, cash, at)
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.RESET,
                    "Account reset to ${Ledger.money(cash)}${if (debt > 0) " (debt of ${Ledger.money(debt)} wiped)" else ""}. Next reset in ${"%.0f".format(days)} days.", -debt, key)
                null
            }
            EconomyAction.Bankrupt -> {
                if (worth >= 0) return "Bankruptcy is only for players with negative net worth."
                bankrupt(a, at, forced = false, key = key)
                null
            }
            EconomyAction.Upgrade -> {
                if (st.cashLevel >= EconomyRules.MAX_LEVEL) return "Maximum level reached."
                val cost = EconomyRules.upgradeCost(st.cashLevel + 1)
                pay(a, cost)?.let { return it }
                st.cashLevel++
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.UPGRADED,
                    "Starting cash upgraded to ${Ledger.money(startingCash(a))} (level ${st.cashLevel})", -cost, key)
                null
            }
            EconomyAction.ClearBadge -> {
                if (st.shame == 0) return "You have no badges of shame."
                if (st.eternal) return "Eternal Shame: badges can no longer be cleared."
                val cost = EconomyRules.clearCost(st.shame)
                pay(a, cost)?.let { return it }
                st.shame--
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.BADGE_CLEARED, "Badge of shame cleared (${st.shame} left)", -cost, key)
                null
            }
            is EconomyAction.BuyBond -> {
                val o = bondOfferings.firstOrNull { it.id == action.offeringId } ?: return "Unknown bond offering."
                if (day !in o.openDay..o.closeDay) return "This offering is not open for subscriptions."
                if (action.amount <= 0) return "Amount must be positive."
                val held = a.bonds.filter { it.offeringId == o.id }.sumOf { it.principal }
                if (held + action.amount > o.capPerPlayer) return "Limit is ${Ledger.money(o.capPerPlayer)} per player (you hold ${Ledger.money(held)})."
                pay(a, action.amount)?.let { return it }
                val payout = (action.amount * (1 + o.returnRate)).roundToLong()
                a.bonds += BondHolding(o.id, action.amount, payout, o.maturityDay)
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.BOND_BOUGHT,
                    "Bought ${Ledger.money(action.amount)} of ${o.name}; pays ${Ledger.money(payout)} at maturity", -action.amount, key)
                null
            }
        }
    }

    /** Pays [cost] from cash; it must be covered by both cash and available equity. */
    private fun pay(a: stonks.engine.trading.Account, cost: Cents): String? {
        val f = ledger.figures(a, prices)
        if (a.cash < cost || f.buyingPowerEquity < cost) return "Not enough cash: this costs ${Ledger.money(cost)}."
        a.cash -= cost
        return null
    }

    private fun bankrupt(a: stonks.engine.trading.Account, at: Long, forced: Boolean, key: String) {
        val st = a.standing
        val worth = try { netWorth(a) } catch (_: ArithmeticException) { 0L }
        st.cashLevel = maxOf(0, st.cashLevel - 1)
        st.shame++
        st.shameEver++
        st.bankruptcies++
        if (st.shame >= EconomyRules.ETERNAL_SHAME) st.eternal = true
        val cash = startingCash(a)
        wipe(a, cash, at)
        val how = if (forced) "Game over: net worth hit ${Ledger.money(worth)}." else "You declared bankruptcy."
        marketEvents += AccountEvent(a.id, AccountEvent.Kind.BANKRUPT,
            "$how Fresh start with ${Ledger.money(cash)}; +1 badge of shame (${st.shame} outstanding).", worth, key)
    }

    /** Fresh start: orders, positions, bonds and debt are gone; plan back to Rookie. */
    private fun wipe(a: stonks.engine.trading.Account, cash: Cents, at: Long) {
        tickers.values.forEach { it.playerOrders.cancelEverything(a.id, "Cancelled: account reset") }
        for ((t, p) in a.positions) {
            if (p.quantity < 0) ledger.shortInterest.merge(t, p.quantity) { x, y -> (x + y).takeIf { it > 0 } }
        }
        a.positions.clear()
        a.reservations.clear()
        a.bonds.clear()
        a.watches.clear()
        a.cash = cash
        a.plan = Plan.ROOKIE
        a.lifetimeRealized = 0
        a.totalCommissions = 0
        a.totalInterest = 0
        a.standing.runStartCash = cash
        a.standing.runStartedAt = at
        a.standing.runStartDay = day
        dividendsDue.removeIf { it.accountId == a.id }
    }

    /** Forced bankruptcy (game over) at net worth <= -10 x starting cash. */
    private fun checkGameOver(everyone: Boolean = false) {
        if (ledger.accounts.isEmpty()) return
        for (a in ledger.accounts.values.sortedBy { it.id }) {
            if (!everyone && a.positions.isEmpty() && a.cash >= 0) continue
            val worth = try { netWorth(a) } catch (_: ArithmeticException) { continue }
            if (worth > -EconomyRules.GAME_OVER_MULTIPLE * startingCash(a)) continue
            val at = nowMillis()
            bankrupt(a, at, forced = true, key = "gameover:${a.id}:$day:${if (current != null) tick else -2}")
        }
    }

    /** Net-worth milestones (2x, 10x, 100x the run's starting cash), each awarded once ever. */
    private fun checkMilestones() {
        for (a in ledger.accounts.values) {
            val worth = try { netWorth(a) } catch (_: ArithmeticException) { continue }
            for ((multiple, name) in EconomyRules.MILESTONES) {
                if (name in a.standing.awarded || worth < multiple * a.standing.runStartCash) continue
                a.standing.awarded += name
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.ACHIEVEMENT, name, key = "ms:${a.id}:$name")
            }
        }
    }

    /** Tracks long positions' price extremes and awards Midas' / Sadim's Hands on exit. */
    private fun watchPosition(a: stonks.engine.trading.Account, ticker: String, before: Pair<Long, Double>?, price: Cents) {
        val qtyBefore = before?.first ?: 0L
        val qtyAfter = a.quantity(ticker)
        val t = tickers[ticker]
        when {
            qtyBefore <= 0 && qtyAfter > 0 -> a.watches[ticker] = PositionWatch(day, price, price)
            qtyBefore > 0 && qtyAfter > 0 -> a.watches[ticker]?.let { w -> w.low = minOf(w.low, price); w.high = maxOf(w.high, price) }
            qtyBefore > 0 -> {
                val w = a.watches.remove(ticker) ?: return
                if (day <= w.openDay) return
                val low = minOf(w.low, t?.dayLow ?: w.low, price).toDouble()
                val high = maxOf(w.high, t?.dayHigh ?: w.high, price).toDouble()
                val avg = before!!.second
                val name = when {
                    avg <= low * 1.02 && price >= high * 0.98 && price >= avg * 1.2 -> "MIDAS_HANDS"
                    avg >= high * 0.98 && price <= low * 1.02 && price <= avg * 0.8 -> "SADIMS_HANDS"
                    else -> return
                }
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.ACHIEVEMENT, name, key = "ach:${a.id}:$name:$day:$tick:$ticker")
            }
        }
    }

    private fun updateWatches() {
        for (a in ledger.accounts.values) for ((ticker, w) in a.watches) {
            val t = tickers[ticker] ?: continue
            t.dayLow?.let { w.low = minOf(w.low, it) }
            t.dayHigh?.let { w.high = maxOf(w.high, it) }
        }
    }

    /**
     * Issues a bond offering (admin): open for [windowDays] game days, maturing
     * [termDays] after the window closes. Returns it.
     */
    fun issueBonds(name: String, returnRate: Double, windowDays: Int, termDays: Int, capPerPlayer: Cents): BondOffering {
        require(returnRate in 0.0..1.0 && windowDays in 1..60 && termDays in 1..400 && capPerPlayer in 1..1_000_000_00L) { "invalid offering" }
        val start = day
        val o = BondOffering(nextBondId++, name, returnRate, start, start + windowDays - 1, start + windowDays - 1 + termDays, capPerPlayer)
        bondOfferings += o
        marketNews += NewsDraft(day, if (current != null) tick else -1, null, null, NewsCategory.MACRO,
            "Treasury opens the $name: +${"%.0f".format(returnRate * 100)}% at maturity, up to ${Ledger.money(capPerPlayer)} per investor, subscriptions for $windowDays sessions")
        return o
    }

    private fun matureBonds() {
        for (a in ledger.accounts.values.sortedBy { it.id }) {
            val due = a.bonds.filter { it.maturityDay <= day }
            if (due.isEmpty()) continue
            a.bonds.removeAll(due.toSet())
            for (b in due) {
                a.cash = Math.addExact(a.cash, b.payout)
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.BOND_MATURED,
                    "Bond matured: ${Ledger.money(b.payout)} paid (${Ledger.money(b.payout - b.principal)} interest)", b.payout, "bond:${a.id}:${b.offeringId}:$day")
            }
        }
        bondOfferings.removeIf { it.maturityDay < day - 60 }
    }

    // ---- Game master -----------------------------------------------------------------

    /** Market and sector shocks scheduled by admins. */
    val scheduledShocks = ArrayList<ScheduledShock>()

    /** Applies an admin action. Returns a rejection reason or null. */
    fun admin(action: AdminAction): String? {
        fun ticker(t: String) = tickerMap[t.uppercase()]
        val open = current != null
        val nowTick = if (open) tick else -1
        return when (action) {
            is AdminAction.SetStrategy -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                t.corp.forceStrategy(action.strategy, if (action.nextSession || !open) null else t.strategy)
                null
            }
            is AdminAction.CompanyEvent -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                when (action.event) {
                    "DISTRESS" -> { t.corp.enterDistress(day); null }
                    "BUYOUT" -> {
                        if (t.corp.status != stonks.engine.world.CompanyStatus.ACTIVE) return "Company is not active."
                        t.shock(t.corp.adminBuyout(day, t.reference, 0.3, 10, completes = true, strategy = if (open) t.strategy else null))
                        null
                    }
                    else -> {
                        val type = runCatching { stonks.engine.world.EventType.valueOf(action.event) }.getOrNull() ?: return "Unknown event."
                        val d = action.day ?: day
                        val tk = action.tick ?: nowTick
                        if (d < day || (d == day && open && tk < tick)) return "That time has passed."
                        t.corp.schedule(stonks.engine.world.AgendaItem(d, tk, stonks.engine.world.AgendaKind.EVENT, type, category = type.category))
                        null
                    }
                }
            }
            is AdminAction.Shock -> {
                if (action.percent <= -95 || action.percent > 1000) return "Percent must be between -95 and 1000."
                if (action.headline.isBlank() || action.headline.length > 200) return "A headline (up to 200 characters) is required."
                when (action.scope) {
                    AdminAction.Shock.Scope.TICKER -> ticker(action.target ?: "") ?: return "Unknown ticker."
                    AdminAction.Shock.Scope.SECTOR -> Sector.entries.firstOrNull { it.name == action.target } ?: return "Unknown sector."
                    AdminAction.Shock.Scope.MARKET -> {}
                }
                val d = action.day ?: day
                val tk = action.tick ?: nowTick
                if (d < day || (d == day && open && tk < tick)) return "That time has passed."
                scheduledShocks += ScheduledShock(d, tk, action.scope, action.target?.uppercase(), kotlin.math.ln(1 + action.percent / 100), action.headline)
                if (d == day && (!open || tk <= tick)) applyShocks(if (open) tick else -1)
                null
            }
            is AdminAction.SetRate -> {
                if (action.percent !in 0.0..10.0) return "Rate must be 0–10%."
                val reaction = setBenchmarkRate(action.percent / 100)
                tickerMap.values.forEach { it.shock(reaction * it.company.marketBeta) }
                nextRateDay = day + RATE_INTERVAL
                null
            }
            is AdminAction.SetRegime -> {
                regime = action.regime
                marketNews += NewsDraft(day, nowTick, null, null, NewsCategory.MACRO, REGIME_HEADLINES.getValue(action.regime))
                null
            }
            is AdminAction.Halt -> {
                val targets = if (action.ticker == null) tickerMap.values.toList() else listOf(ticker(action.ticker) ?: return "Unknown ticker.")
                targets.forEach { it.halted = action.halted }
                val what = action.ticker?.uppercase() ?: "all trading"
                marketNews += NewsDraft(day, nowTick, action.ticker?.uppercase(), null, NewsCategory.MACRO,
                    if (action.halted) "Exchange halts $what" else "Exchange resumes $what")
                null
            }
            is AdminAction.Tune -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                action.volatility?.let { if (it !in 0.0..10.0) return "Volatility multiplier must be 0–10."; t.volMultiplier = it }
                action.depth?.let { if (it !in 0.01..100.0) return "Depth multiplier must be 0.01–100."; t.depthMultiplier = it }
                action.borrowPool?.let { if (it !in 0.0..1.0) return "Borrow pool must be 0–1."; t.borrowPoolFraction = it }
                null
            }
            is AdminAction.Split -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                val r = action.ratio
                val valid = r in listOf(2.0, 3.0, 4.0, 5.0, 10.0) || (r < 1 && (1 / r).let { Math.abs(it - Math.round(it)) < 1e-9 && Math.round(it) in 2..100 })
                if (!valid) return "Ratio must be 2, 3, 4, 5 or 10 (split) or 1/n for a reverse split."
                if (t.corp.splitDay >= 0) return "A split is already pending."
                t.corp.forceSplit(day, r, delay = if (open) 1 else 0)
                null
            }
            is AdminAction.SpecialDividend -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                if (action.amount !in 1..t.last) return "Amount must be between 1 cent and the share price."
                if (t.corp.exDay >= day) return "A dividend is already pending."
                t.corp.specialDividend(day, action.amount, delay = if (open) 1 else 0)
                null
            }
            is AdminAction.Buyback -> {
                val t = ticker(action.ticker) ?: return "Unknown ticker."
                if (action.percent !in 0.1..25.0) return "Buyback must be 0.1–25% of shares."
                t.shock(t.corp.buyback(day, nowTick, action.percent / 100))
                null
            }
            is AdminAction.Ipo -> {
                if (action.days !in 1..30) return "Days must be 1–30."
                // While closed, `day` is already the next session.
                val listingDay = day + action.days - if (open) 0 else 1
                val company = Ipo.create(listingDay, usedTickers, (tickerMap.values.map { it.company.name } + ipoPipeline.map { it.company.name }).toSet(), worldRng, action.sector)
                usedTickers += company.ticker
                ipoPipeline += PendingIpo(listingDay, company)
                marketNews += NewsDraft(day, nowTick, company.ticker, company.sector, NewsCategory.LISTING,
                    "${company.name} (${company.ticker}) sets IPO at ${Headlines.money(company.initialPrice)} per share; trading begins in ${action.days} sessions")
                null
            }
            is AdminAction.IssueBonds -> try {
                issueBonds(action.name, action.returnPct / 100, action.windowDays, action.termDays, action.capPerPlayer)
                null
            } catch (_: IllegalArgumentException) {
                "Invalid offering (return 0–100%, window 1–60, term 1–400 sessions, cap up to $1M)."
            }
            is AdminAction.VoidFill -> {
                val a = ledger.account(action.accountId) ?: return "No trading account."
                if (action.quantity <= 0 || action.price <= 0) return "Invalid fill."
                try {
                    ledger.applyFill(a, action.ticker, action.side.opposite, action.quantity, action.price, 0)
                    a.cash = Math.addExact(a.cash, action.commission)
                    a.totalCommissions -= action.commission
                } catch (_: ArithmeticException) {
                    return "Fill can't be reversed."
                }
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.ADJUSTED,
                    "An administrator voided your ${action.side.name.lowercase()} of ${action.quantity} ${action.ticker} @ ${Ledger.money(action.price)}")
                null
            }
            is AdminAction.AdjustCash -> {
                val a = ledger.account(action.accountId) ?: return "No trading account."
                if (action.amount == 0L || kotlin.math.abs(action.amount) > 1_000_000_000_00L) return "Amount must be non-zero and at most $1B."
                a.cash = try { Math.addExact(a.cash, action.amount) } catch (_: ArithmeticException) { return "Amount too large." }
                marketEvents += AccountEvent(a.id, AccountEvent.Kind.ADJUSTED,
                    "An administrator ${if (action.amount > 0) "credited" else "debited"} ${Ledger.money(kotlin.math.abs(action.amount))}: ${action.reason}", action.amount)
                null
            }
        }
    }

    /** Applies scheduled shocks due at [atTick] of today (-1 = the open). */
    private fun applyShocks(atTick: Int) {
        if (scheduledShocks.isEmpty()) return
        val due = scheduledShocks.filter { it.day < day || (it.day == day && it.tick <= atTick) }
        if (due.isEmpty()) return
        scheduledShocks.removeAll(due.toSet())
        for (sh in due) {
            val targets = when (sh.scope) {
                AdminAction.Shock.Scope.TICKER -> listOfNotNull(tickerMap[sh.target])
                AdminAction.Shock.Scope.SECTOR -> tickerMap.values.filter { it.company.sector.name == sh.target }
                AdminAction.Shock.Scope.MARKET -> tickerMap.values.toList()
            }
            for (t in targets) {
                val beta = when (sh.scope) {
                    AdminAction.Shock.Scope.TICKER -> 1.0
                    AdminAction.Shock.Scope.SECTOR -> t.company.sectorBeta
                    AdminAction.Shock.Scope.MARKET -> t.company.marketBeta
                }
                t.shock(sh.logReturn * beta)
            }
            val sector = if (sh.scope == AdminAction.Shock.Scope.SECTOR) Sector.valueOf(sh.target!!) else null
            val ticker = if (sh.scope == AdminAction.Shock.Scope.TICKER) sh.target else null
            val category = if (sh.scope == AdminAction.Shock.Scope.MARKET) NewsCategory.MACRO else if (sector != null) NewsCategory.SECTOR else NewsCategory.CORPORATE
            marketNews += NewsDraft(day, atTick, ticker, sector ?: ticker?.let { tickerMap[it]?.company?.sector }, category, sh.headline, sh.logReturn)
        }
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
        actionsOut += CorporateAction(ticker, day, CorporateAction.Kind.DELISTED)
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
            actionsOut += CorporateAction(ipo.company.ticker, day, CorporateAction.Kind.LISTED)
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
            actionsOut += CorporateAction(ticker, day, CorporateAction.Kind.SPLIT, ratio)
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
        applyShocks(tick)
        val reports = tickers.values.map { it.step() }
        checkMargins()
        checkGameOver()
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
        indexClose = indexValue
        tickers.values.forEach { it.endSession() }
        val plans = ledger.accounts.values.associate { it.id to it.plan }
        updateWatches()
        ledger.accrueDaily(prices, benchmarkRate, ::borrowRate)
        ledger.accounts.values.filter { it.plan != plans[it.id] }
            .forEach { marketEvents += AccountEvent(it.id, AccountEvent.Kind.PLAN_LOST, it.plan.name) }
        lastSessionClose = s.close
        checkGameOver(everyone = true)
        checkMilestones()
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
        out.writeDouble(indexClose)
        // v4: bond offerings.
        out.writeList(bondOfferings) {
            writeLong(it.id); writeUTF(it.name); writeDouble(it.returnRate); writeInt(it.openDay); writeInt(it.closeDay)
            writeInt(it.maturityDay); writeLong(it.capPerPlayer)
        }
        out.writeLong(nextBondId)
        // v5: scheduled admin shocks.
        out.writeList(scheduledShocks) {
            writeInt(it.day); writeInt(it.tick); writeUTF(it.scope.name); writeUTF(it.target ?: ""); writeDouble(it.logReturn); writeUTF(it.headline)
        }
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
        const val INDEX_BASE = 1000.0
        private val REGIME_HEADLINES = mapOf(
            MarketRegime.NEUTRAL to "Markets settle as investors await direction",
            MarketRegime.BULL to "Optimism sweeps markets: analysts declare a new bull run",
            MarketRegime.BEAR to "Gloom spreads across markets as investors turn cautious",
            MarketRegime.CRASH to "Panic grips markets in a broad sell-off",
            MarketRegime.BUBBLE to "Euphoria takes hold: 'this time is different', say traders",
        )
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
                    it.ledger.readFrom(input, version)
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
                    it.indexClose = input.readDouble()
                    if (version >= 4) {
                        it.bondOfferings += input.readList {
                            BondOffering(readLong(), readUTF(), readDouble(), readInt(), readInt(), readInt(), readLong())
                        }
                        it.nextBondId = input.readLong()
                    }
                    if (version >= 5) {
                        it.scheduledShocks += input.readList {
                            ScheduledShock(readInt(), readInt(), AdminAction.Shock.Scope.valueOf(readUTF()), readUTF().ifEmpty { null }, readDouble(), readUTF())
                        }
                    }
                } else {
                    // Continue the old index (average of price relative to IPO price).
                    val rel = tickers.mapNotNull { t -> t.previousClose?.let { p -> p.toDouble() / t.company.initialPrice } }
                    if (rel.isNotEmpty()) it.indexClose = INDEX_BASE * rel.average()
                    // Upgraded world: corporate life starts now.
                    it.benchmarkRate = config.benchmarkRate
                    it.nextRateDay = completed + RATE_INTERVAL
                    it.assignDividendPayers()
                }
            }
        }
    }
}
