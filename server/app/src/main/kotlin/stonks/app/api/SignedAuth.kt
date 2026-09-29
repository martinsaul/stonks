package stonks.app.api

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import stonks.app.auth.Guard
import stonks.app.auth.Principal
import stonks.app.auth.RequestSignature

private val PrincipalKey = AttributeKey<Principal>("stonks.principal")
private val SignedBodyKey = AttributeKey<ByteArray>("stonks.signedBody")
private val bodyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]

val ApplicationCall.clientIp: String get() = request.origin.remoteHost

/**
 * The request body exactly as signed. Signed routes must read their body through this
 * (the stream has already been consumed to verify the signature).
 */
val ApplicationCall.signedBody: ByteArray get() = attributes[SignedBodyKey]

inline fun <reified T> ApplicationCall.signedJson(): T = try {
    signedBodyJson.decodeFromString<T>(signedBody.decodeToString())
} catch (e: kotlinx.serialization.SerializationException) {
    throw badRequest("Malformed request body.")
}

@PublishedApi
internal val signedBodyJson get() = bodyJson

private const val MAX_BODY = 64L * 1024

class SignedAuthConfig {
    lateinit var guard: Guard
}

/**
 * Requires a valid `STONKS-V1` signature on every call in the route it is installed
 * on, then applies rate and concurrency limits. Failures throw [ApiException].
 */
val SignedAuth = createRouteScopedPlugin("SignedAuth", ::SignedAuthConfig) {
    val guard = pluginConfig.guard

    onCall { call ->
        guard.checkIp(call.clientIp)
        val body = if (call.request.httpMethod in listOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Delete)) {
            ByteArray(0)
        } else {
            call.receiveChannel().readRemaining(MAX_BODY + 1).readByteArray()
                .also { if (it.size > MAX_BODY) throw badRequest("Request body too large.") }
        }
        val h = call.request.headers
        val principal = guard.authenticate(
            h[RequestSignature.HEADER_SESSION],
            h[RequestSignature.HEADER_TIMESTAMP],
            h[RequestSignature.HEADER_NONCE],
            h[RequestSignature.HEADER_SIGNATURE],
            call.request.httpMethod.value,
            call.request.uri,
            body,
        )
        guard.charge(principal)
        guard.enter(principal)
        call.attributes.put(SignedBodyKey, body)
        call.attributes.put(PrincipalKey, principal)
    }

    on(ResponseSent) { call ->
        call.attributes.getOrNull(PrincipalKey)?.let(guard::leave)
    }
}

private object SignedRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(signed)"
}

/** Routes declared inside [build] require signed requests; siblings are unaffected. */
fun Route.signed(guard: Guard, build: Route.() -> Unit): Route =
    createChild(SignedRouteSelector).apply {
        install(SignedAuth) { this.guard = guard }
        build()
    }

/**
 * Authenticates a WebSocket upgrade before it happens. Browsers cannot set headers on
 * WebSocket handshakes, so the signature travels in query parameters (`session`, `ts`,
 * `nonce`, `sig`) and covers the canonical path without them.
 */
val SignedHandshake = createRouteScopedPlugin("SignedHandshake", ::SignedAuthConfig) {
    val guard = pluginConfig.guard
    onCall { call ->
        guard.checkIp(call.clientIp)
        val q = call.request.queryParameters
        val principal = guard.authenticate(q["session"], q["ts"], q["nonce"], q["sig"], "GET", call.request.path(), ByteArray(0))
        guard.charge(principal)
        call.attributes.put(PrincipalKey, principal)
    }
}
