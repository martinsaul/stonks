package stonks.engine.sim

import stonks.engine.book.Fill
import stonks.engine.clock.Session
import stonks.engine.clock.SessionKind
import stonks.engine.company.Company
import stonks.engine.core.Rng
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * The whole regional market: every ticker plus the shared factors and regime.
 *
 * Live mode calls [beginSession], then [step] every 5 seconds, then [endSession].
 * Backfill uses [runSession], which simulates tickers in parallel; results are
 * identical either way because tickers only interact through pre-generated factors.
 */
class Market(
    companies: List<Company>,
    val config: MarketConfig = MarketConfig(),
    val seed: Long,
) {
    private val marketRng = Rng(Rng.derive(seed, 0))
    val tickers: Map<String, TickerSim> = companies.withIndex().associate { (i, c) ->
        c.ticker to TickerSim(c, config, Rng.derive(seed, 1000L + i))
    }

    var regime: MarketRegime = MarketRegime.NEUTRAL
    /** Regime of every session run so far, oldest first. */
    val regimeHistory = mutableListOf<MarketRegime>()
    private var previousClose: java.time.Instant? = null
    private var current: Session? = null
    private var currentFactors: SessionFactors? = null

    val session: Session? get() = current

    fun beginSession(session: Session): Map<String, List<Fill>> {
        check(current == null) { "session already open" }
        maybeChangeRegime()
        regimeHistory += regime
        val factors = SessionFactors.generate(marketRng, session.ticks)
        val gapDays = gapDays(session)
        current = session
        currentFactors = factors
        previousClose = session.close
        return tickers.mapValues { (_, t) -> t.beginSession(session, factors, regime, gapDays) }
    }

    fun step(): List<TickReport> = tickers.values.map { it.step() }

    fun endSession() {
        tickers.values.forEach { it.endSession() }
        current = null
        currentFactors = null
    }

    /** Runs a full session for every ticker, in parallel when [executor] is given. */
    fun runSession(session: Session, executor: ExecutorService? = null) {
        beginSession(session)
        val ticks = session.ticks
        val work = tickers.values.map { t -> Callable { repeat(ticks) { t.step() } } }
        if (executor == null) work.forEach { it.call() } else executor.invokeAll(work).forEach { it.get() }
        endSession()
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
        val prev = previousClose ?: return config.gapVarianceDays
        val hours = Duration.between(prev, session.open).toHours().coerceAtLeast(1)
        val scale = if (session.kind == SessionKind.WEEKDAY_B) 0.5 else minOf(2.0, hours / 8.0)
        return config.gapVarianceDays * scale
    }
}
