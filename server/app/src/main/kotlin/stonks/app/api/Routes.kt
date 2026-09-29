package stonks.app.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import stonks.app.App
import stonks.app.auth.OtpRequestResult
import stonks.app.auth.VerifyResult
import stonks.app.market.CandleDto
import stonks.app.market.Depth
import stonks.app.market.IndexQuote
import stonks.app.market.Quote
import stonks.app.market.SessionInfo
import stonks.engine.sim.Resolution
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable data class OtpRequest(val email: String)
@Serializable data class OtpRequested(val expiresAt: String, val devCode: String? = null)
@Serializable data class OtpVerify(val email: String, val code: String, val publicKey: String)
@Serializable data class LoginResponse(val sessionId: String, val accountId: Long, val expiresAt: String, val newAccount: Boolean, val serverTime: Long)
@Serializable data class BadgeDto(val badge: String, val title: String, val description: String, val count: Int, val lastAwardedAt: String)
@Serializable data class MeResponse(val accountId: Long, val email: String, val createdAt: String, val badges: List<BadgeDto>)
@Serializable data class MarketResponse(val time: String, val session: SessionInfo, val regime: String, val index: IndexQuote, val quotes: List<Quote>)
@Serializable data class CompanyProfile(val ticker: String, val name: String, val sector: String, val sharesOutstanding: Long)
@Serializable data class QuoteStats(val high52w: Long?, val low52w: Long?, val avgVolume30d: Long?)
@Serializable data class QuoteResponse(val time: String, val session: SessionInfo, val quote: Quote, val profile: CompanyProfile, val stats: QuoteStats, val depth: Depth)
@Serializable data class CandlesResponse(val ticker: String, val resolution: String, val candles: List<CandleDto>, val live: CandleDto? = null)

private val RES = mapOf("5s" to Resolution.S5, "1m" to Resolution.M1, "1d" to Resolution.D1)
private const val MAX_CANDLES = 2000

fun Route.authRoutes(app: App) {
    route("/api/v1/auth") {
        post("/otp/request") {
            val ip = call.clientIp
            app.guard.checkIp(ip)
            val req = call.receive<OtpRequest>()
            app.guard.limit(app.guard.otpPerIp, ip, "Too many code requests from this network. Try again later.")
            app.guard.limit(app.guard.otpPerEmail, req.email.trim().lowercase(), "Too many codes requested for this address. Try again later.")
            when (val r = app.auth.requestOtp(req.email)) {
                is OtpRequestResult.Rejected -> throw badRequest(r.reason, "email_rejected")
                is OtpRequestResult.Sent -> call.respond(HttpStatusCode.Accepted, OtpRequested(r.expiresAt.toString(), r.devCode))
            }
        }

        post("/otp/verify") {
            val ip = call.clientIp
            app.guard.checkIp(ip)
            app.guard.limit(app.guard.verifyPerIp, ip, "Too many attempts. Try again later.")
            val req = call.receive<OtpVerify>()
            when (val r = app.auth.verify(req.email, req.code, req.publicKey, call.request.userAgent(), ip)) {
                is VerifyResult.Rejected -> throw badRequest(r.reason, "otp_rejected")
                VerifyResult.AliasRefused -> throw ApiException(
                    HttpStatusCode.Forbidden, "alias_refused",
                    "Nice try. That address is an alias of an existing account, which has been awarded a badge for your effort.",
                )
                is VerifyResult.LoggedIn -> call.respond(
                    LoginResponse(r.session.id, r.accountId, r.session.expiresAt.toString(), r.newAccount, app.clock.millis()),
                )
            }
        }
    }
}

/** Routes that require a signed request. */
fun Route.signedRoutes(app: App) {
    route("/api/v1") { signed(app.guard) {

        post("/auth/logout") {
            app.auth.logout(call.principal.sessionId, call.principal.accountId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val account = app.accounts.byId(call.principal.accountId) ?: throw notFound("Account not found.")
            val badges = app.accounts.badges(account.id).map {
                BadgeDto(it.badge.name, it.badge.title, it.badge.description, it.count, it.lastAwardedAt.toString())
            }
            call.respond(MeResponse(account.id, account.email, account.createdAt.toString(), badges))
        }

        get("/market") {
            val s = app.market.state
            call.respond(MarketResponse(s.time.toString(), s.session, s.regime, s.index, s.quotes))
        }

        get("/quotes/{ticker}") {
            val s = app.market.state
            val ticker = call.parameters["ticker"]!!.uppercase()
            val quote = s.quotesByTicker[ticker] ?: throw notFound("Unknown ticker $ticker.")
            val company = app.market.market.tickers.getValue(ticker).company
            val stats = app.quoteStats(ticker)
            call.respond(
                QuoteResponse(
                    s.time.toString(), s.session, quote,
                    CompanyProfile(company.ticker, company.name, company.sector.name, company.sharesOutstanding),
                    stats, s.depth.getValue(ticker),
                ),
            )
        }

        get("/quotes/{ticker}/candles") {
            val ticker = call.parameters["ticker"]!!.uppercase()
            val state = app.market.state
            val quote = state.quotesByTicker[ticker] ?: throw notFound("Unknown ticker $ticker.")
            val resName = call.request.queryParameters["res"] ?: "1d"
            val res = RES[resName] ?: throw badRequest("res must be one of ${RES.keys}.")
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, MAX_CANDLES)
            val from = call.request.queryParameters["from"]?.let(::parseInstant)
            val to = call.request.queryParameters["to"]?.let(::parseInstant)
            // History is heavier than a quote: charge proportionally to the rows requested.
            app.guard.charge(call.principal, limit / 250.0)

            val rows = app.candles.query(ticker, res, from, to, limit)
                .map { CandleDto(it.time.toString(), it.open, it.high, it.low, it.close, it.volume) }
            val live = when {
                to != null -> null
                res == Resolution.M1 -> state.liveMinute[ticker]
                res == Resolution.D1 && state.session.state == "OPEN" && quote.open != null ->
                    CandleDto(state.session.opensAt!!, quote.open, quote.high!!, quote.low!!, quote.last, quote.volume)
                else -> null
            }
            call.respond(CandlesResponse(ticker, resName, rows, live))
        }

        tradingRoutes(app)
    } }
}

private fun parseInstant(s: String): Instant = try {
    s.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(s)
} catch (_: Exception) {
    throw badRequest("Invalid time '$s' (ISO-8601 or epoch milliseconds).")
}

/** Caches per-ticker daily statistics for a minute. */
class QuoteStatsCache(private val load: (String) -> QuoteStats) {
    private val cache = ConcurrentHashMap<String, Pair<QuoteStats, Long>>()

    operator fun invoke(ticker: String): QuoteStats {
        val now = System.currentTimeMillis()
        cache[ticker]?.let { (stats, until) -> if (until > now) return stats }
        return load(ticker).also { cache[ticker] = it to now + 60_000 }
    }
}
