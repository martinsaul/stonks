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
        return tickers.mapValues { (_, t) -> t.beginSession(session, factors, regime, gapDays) }
    }

    fun step(): List<TickReport> = tickers.values.map { it.step() }

    /** Steps every ticker until [targetTick] (exclusive), in parallel when [executor] is given. */
    fun fastForward(targetTick: Int, executor: ExecutorService? = null) {
        val s = checkNotNull(current) { "market closed" }
        val target = minOf(targetTick, s.ticks)
        if (tick >= target) return
        val work = tickers.values.map { t -> Callable { while (t.tick < target) t.step() } }
        if (executor == null) work.forEach { it.call() } else executor.invokeAll(work).forEach { it.get() }
    }

    fun endSession() {
        val s = checkNotNull(current) { "market closed" }
        tickers.values.forEach { it.endSession() }
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
        fun readSnapshot(input: DataInputStream, config: MarketConfig = MarketConfig()): Market {
            require(input.readInt() == Snapshot.MAGIC) { "not a stonks snapshot" }
            val version = input.readInt()
            require(version == Snapshot.VERSION) { "unsupported snapshot version $version" }
            val seed = input.readLong()
            val rng = Rng.restore(input.readLong())
            val regime = MarketRegime.valueOf(input.readUTF())
            val history = input.readList { MarketRegime.valueOf(readUTF()) }
            val completed = input.readInt()
            val lastClose = input.readNullableLong()?.let(Instant::ofEpochMilli)
            val tickers = input.readList { TickerSim.readFrom(this, config) }
            return Market(tickers, config, seed, rng).also {
                it.regime = regime
                it.regimeHistory += history
                it.sessionsCompleted = completed
                it.lastSessionClose = lastClose
            }
        }
    }
}
