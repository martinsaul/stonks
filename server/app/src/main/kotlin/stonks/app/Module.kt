package stonks.app

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import stonks.app.api.ApiException
import stonks.app.api.ErrorBody
import stonks.app.api.authRoutes
import stonks.app.api.SignedHandshake
import stonks.app.api.principal
import stonks.app.api.signedRoutes
import stonks.app.auth.RequestSignature
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("stonks.app")

fun Application.stonksModule(app: App) {
    if (app.config.trustProxy) install(XForwardedHeaders)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        maxFrameSize = 16 * 1024
    }
    val origins = app.config.corsOrigins + if (app.config.devMode) listOf("http://localhost:5173", "http://127.0.0.1:5173") else emptyList()
    if (origins.isNotEmpty()) {
        install(CORS) {
            origins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://"))) }
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.ContentType)
            listOf(RequestSignature.HEADER_SESSION, RequestSignature.HEADER_TIMESTAMP, RequestSignature.HEADER_NONCE, RequestSignature.HEADER_SIGNATURE, stonks.app.admin.ADMIN_KEY_HEADER)
                .forEach(::allowHeader)
            exposeHeader(RequestSignature.HEADER_SERVER_TIME)
            exposeHeader(HttpHeaders.RetryAfter)
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            e.headers.forEach { (k, v) -> call.response.header(k, v) }
            call.respond(e.status, ErrorBody(e.code, e.message))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Malformed request."))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "Something went wrong."))
        }
    }

    routing {
        get("/healthz") { call.respond(mapOf("ok" to true)) }
        authRoutes(app)
        signedRoutes(app)

        route("/api/v1/ws") {
            install(SignedHandshake) { guard = app.guard }
            webSocket { app.feed.handle(this, call.principal) { app.market.state } }
        }
    }
}
