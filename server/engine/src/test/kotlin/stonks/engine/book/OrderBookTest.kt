package stonks.engine.book

import stonks.engine.core.Side
import stonks.engine.core.TraderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderBookTest {
    private var id = 1L
    private val alice = TraderId.player(1)
    private val bob = TraderId.player(2)

    private fun limit(t: TraderId, side: Side, qty: Long, px: Long, tif: TimeInForce = TimeInForce.DAY) =
        Order(id++, t, side, OrderType.LIMIT, qty, px, tif)

    private fun market(t: TraderId, side: Side, qty: Long) = Order(id++, t, side, OrderType.MARKET, qty)

    @Test
    fun `price then time priority`() {
        val book = OrderBook()
        val first = limit(alice, Side.SELL, 10, 101)
        val second = limit(bob, Side.SELL, 10, 101)
        val better = limit(bob, Side.SELL, 5, 100)
        listOf(first, second, better).forEach { book.submit(it) }

        val fills = book.submit(market(TraderId.player(3), Side.BUY, 12))

        assertEquals(listOf(100L to 5L, 101L to 7L), fills.map { it.price to it.quantity })
        assertEquals(listOf(better.id, first.id), fills.map { it.makerOrderId })
        assertEquals(3, first.remaining)
        assertEquals(10, second.remaining)
    }

    @Test
    fun `limit order rests remainder and respects its price`() {
        val book = OrderBook()
        book.submit(limit(alice, Side.SELL, 5, 100))
        book.submit(limit(alice, Side.SELL, 5, 105))
        val buy = limit(bob, Side.BUY, 8, 102)

        val fills = book.submit(buy)

        assertEquals(5, fills.sumOf { it.quantity })
        assertEquals(3, buy.remaining)
        assertEquals(102, book.bestBid)
        assertEquals(105, book.bestAsk)
    }

    @Test
    fun `IOC and market remainders never rest`() {
        val book = OrderBook()
        book.submit(limit(alice, Side.SELL, 5, 100))
        val ioc = limit(bob, Side.BUY, 10, 100, TimeInForce.IOC)
        book.submit(ioc)
        book.submit(market(bob, Side.BUY, 10))

        assertEquals(5, ioc.remaining)
        assertNull(book.bestBid)
        assertTrue(book.restingOrders().isEmpty())
    }

    @Test
    fun `cancel removes the order`() {
        val book = OrderBook()
        val o = limit(alice, Side.BUY, 5, 99)
        book.submit(o)
        assertEquals(o, book.cancel(o.id))
        assertNull(book.bestBid)
        assertNull(book.cancel(o.id))
    }

    @Test
    fun `resting orders trade before maker liquidity at the same price`() {
        val book = OrderBook()
        book.setMakerLadder(Side.SELL, longArrayOf(100, 101), longArrayOf(10, 10), 2)
        val player = limit(alice, Side.SELL, 4, 100)
        book.submit(player)

        val fills = book.submit(market(bob, Side.BUY, 20))

        assertEquals(listOf(100L to 4L, 100L to 10L, 101L to 6L), fills.map { it.price to it.quantity })
        assertEquals(alice, fills[0].makerTrader)
        assertEquals(TraderId.MARKET_MAKER, fills[1].makerTrader)
        assertEquals(101, book.bestAsk)
    }

    @Test
    fun `maker quotes that cross resting orders fill at the resting price`() {
        val book = OrderBook()
        val bid = limit(alice, Side.BUY, 5, 105)
        book.submit(bid)

        val fills = book.setMakerLadder(Side.SELL, longArrayOf(100, 101), longArrayOf(3, 10), 2)

        assertEquals(listOf(105L to 3L, 105L to 2L), fills.map { it.price to it.quantity })
        assertTrue(bid.isDone)
        assertEquals(101, book.bestAsk)
    }

    @Test
    fun `depth merges resting orders and maker ladder`() {
        val book = OrderBook()
        book.setMakerLadder(Side.BUY, longArrayOf(99, 98), longArrayOf(10, 20), 2)
        book.submit(limit(alice, Side.BUY, 5, 99))
        book.submit(limit(alice, Side.BUY, 5, 97))

        assertEquals(listOf(Level(99, 15), Level(98, 20), Level(97, 5)), book.depth(Side.BUY, 5))
    }
}
