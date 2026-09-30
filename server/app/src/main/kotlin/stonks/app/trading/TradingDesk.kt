package stonks.app.trading

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import stonks.engine.core.Side
import stonks.engine.sim.Market
import stonks.engine.trading.AccountEvent
import stonks.engine.trading.EngineEvent
import stonks.engine.trading.FillEvent
import stonks.engine.trading.LegStatus
import stonks.engine.trading.OrderRequest
import stonks.engine.trading.OrderUpdate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** A player action waiting for the simulation thread. */
sealed class Command(val accountId: Long, val arrival: Instant) {
    class OpenAccount(accountId: Long, arrival: Instant, val result: CompletableFuture<Boolean>) : Command(accountId, arrival)
    class Place(accountId: Long, arrival: Instant, val api: PlaceOrderRequest, val request: OrderRequest, val result: CompletableFuture<PlaceOrderResponse>) : Command(accountId, arrival)
    class Cancel(accountId: Long, arrival: Instant, val orderId: Long, val result: CompletableFuture<Boolean>) : Command(accountId, arrival)

    fun fail(e: Throwable) = when (this) {
        is OpenAccount -> result.completeExceptionally(e)
        is Place -> result.completeExceptionally(e)
        is Cancel -> result.completeExceptionally(e)
    }
}

@Serializable data class PlacePayload(val request: PlaceOrderRequest, val executeDay: Int, val executeTick: Int)
@Serializable data class OpenPayload(val cash: Long)
@Serializable data class CancelPayload(val orderId: Long)

/** Where and when a new order will execute. */
data class Schedule(val day: Int, val tick: Int, val executesAt: Instant?)

/**
 * The trading side of the simulation thread (docs/DESIGN.md, "Execution model").
 *
 * Commands arrive from any thread through [submit]; the simulation thread [process]es
 * them: it assigns each order its place in the market-wide intake queue
 * (`ready = max(arrival, previous ready) + p`), logs the input to the database *before*
 * applying it (write-ahead), then applies it to the engine. After a restart the same log
 * is [replay]ed on top of the latest snapshot.
 */
