package stonks.app.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import stonks.app.App
import stonks.app.trading.Command
import stonks.app.trading.PlaceOrderRequest
import stonks.app.trading.PlaceOrderResponse
import stonks.app.trading.PortfolioDto
import stonks.app.trading.RequestError
import stonks.app.trading.toEngine
import java.util.concurrent.CompletableFuture

/** Trading endpoints; installed inside the signed route block. */
fun Route.tradingRoutes(app: App) {
    get("/portfolio") {
        call.respond(app.portfolio(call.principal.accountId))
    }

    post("/orders") {
        val p = call.principal
        app.guard.charge(p) // placing costs an extra token
        val body = call.signedJson<PlaceOrderRequest>()
        val request = try { body.toEngine() } catch (e: RequestError) { throw badRequest(e.message!!, "order_invalid") }
        app.ensureAccount(p.accountId)
        val result = CompletableFuture<PlaceOrderResponse>()
        app.market.desk.submit(Command.Place(p.accountId, app.clock.instant(), body, request, result))
        call.respond(HttpStatusCode.Accepted, awaitCommand(result))
    }

    delete("/orders/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw badRequest("Invalid order id.")
        val result = CompletableFuture<Boolean>()
        app.market.desk.submit(Command.Cancel(call.principal.accountId, app.clock.instant(), id, result))
        if (awaitCommand(result)) call.respond(HttpStatusCode.NoContent) else throw notFound("No open order $id.")
    }

    get("/orders") {
        val accountId = call.principal.accountId
        when (call.request.queryParameters["status"] ?: "open") {
            "open" -> call.respond(app.market.state.portfolios[accountId]?.openOrders ?: emptyList())
            "all" -> {
                app.guard.charge(call.principal, 2.0)
                call.respond(app.trading.recentOrders(accountId, 200))
            }
            else -> throw badRequest("status must be 'open' or 'all'.")
        }
    }

    get("/fills") {
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        app.guard.charge(call.principal, limit / 100.0)
        call.respond(app.trading.fills(call.principal.accountId, limit))
    }
}

private suspend fun <T> awaitCommand(f: CompletableFuture<T>): T = try {
    withTimeout(10_000) { f.await() }
} catch (e: RequestError) {
    throw badRequest(e.message!!, "order_rejected")
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    throw ApiException(HttpStatusCode.ServiceUnavailable, "busy", "The market is busy; please retry.")
}

/** Opens the player's trading account (with starting cash) on first use. */
suspend fun App.ensureAccount(accountId: Long) {
    if (market.state.portfolios.containsKey(accountId)) return
    val opened = CompletableFuture<Boolean>()
    market.desk.submit(Command.OpenAccount(accountId, clock.instant(), opened))
    awaitCommand(opened)
}

suspend fun App.portfolio(accountId: Long): PortfolioDto {
    ensureAccount(accountId)
    // The account appears in the next published state (normally within milliseconds).
    repeat(200) {
        market.state.portfolios[accountId]?.let { return it }
        delay(10)
    }
    throw ApiException(HttpStatusCode.ServiceUnavailable, "busy", "The market is busy; please retry.")
}
