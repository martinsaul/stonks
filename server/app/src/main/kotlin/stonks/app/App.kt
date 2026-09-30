package stonks.app

import stonks.app.accounts.AccountStore
import stonks.app.accounts.EventLog
import stonks.app.api.QuoteStats
import stonks.app.auth.AuthService
import stonks.app.auth.EmailPolicy
import stonks.app.auth.EmailSender
import stonks.app.auth.Guard
import stonks.app.auth.LoggingEmailSender
import stonks.app.auth.SessionStore
import stonks.app.db.Db
import stonks.app.market.CandleStore
import stonks.app.market.MarketRuntime
import stonks.app.market.WorldStore
import stonks.app.ws.PriceFeed
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Wires the application's services together. */
class App(
    val config: AppConfig,
    val db: Db,
    val clock: Clock = Clock.systemUTC(),
    emailSender: EmailSender = LoggingEmailSender(),
) : AutoCloseable {
    val accounts = AccountStore(db)
    val events = EventLog()
    val sessions = SessionStore(db, maxPerAccount = config.limits.maxSessionsPerAccount)
    val guard = Guard(sessions, config.limits, clock)
    val auth = AuthService(
        db, EmailPolicy(config.allowedEmailDomains), accounts, sessions, events, emailSender,
        config.otpPepper, config.devMode, clock,
        fixedCode = config.fixedOtp,
        emailsPerAddress = config.limits.otpEmailsPerAddress,
    )
    val candles = CandleStore(db)
    val trading = stonks.app.trading.TradingStore(db)
    val news = stonks.app.market.NewsStore(db)
    val market = MarketRuntime(
        WorldStore(db), candles, trading, news, clock,
        seedOverride = config.worldSeed,
        backfillSessions = config.backfillSessions,
    )
    val feed = PriceFeed(config.limits.maxSocketsPerAccount) { market.portfolio(it) }
    /** Splits per ticker (for adjusting history), cached for a minute. */
    val splits = stonks.app.api.TtlCache<String, List<stonks.app.market.ActionRow>> { news.splits(it) }
    val quoteStats = stonks.app.api.TtlCache<String, QuoteStats> { ticker ->
        val daily = stonks.app.market.adjustForSplits(candles.query(ticker, stonks.engine.sim.Resolution.D1, null, null, 252), splits(ticker))
        val recent = daily.takeLast(30)
        QuoteStats(daily.maxOfOrNull { it.high }, daily.minOfOrNull { it.low }, recent.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.volume } / r.size })
    }
    val leaderboards = stonks.app.economy.Leaderboards(db, accounts, { market.allPortfolios() }, clock)
    val admin = stonks.app.admin.AdminStore(db)

    /** Restarts the process (a world rollback applies on start). Replaced in tests. */
    var restart: () -> Unit = {
        Thread {
            Thread.sleep(1000)
            org.slf4j.LoggerFactory.getLogger(App::class.java).warn("Restarting for a world rollback")
            Runtime.getRuntime().exit(0)
        }.start()
    }

    fun requestRestart() = restart()
    private val housekeeping = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "housekeeping").apply { isDaemon = true } }

    /** Builds the world (or restores it) and starts the live market. */
    fun start(liveLoop: Boolean = true) {
        market.onPublish(feed::publish)
        market.onAccountsChanged(feed::accountsChanged)
        market.onSessionEnd = { day, ports ->
            val worths = ports.map { it.standing.netWorth }
            admin.recordStats(day, clock.instant(), worths, ports.sumOf { it.cash }, ports.sumOf { maxOf(0, -it.cash) })
        }
        admin.applyPendingRollback(clock.instant())
        market.bootstrap()
        if (liveLoop) market.start()
        housekeeping.scheduleWithFixedDelay({ runCatching { sessions.flushLastSeen() } }, 60, 60, TimeUnit.SECONDS)
        housekeeping.scheduleWithFixedDelay({ runCatching { auth.cleanup() } }, 5, 60, TimeUnit.MINUTES)
        housekeeping.scheduleWithFixedDelay({
            runCatching { leaderboards.refresh() }.onFailure { org.slf4j.LoggerFactory.getLogger(App::class.java).error("leaderboards", it) }
        }, 10, 60, TimeUnit.SECONDS)
    }

    override fun close() {
        housekeeping.shutdown()
        runCatching { sessions.flushLastSeen() }
        market.close()
        db.close()
    }
}
