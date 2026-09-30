package stonks.app.market

import org.slf4j.LoggerFactory
import stonks.engine.clock.MarketSchedule
import stonks.engine.clock.Session
import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.sim.Backfill
import stonks.engine.sim.CandleRetention
import stonks.engine.sim.Market
import stonks.engine.sim.MarketConfig
import stonks.engine.sim.TickerSim
import stonks.app.trading.Schedule
import stonks.app.trading.TradingDesk
import stonks.app.trading.TradingStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the live market. All engine access happens on one simulation thread; other
 * threads read the immutable [state] published after every tick.
 *
 * Startup: create the world (seed + simulated history) or restore the latest snapshot,
 * then catch up on any sessions missed while the server was down, including fast-
 * forwarding into a session in progress. Snapshots are saved after every session.
 */
class MarketRuntime(
    private val worlds: WorldStore,
    private val candles: CandleStore,
    private val trading: TradingStore,
    private val news: NewsStore,
    private val clock: Clock,
    private val schedule: MarketSchedule = MarketSchedule(),
    private val seedOverride: Long? = null,
    private val backfillSessions: Int = 500,
    private val config: MarketConfig = MarketConfig(retention = CandleRetention(0, 0, 0)),
    orderProcessing: Duration = Duration.ofMillis(10),
) : AutoCloseable {
    /** Player trading: intake, input log, replay, portfolio views. */
    val desk = TradingDesk(trading, orderProcessing)
    private var lastPortfolioPersist = Instant.EPOCH
    private val log = LoggerFactory.getLogger(MarketRuntime::class.java)
    private val writer = CandleWriter(candles, clock)
    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "candle-writer").apply { isDaemon = true } }
    private val published = AtomicReference<MarketState>()
    private val listeners = CopyOnWriteArrayList<(MarketState) -> Unit>()
    private val accountListeners = CopyOnWriteArrayList<(Set<Long>) -> Unit>()
    private val portfolios = java.util.concurrent.ConcurrentHashMap<Long, stonks.app.trading.PortfolioDto>()
    @Volatile private var running = false
    /** The open session, or the last one to open (for stamping news times). */
    private var lastSession: Session? = null
    @Volatile var latestNewsId = 0L
        private set
    private var calendarCache: Pair<Int, List<Session>>? = null
    private var thread: Thread? = null

    lateinit var market: Market
        private set

    val state: MarketState get() = checkNotNull(published.get()) { "market not started" }

    fun onPublish(listener: (MarketState) -> Unit) {
        listeners += listener
    }

    /** Called with the accounts whose portfolios changed between market ticks. */
    fun onAccountsChanged(listener: (Set<Long>) -> Unit) {
        accountListeners += listener
    }

    /** A player's latest portfolio (null until their trading account exists). */
    fun portfolio(accountId: Long): stonks.app.trading.PortfolioDto? = portfolios[accountId]

    /** Creates or restores the world and catches up to [clock]. Blocking. */
    fun bootstrap() {
        val world = worlds.load()
        val snapshot = worlds.latestSnapshot()
        if (world != null && snapshot != null) {
            market = Market.readSnapshot(DataInputStream(ByteArrayInputStream(snapshot)), config)
            log.info("Restored world (seed {}) after {} sessions", market.seed, market.sessionsCompleted)
        } else {
            createWorld(world?.seed ?: seedOverride ?: SecureRandom().nextLong(), world?.liveAt)
        }
        market.setCandleSink(writer)
        latestNewsId = news.latestId()
        desk.setEstimator(::estimate)
        replay()
        catchUp(clock.instant())
        desk.absorb(market.drainEvents(), clock.instant())
        publish(clock.instant())
        flusher.scheduleWithFixedDelay({ runCatching { writer.flush() }.onFailure { log.error("flush", it) } }, 1, 1, TimeUnit.SECONDS)
        flusher.scheduleWithFixedDelay({ runCatching { candles.sweepExpired(clock.instant()) } }, 1, 60, TimeUnit.MINUTES)
    }

    private fun createWorld(seed: Long, existingLiveAt: Instant?) {
        val liveAt = existingLiveAt ?: clock.instant()
        if (existingLiveAt == null) worlds.create(seed, liveAt)
        log.info("Creating world with seed {}: simulating {} sessions of history…", seed, backfillSessions)
        market = Market(CompanyCatalog.generate(Rng(Rng.derive(seed, -1))), config, seed)
        market.setCandleSink(writer)
        val started = System.nanoTime()
        Backfill.run(market, schedule, liveAt, backfillSessions) { i, s ->
            lastSession = s
            drainWorld(sync = true)
            if (writer.pending > 1_000_000) writer.flush(bulk = true)
            if ((i + 1) % 100 == 0) log.info("  {}/{} sessions", i + 1, backfillSessions)
        }
        writer.flush(bulk = true)
        saveSnapshot()
        log.info("World created in {}s", Duration.ofNanos(System.nanoTime() - started).seconds)
    }

    /**
     * Re-applies logged player inputs made after the restored snapshot, each at the
     * exact session and tick it was originally applied, so fills come out identical.
     */
    private fun replay() {
        val inputs = trading.inputsAfter(market.lastInputSeq)
        if (inputs.isEmpty()) return
        for (i in inputs) {
            advanceTo(i.applyDay, i.applyTick)
            desk.applyLogged(market, i)
            desk.absorb(market.drainEvents(), clock.instant())
        }
        log.info("Replayed {} player inputs", inputs.size)
    }

    private fun advanceTo(day: Int, tick: Int) {
        while (true) {
            val s = market.session
            if (s == null) {
                if (market.day == day && tick == TradingDesk.CLOSED) return
                check(market.day <= day) { "input log is ahead of the market" }
                begin(nextSession())
            } else if (market.day == day && tick >= 0) {
                market.fastForward(tick)
                return
            } else {
                market.fastForward(s.ticks)
                finishSession()
            }
            desk.absorb(market.drainEvents(), clock.instant())
        }
    }

    /** Maps an order's intake-queue ready time to the tick that executes it. */
    private fun scheduleOrder(ready: Instant): Schedule {
        val s = market.session
        if (s != null && ready.isBefore(s.close)) {
            val ms = Duration.between(s.open, ready).toMillis()
            val k = maxOf(market.tick, Math.ceilDiv(ms, MarketSchedule.TICK_SECONDS * 1000).toInt() - 1)
            if (k < s.ticks) return Schedule(market.day, k, s.tickTime(k + 1))
            return Schedule(market.day + 1, -1, null)
        }
        return Schedule(market.day, -1, null)
    }

    private fun estimate(day: Int, tick: Int): Instant? {
        val s = market.session
        return when {
            s != null && day == market.day && tick >= 0 -> s.tickTime(tick + 1)
            s != null -> schedule.nextSessionAfter(s.close).open
            else -> nextSession().open
        }
    }

    /** Runs every session that should already have happened by [now]. */
    private fun catchUp(now: Instant) {
        market.session?.let { s ->
            market.fastForward(ticksDue(s, now), pool)
            if (market.tick >= s.ticks) finishSession()
        }
        var caughtUp = 0
        while (market.session == null) {
            val next = nextSession()
            when {
                !next.close.isAfter(now) -> {
                    begin(next)
                    market.fastForward(next.ticks, pool)
                    finishSession()
                    caughtUp++
                }
                !next.open.isAfter(now) -> {
                    begin(next)
                    market.fastForward(ticksDue(next, now), pool)
                }
                else -> break
            }
        }
        if (caughtUp > 0) log.info("Caught up {} missed sessions", caughtUp)
    }

    /**
     * Does all work due at [now] and returns when it should be called next. Called by
     * the simulation thread; exposed for tests.
     */
    fun pump(now: Instant): Instant {
        val touched = HashSet(desk.process(market, now, ::scheduleOrder))
        var marketChanged = false
        val s = market.session
        if (s == null) {
            if (!now.isBefore(nextSession().open)) {
                catchUp(now)
                marketChanged = true
            }
        } else {
            val due = ticksDue(s, now)
            if (market.tick < due) {
                while (market.tick < due) market.step()
                if (market.tick >= s.ticks) finishSession()
                marketChanged = true
            }
        }
        touched += desk.absorb(market.drainEvents(), now)
        drainWorld()
        if (marketChanged) {
            publish(now) // prices moved: every portfolio is rebuilt and every client gets a tick
        } else if (touched.isNotEmpty()) {
            // Between ticks only the affected players' portfolios change: rebuild and push
            // just those (not a broadcast to every client).
            portfolios.putAll(desk.portfolios(market, touched))
            for (l in accountListeners) {
                try { l(touched) } catch (e: Exception) { log.warn("account listener failed", e) }
            }
        }
        val open = market.session ?: return nextSession().open
        return open.tickTime(market.tick + 1)
    }

    /** Writes all queued candles now (normally done every second in the background). */
    fun flushCandles() = writer.flush()

    fun start() {
        running = true
        thread = Thread({
            while (running) {
                try {
                    val wake = pump(clock.instant())
                    val sleep = Duration.between(clock.instant(), wake).toMillis().coerceIn(5, 1000)
                    desk.awaitWork(sleep) // wakes early when a player command arrives
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("Simulation loop error", e)
                    Thread.sleep(1000)
                }
            }
        }, "market-sim").apply { start() }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(5000)
        flusher.shutdown()
        writer.flush()
        desk.close()
        pool.shutdown()
    }

    private fun begin(s: Session) {
        market.beginSession(s)
        lastSession = s
    }

    private fun finishSession() {
        market.endSession()
        drainWorld()
        saveSnapshot()
    }

    /**
     * Moves the engine's news and corporate actions to the database, stamped with
     * wall-clock times from the session they happened in.
     */
    private fun drainWorld(sync: Boolean = false) {
        val items = market.drainNews()
        val actions = market.drainActions()
        if (items.isEmpty() && actions.isEmpty()) return
        val s = market.session ?: lastSession
        fun at(tick: Int): Instant = when {
            s == null -> clock.instant()
            tick < 0 -> s.open
            tick >= s.ticks -> s.close
            else -> s.tickTime(tick + 1)
        }
        val stamped = items.map { StampedNews(it, at(it.tick)) }
        val rows = actions.map { ActionRow(it.ticker, it.day, it.kind.name, it.ratio, at(-1)) }
        items.lastOrNull()?.let { latestNewsId = maxOf(latestNewsId, it.id) }
        val write = {
            try { news.insert(stamped, rows) } catch (e: Exception) { log.error("Failed to save {} news items", stamped.size, e) }
        }
        if (sync) write() else flusher.execute(write)
    }

    companion object {
        /** How far ahead the calendar looks (game days). */
        const val CALENDAR_DAYS = 60
    }

    private fun nextSession(): Session = schedule.nextSessionAfter(market.lastSessionClose ?: clock.instant())

    /** Ticks that should have been simulated by [now]: tick k runs once its 5s window ends. */
    private fun ticksDue(s: Session, now: Instant): Int =
        (Duration.between(s.open, now).seconds / MarketSchedule.TICK_SECONDS).toInt().coerceIn(0, s.ticks)

    private fun saveSnapshot() {
        val bytes = ByteArrayOutputStream().also { market.writeSnapshot(DataOutputStream(it)) }.toByteArray()
        worlds.saveSnapshot(market.sessionsCompleted, market.lastSessionClose, bytes)
    }

    private fun publish(now: Instant) {
        val s = market.session
        val session = if (s != null) {
            SessionInfo("OPEN", s.kind.name, s.open.toString(), s.close.toString())
        } else {
            val next = nextSession()
            SessionInfo("CLOSED", nextOpen = next.open.toString(), nextKind = next.kind.name)
        }
        val tickers = market.tickers.values
        val quotes = tickers.map { quote(it) }
        val depth = tickers.associate { t ->
            t.company.ticker to Depth(
                t.book.depth(Side.BUY, 10).map { DepthLevel(it.price, it.quantity) },
                t.book.depth(Side.SELL, 10).map { DepthLevel(it.price, it.quantity) },
            )
        }
        val liveMinute = if (s == null) emptyMap() else tickers.mapNotNull { t ->
            t.candles.minutes.current?.let { t.company.ticker to CandleDto(it.time.toString(), it.open, it.high, it.low, it.close, it.volume) }
        }.toMap()

        val value = market.indexValue
        val prev = market.indexClose.takeIf { market.sessionsCompleted > 0 }
        val index = IndexQuote("STONKS 50", value, prev, prev?.let { value - it }, prev?.let { (value - it) / it * 100 })

        val all = desk.portfolios(market)
        portfolios.putAll(all)
        if (Duration.between(lastPortfolioPersist, now) >= Duration.ofMinutes(1) || market.session == null) {
            lastPortfolioPersist = now
            desk.persistPortfolios(all.values)
        }
        val dates = sessionDates()
        val date = { day: Int -> dates.getOrNull(day - market.day)?.open?.toString() }
        val fundamentals = tickers.associate { it.company.ticker to fundamentals(it, date) }
        val state = MarketState(
            now, session, market.regime.name, index, quotes, depth, liveMinute,
            fundamentals, calendar(date),
            market.delistings.takeLast(50).map { DelistingDto(it.ticker, it.name, it.day, it.price, it.reason) },
            market.benchmarkRate * 100, latestNewsId,
        )
        published.set(state)
        for (l in listeners) {
            try { l(state) } catch (e: Exception) { log.warn("listener failed", e) }
        }
    }

    /** The current (or next) session and those after it: index k is game day market.day + k. */
    private fun sessionDates(): List<Session> {
        calendarCache?.let { (day, list) -> if (day == market.day) return list }
        val list = ArrayList<Session>(CALENDAR_DAYS)
        list += market.session ?: nextSession()
        while (list.size < CALENDAR_DAYS) list += schedule.nextSessionAfter(list.last().close)
        calendarCache = market.day to list
        return list
    }

    private fun fundamentals(t: TickerSim, date: (Int) -> String?): Fundamentals {
        val c = t.corp
        val eps = c.epsTtm
        val short = market.ledger.shortInterest[t.company.ticker] ?: 0
        return Fundamentals(
            shares = c.shares,
            epsTtm = eps,
            pe = eps?.takeIf { it > 0 }?.let { t.last.toDouble() / it },
            dividend = c.dividend,
            dividendYield = if (c.dividend > 0) c.dividend * 4 * 100.0 / t.last else null,
            exDividendDate = c.exDay.takeIf { it >= market.day }?.let(date),
            dividendPayDate = c.payDay.takeIf { it >= market.day }?.let(date),
            nextEarnings = date(c.nextEarningsDay),
            consensusEps = c.consensus,
            status = c.status.name,
            dealOffer = c.deal?.offer,
            splitDate = c.splitDay.takeIf { it >= 0 }?.let(date),
            splitRatio = c.splitRatio.takeIf { c.splitDay >= 0 },
            shortInterest = short,
            shortInterestPct = short * 100.0 / c.shares.coerceAtLeast(1),
            borrowFee = market.borrowRate(t.company.ticker) * 100,
        )
    }

    private fun calendar(date: (Int) -> String?): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        fun add(day: Int, kind: String, t: TickerSim?, detail: String, ticker: String? = t?.company?.ticker, name: String? = t?.company?.name) {
            if (day < market.day) return
            val d = date(day) ?: return
            out += CalendarEvent(d, day, kind, ticker, name, detail)
        }
        val money = { c: Long -> "$" + "%,.2f".format(c / 100.0) }
        for (t in market.tickers.values) {
            val c = t.corp
            add(c.nextEarningsDay, "EARNINGS", t, "EPS estimate ${money(c.consensus)}")
            if (c.exDay >= 0) add(c.exDay, "EX_DIVIDEND", t, "${money(c.declaredDividend)} per share")
            if (c.payDay >= 0 && c.declaredDividend > 0) add(c.payDay, "DIVIDEND_PAY", t, "${money(c.declaredDividend)} per share")
            if (c.splitDay >= 0) add(c.splitDay, "SPLIT", t,
                if (c.splitRatio >= 1) "${c.splitRatio.toInt()}-for-1 split" else "1-for-${Math.round(1 / c.splitRatio)} reverse split")
            c.deal?.let { add(it.closeDay, "DEAL_CLOSE", t, "Cash-out at ${money(it.offer)} per share (if the deal completes)") }
        }
        for (ipo in market.ipoPipeline) add(ipo.day, "IPO", null, "IPO at ${money(ipo.company.initialPrice)}", ipo.company.ticker, ipo.company.name)
        add(market.nextRateDay, "RATE_DECISION", null, "Fedora Reserve rate decision (now ${"%.2f".format(market.benchmarkRate * 100)}%)")
        return out.sortedWith(compareBy({ it.day }, { it.kind }, { it.ticker }))
    }

    private fun quote(t: TickerSim): Quote {
        val bids = t.book.depth(Side.BUY, 1).firstOrNull()
        val asks = t.book.depth(Side.SELL, 1).firstOrNull()
        val change = t.previousClose?.let { t.last - it }
        return Quote(
            ticker = t.company.ticker,
            name = t.company.name,
            sector = t.company.sector.name,
            last = t.last,
            bid = bids?.price,
            ask = asks?.price,
            bidSize = bids?.quantity,
            askSize = asks?.quantity,
            prevClose = t.previousClose,
            open = t.dayOpen,
            high = t.dayHigh,
            low = t.dayLow,
            volume = t.dayVolume,
            change = change,
            changePct = change?.let { c -> t.previousClose?.let { c * 100.0 / it } },
            marketCap = (t.last / 100.0 * t.corp.shares).toLong(),
        )
    }
}
