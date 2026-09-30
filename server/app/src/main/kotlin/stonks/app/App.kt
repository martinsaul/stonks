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
    private val housekeeping = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "housekeeping").apply { isDaemon = true } }

    /** Builds the world (or restores it) and starts the live market. */
    fun start(liveLoop: Boolean = true) {
        market.onPublish(feed::publish)
        market.onAccountsChanged(feed::accountsChanged)
        market.bootstrap()
        if (liveLoop) market.start()
        housekeeping.scheduleWithFixedDelay({ runCatching { sessions.flushLastSeen() } }, 60, 60, TimeUnit.SECONDS)
        housekeeping.scheduleWithFixedDelay({ runCatching { auth.cleanup() } }, 5, 60, TimeUnit.MINUTES)
    }

    override fun close() {
        housekeeping.shutdown()
        runCatching { sessions.flushLastSeen() }
        market.close()
        db.close()
    }
}
