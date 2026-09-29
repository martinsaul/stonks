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
    private val clock: Clock,
    private val schedule: MarketSchedule = MarketSchedule(),
    private val seedOverride: Long? = null,
    private val backfillSessions: Int = 500,
    private val config: MarketConfig = MarketConfig(retention = CandleRetention(0, 0, 0)),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(MarketRuntime::class.java)
    private val writer = CandleWriter(candles, clock)
    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "candle-writer").apply { isDaemon = true } }
    private val published = AtomicReference<MarketState>()
    private val listeners = CopyOnWriteArrayList<(MarketState) -> Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null

    lateinit var market: Market
        private set

    val state: MarketState get() = checkNotNull(published.get()) { "market not started" }

    fun onPublish(listener: (MarketState) -> Unit) {
        listeners += listener
    }

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
        catchUp(clock.instant())
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
        Backfill.run(market, schedule, liveAt, backfillSessions) { i, _ ->
            if (writer.pending > 1_000_000) writer.flush(bulk = true)
            if ((i + 1) % 100 == 0) log.info("  {}/{} sessions", i + 1, backfillSessions)
        }
        writer.flush(bulk = true)
        saveSnapshot()
        log.info("World created in {}s", Duration.ofNanos(System.nanoTime() - started).seconds)
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
                    market.runSession(next, pool)
                    saveSnapshot()
                    caughtUp++
                }
                !next.open.isAfter(now) -> {
                    market.beginSession(next)
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
        val s = market.session
        if (s == null) {
            val next = nextSession()
            if (now.isBefore(next.open)) return next.open
            catchUp(now)
            publish(now)
        } else {
            val due = ticksDue(s, now)
            if (market.tick < due) {
                while (market.tick < due) market.step()
                if (market.tick >= s.ticks) finishSession()
                publish(now)
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
                    val sleep = Duration.between(clock.instant(), wake).toMillis().coerceIn(20, 1000)
                    Thread.sleep(sleep)
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
        pool.shutdown()
    }

    private fun finishSession() {
        market.endSession()
        saveSnapshot()
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

        val value = 1000.0 * tickers.map { it.last.toDouble() / it.company.initialPrice }.average()
        val prev = tickers.mapNotNull { t -> t.previousClose?.let { it.toDouble() / t.company.initialPrice } }
            .takeIf { it.size == tickers.size }?.let { 1000.0 * it.average() }
        val index = IndexQuote("STONKS 50", value, prev, prev?.let { value - it }, prev?.let { (value - it) / it * 100 })

        val state = MarketState(now, session, market.regime.name, index, quotes, depth, liveMinute)
        published.set(state)
        for (l in listeners) {
            try { l(state) } catch (e: Exception) { log.warn("listener failed", e) }
        }
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
            marketCap = (t.last / 100.0 * t.company.sharesOutstanding).toLong(),
        )
    }
}
