package stonks.app

import stonks.app.accounts.AccountStore
import stonks.app.accounts.EventLog
import stonks.app.api.QuoteStats
import stonks.app.api.QuoteStatsCache
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
    )
    val candles = CandleStore(db)
    val market = MarketRuntime(
        WorldStore(db), candles, clock,
        seedOverride = config.worldSeed,
        backfillSessions = config.backfillSessions,
    )
    val feed = PriceFeed(config.limits.maxSocketsPerAccount)
    val quoteStats = QuoteStatsCache { ticker ->
        candles.dailyStats(ticker).let { QuoteStats(it.high52, it.low52, it.avgVolume30) }
    }
    private val housekeeping = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "housekeeping").apply { isDaemon = true } }

    /** Builds the world (or restores it) and starts the live market. */
    fun start(liveLoop: Boolean = true) {
        market.onPublish(feed::publish)
        market.bootstrap()
        if (liveLoop) market.start()
        housekeeping.scheduleWithFixedDelay({ runCatching { sessions.flushLastSeen() } }, 60, 60, TimeUnit.SECONDS)
    }

    override fun close() {
        housekeeping.shutdown()
        runCatching { sessions.flushLastSeen() }
        market.close()
        db.close()
    }
}