class TradingDesk(
    private val store: TradingStore,
    private val processing: Duration = Duration.ofMillis(10),
    val startingCash: Long = 5_000_00,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TradingDesk::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val inbox = LinkedBlockingQueue<Command>()
    private val buffered = ArrayDeque<Command>()
    private var lastReady: Instant = Instant.EPOCH
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "trading-writer").apply { isDaemon = true } }

    // Views maintained on the simulation thread.
    private val openOrders = HashMap<Long, LinkedHashMap<Long, OrderDto>>()
    private val notices = HashMap<Long, ArrayDeque<NoticeDto>>()
    private var noticeSeq = 0L

    fun submit(cmd: Command) {
        inbox.add(cmd)
    }

    /** Blocks up to [millis] for a command to arrive. */
    fun awaitWork(millis: Long) {
        if (buffered.isNotEmpty() || inbox.isNotEmpty()) return
        inbox.poll(millis, TimeUnit.MILLISECONDS)?.let(buffered::addLast)
    }

    /** Processes queued commands. Returns the accounts they touched. */
    fun process(market: Market, now: Instant, schedule: (ready: Instant) -> Schedule): Set<Long> {
        while (true) buffered.addLast(inbox.poll() ?: break)
        if (buffered.isEmpty()) return emptySet()
        val cmds = buffered.toList()
        buffered.clear()

        val applyTick = if (market.session != null) market.tick else CLOSED
        val accepted = ArrayList<Pair<Command, NewInput>>()
        val opening = HashSet<Long>()
        for (cmd in cmds.sortedBy { it.arrival }) {
            when (cmd) {
                is Command.OpenAccount -> {
                    if (market.ledger.account(cmd.accountId) != null || !opening.add(cmd.accountId)) {
                        cmd.result.complete(false)
                        continue
                    }
                    accepted += cmd to NewInput(market.day, applyTick, "open", cmd.accountId, json.encodeToString(OpenPayload.serializer(), OpenPayload(startingCash)))
                }
                is Command.Place -> {
                    if (market.ledger.account(cmd.accountId) == null && cmd.accountId !in opening) {
                        cmd.fail(RequestError("No trading account."))
                        continue
                    }
                    if (cmd.request.ticker !in market.tickers) {
                        cmd.fail(RequestError("Unknown ticker ${cmd.request.ticker}."))
                        continue
                    }
                    val ready = maxOf(cmd.arrival, lastReady).plus(processing)
                    lastReady = ready
                    val s = schedule(ready)
                    val payload = PlacePayload(cmd.api, s.day, s.tick)
                    accepted += cmd to NewInput(market.day, applyTick, "place", cmd.accountId, json.encodeToString(PlacePayload.serializer(), payload))
                }
                is Command.Cancel -> {
                    val mine = openOrders[cmd.accountId]?.values?.any { it.id == cmd.orderId || it.groupId == cmd.orderId } == true
                    if (!mine) {
                        cmd.result.complete(false)
                        continue
                    }
                    accepted += cmd to NewInput(market.day, applyTick, "cancel", cmd.accountId, json.encodeToString(CancelPayload.serializer(), CancelPayload(cmd.orderId)))
                }
            }
        }
        if (accepted.isEmpty()) return emptySet()

        val seqs = try {
            store.log(accepted.map { it.second })
        } catch (e: Exception) {
            log.error("Failed to log {} inputs", accepted.size, e)
            accepted.forEach { it.first.fail(IllegalStateException("Could not record the request; please retry.")) }
            return emptySet()
        }
        accepted.forEachIndexed { i, (cmd, input) ->
            val seq = seqs[i]
            val reason = apply(market, seq, input.kind, cmd.accountId, input.payload)
            when (cmd) {
                is Command.OpenAccount -> cmd.result.complete(true)
                is Command.Cancel -> cmd.result.complete(reason == null)
                is Command.Place -> if (reason != null) cmd.fail(RequestError(reason)) else {
                    val p = json.decodeFromString(PlacePayload.serializer(), input.payload)
                    val executesAt = estimateFor(p)
                    cmd.result.complete(PlaceOrderResponse(seq, cmd.request.legs.indices.map { seq * 4 + it }, "QUEUED", executesAt))
                }
            }
        }
        return accepted.mapTo(HashSet()) { it.first.accountId }
    }

    /** Re-applies a logged input (after a restart). */
    fun applyLogged(market: Market, input: LoggedInput) {
        apply(market, input.seq, input.kind, input.accountId, input.payload)
    }

    private var estimator: (Int, Int) -> Instant? = { _, _ -> null }
    fun setEstimator(f: (day: Int, tick: Int) -> Instant?) { estimator = f }
    private fun estimateFor(p: PlacePayload): String? = estimator(p.executeDay, p.executeTick)?.toString()

    /**
     * Applies one input to the engine; returns a rejection reason or null. Never
     * throws: a logged input that can't be applied is rejected (and stays rejected on
     * every replay) instead of taking the server down.
     */
    private fun apply(market: Market, seq: Long, kind: String, accountId: Long, payload: String): String? {
        market.lastInputSeq = seq
        return try {
            applyUnchecked(market, seq, kind, accountId, payload)
        } catch (e: Exception) {
            log.error("Input {} ({}) for account {} could not be applied; rejecting it", seq, kind, accountId, e)
            "Request could not be processed."
        }
    }

    private fun applyUnchecked(market: Market, seq: Long, kind: String, accountId: Long, payload: String): String? {
        return when (kind) {
            "open" -> { market.openAccount(accountId, json.decodeFromString(OpenPayload.serializer(), payload).cash); null }
            "place" -> {
                val p = json.decodeFromString(PlacePayload.serializer(), payload)
                val request = try { p.request.toEngine() } catch (e: RequestError) { return e.message }
                market.placeOrder(seq, accountId, request, p.executeDay, p.executeTick)
            }
            "cancel" -> if (market.cancelOrder(accountId, json.decodeFromString(CancelPayload.serializer(), payload).orderId)) null else "Order not found."
            else -> "Unknown input $kind"
        }
    }

    /** Updates views from engine events and queues their persistence. Returns the accounts touched. */
    fun absorb(events: List<EngineEvent>, now: Instant): Set<Long> {
        if (events.isEmpty()) return emptySet()
        val at = now.toString()
        val orderRows = LinkedHashMap<Long, OrderUpdate>()
        val fills = ArrayList<FillEvent>()
        for (e in events) {
            when (e) {
                is OrderUpdate -> {
                    orderRows[e.orderId] = e
                    val open = openOrders.getOrPut(e.accountId) { LinkedHashMap() }
                    if (e.status.done) open.remove(e.orderId) else open[e.orderId] = e.toDto(at)
                    when (e.status) {
                        LegStatus.REJECTED -> notice(e.accountId, "rejected", "${e.side.verb()} ${e.quantity} ${e.ticker} rejected: ${e.reason}", at)
                        LegStatus.CANCELLED -> if (e.reason != null && e.reason != "Cancelled") notice(e.accountId, "cancelled", "${e.side.verb()} ${e.quantity} ${e.ticker}: ${e.reason}", at)
                        LegStatus.EXPIRED -> notice(e.accountId, "expired", "${e.side.verb()} ${e.quantity} ${e.ticker} expired", at)
                        else -> {}
                    }
                }
                is FillEvent -> fills += e
                is AccountEvent -> when (e.kind) {
                    AccountEvent.Kind.MARGIN_CALL -> notice(e.accountId, "margin_call", "Margin call: ${e.detail}", at)
                    AccountEvent.Kind.PLAN_UPGRADED -> notice(e.accountId, "plan", "Plan upgraded to ${e.detail.lowercase().replaceFirstChar { it.uppercase() }}!", at)
                    AccountEvent.Kind.PLAN_LOST -> notice(e.accountId, "plan", "Net worth hit zero: plan reset to Rookie", at)
                    AccountEvent.Kind.DIVIDEND -> notice(e.accountId, "dividend", e.detail, at)
                    AccountEvent.Kind.SPLIT -> notice(e.accountId, "split", e.detail, at)
                    AccountEvent.Kind.DELISTED -> notice(e.accountId, "delisted", e.detail, at)
                    AccountEvent.Kind.OPENED -> {}
                }
            }
        }
        // One notice per order per batch, however many partial fills it took.
        for ((_, group) in fills.groupBy { it.orderId }) {
            val f = group.first()
            val qty = group.sumOf { it.quantity }
            val avg = group.sumOf { it.quantity * it.price } / qty
            val what = if (f.liquidation) "Liquidated" else if (f.side == Side.BUY) "Bought" else "Sold"
            notice(f.accountId, "fill", "$what $qty ${f.ticker} @ ${"%,.2f".format(avg / 100.0)}", at)
        }
        val orderList = orderRows.values.toList()
        writer.execute {
            try {
                store.writeEvents(orderList, fills, now)
            } catch (e: Exception) {
                log.error("Failed to persist {} order updates / {} fills", orderList.size, fills.size, e)
            }
        }
        return events.mapTo(HashSet()) { it.accountId }
    }

    private fun notice(accountId: Long, kind: String, text: String, at: String) {
        val q = notices.getOrPut(accountId) { ArrayDeque() }
        q.addLast(NoticeDto(++noticeSeq, kind, text, at))
        while (q.size > 20) q.removeFirst()
    }

    /** Builds portfolios from the engine (simulation thread): all accounts, or just [only]. */
    fun portfolios(market: Market, only: Set<Long>? = null): Map<Long, PortfolioDto> {
        val prices = market.prices
        val accounts = if (only == null) market.ledger.accounts.values else only.mapNotNull { market.ledger.account(it) }
        return accounts.associate { a ->
            val f = market.ledger.figures(a, prices)
            val available = f.equity - f.initialRequirement - f.reserved
            val positions = a.positions.map { (ticker, p) ->
                val last = prices[ticker] ?: 0
                val value = p.quantity * last
                val unrealized = if (p.quantity > 0) value - p.costBasis else p.costBasis - abs(value)
                val prev = market.tickers[ticker]?.previousClose
                PositionDto(
                    ticker, p.quantity, p.averagePrice, last, value, unrealized,
                    if (p.costBasis == 0L) 0.0 else unrealized * 100.0 / p.costBasis,
                    prev?.let { p.quantity * (last - it) },
                )
            }
            a.id to PortfolioDto(
                accountId = a.id, plan = a.plan.name, cash = a.cash, equity = f.equity,
                longValue = f.longValue, shortValue = f.shortValue, availableEquity = available,
                buyingPower = (maxOf(0, available) / a.plan.initialMargin).toLong(),
                marginDebt = f.marginDebt, maintenanceRequirement = f.maintenanceRequirement,
                initialMargin = a.plan.initialMargin, maintenanceMargin = a.plan.maintenanceMargin,
                lifetimeRealized = a.lifetimeRealized, commissionsPaid = a.totalCommissions, interestPaid = a.totalInterest,
                positions = positions,
                openOrders = openOrders[a.id]?.values?.toList()?.reversed() ?: emptyList(),
                notices = notices[a.id]?.toList()?.reversed() ?: emptyList(),
            )
        }
    }

    fun persistPortfolios(rows: Collection<PortfolioDto>) {
        writer.execute { runCatching { store.writePortfolios(rows) }.onFailure { log.error("Failed to persist portfolios", it) } }
    }

    /** Waits for queued projection writes (tests, shutdown). */
    fun flush() {
        writer.submit {}.get(30, TimeUnit.SECONDS)
    }

    override fun close() {
        runCatching { flush() }
        writer.shutdown()
    }

    companion object {
        /** apply_tick for inputs applied while the market was closed. */
        const val CLOSED = -2

        private fun Side.verb() = if (this == Side.BUY) "Buy" else "Sell"
    }
}
