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
    /** The connected player's portfolio, if they have a trading account. */
    val account: stonks.app.trading.PortfolioDto? = null,
)

/** Sent between ticks when only the player's own account changed. */
@Serializable
data class AccountFrame(val type: String = "account", val account: stonks.app.trading.PortfolioDto)

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
class PriceFeed(
    private val maxSocketsPerAccount: Int,
    private val portfolioOf: (Long) -> stonks.app.trading.PortfolioDto? = { null },
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val byAccount = ConcurrentHashMap<Long, MutableSet<Connection>>()
    private val perAccount = ConcurrentHashMap<Long, AtomicInteger>()
    private val inbound = RateLimiter(10.0, 5.0)
    @Volatile private var latest: MarketState? = null

    private class Connection(val principal: Principal) {
        /** Wake-up signal; the flags say what to send. Conflated: slow clients skip updates. */
        val signal = Channel<Unit>(Channel.CONFLATED)
        @Volatile var marketDirty = false
        @Volatile var accountDirty = false
        @Volatile var quotes: Set<String> = emptySet()
        @Volatile var depth: Set<String> = emptySet()
    }

    val connectionCount: Int get() = connections.size

    /** A market tick: every client gets a frame (with its own account). Never blocks. */
    fun publish(state: MarketState) {
        latest = state
        for (c in connections) {
            c.marketDirty = true
            c.signal.trySend(Unit)
        }
    }

    /** Only these players' accounts changed: notify just their sockets. Never blocks. */
    fun accountsChanged(accountIds: Set<Long>) {
        for (id in accountIds) {
            val conns = byAccount[id] ?: continue
            for (c in conns) {
                c.accountDirty = true
                c.signal.trySend(Unit)
            }
        }
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
        byAccount.computeIfAbsent(principal.accountId) { ConcurrentHashMap.newKeySet() } += conn
        latest = latest ?: current()
        try {
            conn.marketDirty = true
            conn.signal.trySend(Unit)
            val sender = ws.launch {
                for (u in conn.signal) {
                    if (conn.marketDirty) {
                        conn.marketDirty = false
                        conn.accountDirty = false
                        val state = latest ?: continue
                        ws.send(Frame.Text(json.encodeToString(TickFrame.serializer(), frameFor(conn, state))))
                    } else if (conn.accountDirty) {
                        conn.accountDirty = false
                        val account = portfolioOf(conn.principal.accountId) ?: continue
                        ws.send(Frame.Text(json.encodeToString(AccountFrame.serializer(), AccountFrame(account = account))))
                    }
                }
            }
            for (frame in ws.incoming) {
                // Every client message counts against the limit, whatever its type.
                if (inbound.take(principal.sessionId) > 0) {
                    ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many messages"))
                    break
                }
                if (frame !is Frame.Text) {
                    ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Text messages only"))
                    break
                }
                val msg = runCatching { json.decodeFromString(ClientMessage.serializer(), frame.readText()) }.getOrNull()
                when (msg?.op) {
                    "subscribe" -> {
                        conn.quotes = (msg.quotes ?: emptyList()).map { it.uppercase() }.take(MAX_QUOTES).toSet()
                        conn.depth = (msg.depth ?: emptyList()).map { it.uppercase() }.take(MAX_DEPTH).toSet()
                        ws.send(Frame.Text(json.encodeToString(ServerMessage.serializer(), ServerMessage("subscribed", quotes = conn.quotes.toList(), depth = conn.depth.toList()))))
                        // Deliver current data now rather than at the next tick (which may be hours away).
                        latest = current()
                        conn.marketDirty = true
                        conn.signal.trySend(Unit)
                    }
                    "ping" -> ws.send(Frame.Text("""{"type":"pong"}"""))
                    else -> ws.send(Frame.Text(json.encodeToString(ServerMessage.serializer(), ServerMessage("error", "Unknown message"))))
                }
            }
            sender.cancel()
        } finally {
            connections -= conn
            byAccount[principal.accountId]?.remove(conn)
            conn.signal.close()
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
        account = portfolioOf(c.principal.accountId),
    )

    companion object {
        const val MAX_QUOTES = 50
        const val MAX_DEPTH = 3
    }
}
