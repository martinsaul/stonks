package stonks.engine.book

import stonks.engine.core.Cents
import stonks.engine.core.Side
import stonks.engine.core.TraderId
import java.util.TreeMap

/** Aggregated quantity at one price. */
data class Level(val price: Cents, val quantity: Long)

/**
 * Market-maker liquidity for one side, stored as primitive arrays rather than order
 * objects because it is replaced every tick. Index 0 is the touch.
 */
internal class MakerLadder(capacity: Int) {
    val prices = LongArray(capacity)
    val quantities = LongArray(capacity)
    var size = 0
    var head = 0

    fun bestPrice(): Cents? {
        while (head < size && quantities[head] == 0L) head++
        return if (head < size) prices[head] else null
    }

    fun set(p: LongArray, q: LongArray, n: Int) {
        require(n <= prices.size) { "ladder too deep" }
        System.arraycopy(p, 0, prices, 0, n)
        System.arraycopy(q, 0, quantities, 0, n)
        size = n
        head = 0
    }

    fun clear() {
        size = 0
        head = 0
    }
}

/**
 * Continuous limit order book with price-time priority.
 *
 * Holds resting orders plus a market-maker ladder per side. At equal prices resting
 * orders trade before the maker ladder, which is re-quoted (i.e. newest) every tick.
 * Maker fills carry `makerOrderId = 0`.
 *
 * Not thread-safe: each ticker's book is owned by a single simulation thread.
 */
class OrderBook(makerDepth: Int = 32) {
    private val bids = TreeMap<Cents, ArrayDeque<Order>>(Comparator.reverseOrder())
    private val asks = TreeMap<Cents, ArrayDeque<Order>>()
    private val makerBids = MakerLadder(makerDepth)
    private val makerAsks = MakerLadder(makerDepth)
    private val resting = HashMap<Long, Order>()
    private var nextSequence = 0L

    val bestBid: Cents? get() = better(Side.BUY, bids.firstEntry()?.key, makerBids.bestPrice())
    val bestAsk: Cents? get() = better(Side.SELL, asks.firstEntry()?.key, makerAsks.bestPrice())

    fun mid(): Double? {
        val b = bestBid ?: return null
        val a = bestAsk ?: return null
        return (a + b) / 2.0
    }

    fun restingOrder(id: Long): Order? = resting[id]
    fun restingOrders(): Collection<Order> = resting.values

    /**
     * Matches [order] against the opposite side, then rests any remainder if it is a
     * LIMIT order that is not IOC. Returns the fills in execution order.
     */
    fun submit(order: Order): List<Fill> {
        order.sequence = nextSequence++
        val fills = ArrayList<Fill>(2)
        val book = if (order.side == Side.BUY) asks else bids
        val maker = if (order.side == Side.BUY) makerAsks else makerBids

        while (order.remaining > 0) {
            val entry = book.firstEntry()
            val makerPx = maker.bestPrice()
            val px = better(order.side.opposite, entry?.key, makerPx) ?: break
            if (!order.accepts(px)) break

            if (entry != null && entry.key == px) {
                val queue = entry.value
                while (order.remaining > 0 && queue.isNotEmpty()) {
                    val m = queue.first()
                    val qty = minOf(order.remaining, m.remaining)
                    order.remaining -= qty
                    m.remaining -= qty
                    fills += Fill(px, qty, order.side, order.id, order.trader, m.id, m.trader)
                    if (m.isDone) {
                        queue.removeFirst()
                        resting.remove(m.id)
                    }
                }
                if (queue.isEmpty()) book.remove(px)
            } else {
                val qty = minOf(order.remaining, maker.quantities[maker.head])
                order.remaining -= qty
                maker.quantities[maker.head] -= qty
                fills += Fill(px, qty, order.side, order.id, order.trader, 0, TraderId.MARKET_MAKER)
            }
        }

        if (order.remaining > 0 && order.type == OrderType.LIMIT && order.tif != TimeInForce.IOC) {
            rest(order)
        }
        return fills
    }

    private fun rest(order: Order) {
        val price = requireNotNull(order.limitPrice)
        val side = if (order.side == Side.BUY) bids else asks
        side.getOrPut(price) { ArrayDeque() }.addLast(order)
        resting[order.id] = order
    }

    /**
     * Replaces the maker ladder on [side] (touch first). Resting orders that the new
     * quotes cross are filled at their own resting price, with the maker as taker.
     */
    fun setMakerLadder(side: Side, prices: LongArray, quantities: LongArray, levels: Int): List<Fill> {
        val ladder = if (side == Side.BUY) makerBids else makerAsks
        ladder.set(prices, quantities, levels)
        val opposite = if (side == Side.BUY) asks else bids
        var fills: MutableList<Fill>? = null
        while (true) {
            val makerPx = ladder.bestPrice() ?: break
            val entry = opposite.firstEntry() ?: break
            val crosses = if (side == Side.BUY) entry.key <= makerPx else entry.key >= makerPx
            if (!crosses) break
            val m = entry.value.first()
            val qty = minOf(m.remaining, ladder.quantities[ladder.head])
            m.remaining -= qty
            ladder.quantities[ladder.head] -= qty
            if (fills == null) fills = ArrayList(2)
            fills += Fill(entry.key, qty, side, 0, TraderId.MARKET_MAKER, m.id, m.trader)
            if (m.isDone) {
                entry.value.removeFirst()
                resting.remove(m.id)
                if (entry.value.isEmpty()) opposite.remove(entry.key)
            }
        }
        return fills ?: emptyList()
    }

    fun clearMakerLadders() {
        makerBids.clear()
        makerAsks.clear()
    }

    fun cancel(orderId: Long): Order? {
        val order = resting.remove(orderId) ?: return null
        val side = if (order.side == Side.BUY) bids else asks
        val price = order.limitPrice!!
        side[price]?.let { q ->
            q.remove(order)
            if (q.isEmpty()) side.remove(price)
        }
        return order
    }

    /** Cancels every resting order matching [predicate]; returns the cancelled orders. */
    fun cancelWhere(predicate: (Order) -> Boolean): List<Order> {
        val doomed = resting.values.filter(predicate)
        doomed.forEach { cancel(it.id) }
        return doomed
    }

    fun cancelAllOf(trader: TraderId): List<Order> = cancelWhere { it.trader == trader }

    /** Aggregated depth on [side], merging resting orders and maker liquidity. */
    fun depth(side: Side, levels: Int): List<Level> {
        val book = if (side == Side.BUY) bids else asks
        val maker = if (side == Side.BUY) makerBids else makerAsks
        val merged = TreeMap<Cents, Long>(if (side == Side.BUY) Comparator.reverseOrder() else Comparator.naturalOrder())
        for ((p, q) in book.entries.asSequence().take(levels)) merged.merge(p, q.sumOf { it.remaining }, Long::plus)
        for (i in maker.head until maker.size) {
            if (maker.quantities[i] > 0) merged.merge(maker.prices[i], maker.quantities[i], Long::plus)
        }
        return merged.entries.asSequence().take(levels).map { Level(it.key, it.value) }.toList()
    }

    /** The better of two prices for a resting [side]: highest bid or lowest ask. */
    private fun better(side: Side, a: Cents?, b: Cents?): Cents? = when {
        a == null -> b
        b == null -> a
        side == Side.BUY -> maxOf(a, b)
        else -> minOf(a, b)
    }
}
