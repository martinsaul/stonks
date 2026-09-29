package stonks.app.ws

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import stonks.app.auth.Principal
import stonks.app.market.Depth
import stonks.app.market.IndexQuote
import stonks.app.market.MarketState
import stonks.app.market.Quote
import stonks.app.market.SessionInfo
import stonks.app.ratelimit.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class ClientMessage(val op: String, val quotes: List<String>? = null, val depth: List<String>? = null)

@Serializable
data class TickFrame(
    val type: String = "tick",
    val time: String,
    val session: SessionInfo,
    val regime: String,
    val index: IndexQuote,
    val quotes: List<Quote>,
    val depth: Map<String, Depth>,
)

@Serializable
data class ServerMessage(val type: String, val message: String? = null, val quotes: List<String>? = null, val depth: List<String>? = null)

/**
 * Pushes market updates to WebSocket clients.
 *
 * Each connection holds only the latest state (a conflated channel), so a slow or
 * greedy client simply skips updates instead of buffering memory or slowing the
 * simulation. Subscriptions and inbound messages are capped per connection, and
 * sockets per account.
 */
class PriceFeed(private val maxSocketsPerAccount: Int) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val perAccount = ConcurrentHashMap<Long, AtomicInteger>()
    private val inbound = RateLimiter(10.0, 5.0)

    private class Connection(val principal: Principal) {
        val outbox = Channel<MarketState>(Channel.CONFLATED)
        @Volatile var quotes: Set<String> = emptySet()
        @Volatile var depth: Set<String> = emptySet()
    }

    val connectionCount: Int get() = connections.size

    /** Called on the simulation thread after each publish; never blocks. */
    fun publish(state: MarketState) {
        for (c in connections) c.outbox.trySend(state)
    }

    suspend fun handle(ws: DefaultWebSocketServerSession, principal: Principal, current: () -> MarketState) {
        val count = perAccount.computeIfAbsent(principal.accountId) { AtomicInteger() }
        if (count.incrementAndGet() > maxSocketsPerAccount) {
            count.decrementAndGet()
            ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections for this account"))
            return
        }
        val conn = Connection(principal)
        connections += conn
        try {
            conn.outbox.trySend(current())
            val sender = ws.launch {
                for (state in conn.outbox) {
                    ws.send(Frame.Text(json.encodeToString(TickFrame.serializer(), frameFor(conn, state))))
                }
            }
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                if (inbound.take(principal.sessionId) > 0) {
                    ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many messages"))
                    break
                }
                val msg = runCatching { json.decodeFromString(ClientMessage.serializer(), frame.readText()) }.getOrNull()
                when (msg?.op) {
                    "subscribe" -> {
                        conn.quotes = (msg.quotes ?: emptyList()).map { it.uppercase() }.take(MAX_QUOTES).toSet()
                        conn.depth = (msg.depth ?: emptyList()).map { it.uppercase() }.take(MAX_DEPTH).toSet()
                        ws.send(Frame.Text(json.encodeToString(ServerMessage.serializer(), ServerMessage("subscribed", quotes = conn.quotes.toList(), depth = conn.depth.toList()))))
                        // Deliver current data now rather than at the next tick (which may be hours away).
                        conn.outbox.trySend(current())
                    }
                    "ping" -> ws.send(Frame.Text("""{"type":"pong"}"""))
                    else -> ws.send(Frame.Text(json.encodeToString(ServerMessage.serializer(), ServerMessage("error", "Unknown message"))))
                }
            }
            sender.cancel()
        } finally {
            connections -= conn
            conn.outbox.close()
            perAccount[principal.accountId]?.decrementAndGet()
        }
    }

    private fun frameFor(c: Connection, s: MarketState) = TickFrame(
        time = s.time.toString(),
        session = s.session,
        regime = s.regime,
        index = s.index,
        quotes = if (c.quotes.isEmpty()) emptyList() else s.quotes.filter { it.ticker in c.quotes },
        depth = if (c.depth.isEmpty()) emptyMap() else s.depth.filterKeys { it in c.depth },
    )

    companion object {
        const val MAX_QUOTES = 50
        const val MAX_DEPTH = 3
    }
}
