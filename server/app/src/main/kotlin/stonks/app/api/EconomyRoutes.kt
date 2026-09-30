package stonks.app.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import stonks.app.App
import stonks.app.market.BondOfferingDto
import stonks.app.trading.Command
import stonks.app.trading.EconomyPayload
import stonks.app.trading.StandingDto
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture

@Serializable data class EconomyRequest(val offeringId: Long? = null, val amount: Long? = null)
@Serializable data class EconomyResult(val ok: Boolean, val standing: StandingDto?)
@Serializable data class BondsResponse(val offerings: List<BondOfferingDto>)
@Serializable data class RenameRequest(val displayName: String)
@Serializable data class ProfileBadge(val badge: String, val title: String, val description: String, val count: Int)
@Serializable data class SeasonResultDto(val season: String, val rank: Int?, val returnPct: Double?)
@Serializable
data class PlayerProfile(
    val name: String,
    val joined: String,
    val plan: String?,
    val netWorth: Long?,
    val cashLevel: Int,
    val shame: Int,
    val eternalShame: Boolean,
    val bankruptcies: Int,
    val badges: List<ProfileBadge>,
    val seasons: List<SeasonResultDto>,
)

/** Economy, leaderboards and profiles; installed inside the signed route block. */
fun Route.economyRoutes(app: App) {
    post("/economy/{action}") {
        val p = call.principal
        val action = call.parameters["action"]!!.replace('-', '_')
        if (action !in EconomyPayload.ACTIONS) throw notFound("Unknown action.")
        app.guard.charge(p, 2.0)
        val body = if (call.signedBody.isEmpty()) EconomyRequest() else call.signedJson<EconomyRequest>()
        app.ensureAccount(p.accountId)
        val result = CompletableFuture<String?>()
        app.market.desk.submit(Command.Economy(p.accountId, app.clock.instant(), EconomyPayload(action, 0, body.offeringId, body.amount), result))
        val reason = awaitResult(result)
        if (reason != null) throw badRequest(reason, "economy_refused")
        kotlinx.coroutines.delay(50) // let the updated portfolio publish
        call.respond(EconomyResult(true, app.market.portfolio(p.accountId)?.standing))
    }

    get("/bonds") {
        call.respond(BondsResponse(app.market.state.bondOfferings))
    }

    post("/me/name") {
        val body = call.signedJson<RenameRequest>()
        app.accounts.rename(call.principal.accountId, body.displayName.trim())?.let { throw badRequest(it, "name_invalid") }
        call.respond(mapOf("displayName" to body.displayName.trim()))
    }

    get("/leaderboards/millionaires") {
        call.respond(app.leaderboards.millionaires(call.principal.accountId))
    }

    get("/leaderboards/season") {
        val id = call.request.queryParameters["season"]
        call.respond(app.leaderboards.season(id, call.principal.accountId) ?: throw notFound("Unknown season."))
    }

    get("/players/{name}") {
        app.guard.charge(call.principal)
        val account = app.accounts.byDisplayName(call.parameters["name"]!!) ?: throw notFound("No such player.")
        val port = app.market.portfolio(account.id)
        val st = port?.standing
        call.respond(
            PlayerProfile(
                name = account.displayName,
                joined = account.createdAt.toString(),
                plan = port?.plan,
                netWorth = st?.netWorth,
                cashLevel = st?.cashLevel ?: 0,
                shame = st?.shame ?: 0,
                eternalShame = st?.eternalShame ?: false,
                bankruptcies = st?.bankruptcies ?: 0,
                badges = app.accounts.badges(account.id).map { ProfileBadge(it.badge.name, it.badge.title, it.badge.description, it.count) },
                seasons = app.leaderboards.history(account.id).map { SeasonResultDto(it.first, it.second, it.third) },
            ),
        )
    }
}

private suspend fun <T> awaitResult(f: CompletableFuture<T>): T = try {
    kotlinx.coroutines.withTimeout(10_000) { f.await() }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    throw ApiException(io.ktor.http.HttpStatusCode.ServiceUnavailable, "busy", "The market is busy; please retry.")
}
