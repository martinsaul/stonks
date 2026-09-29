package stonks.engine.book

import stonks.engine.core.Side
import stonks.engine.core.TraderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuctionTest {
    private var id = 1L
    private fun order(side: Side, qty: Long, px: Long?) = Order(
        id++, TraderId.player(id), side,
        if (px == null) OrderType.MARKET else OrderType.LIMIT, qty, px,
    ).also { it.sequence = id }

    @Test
    fun `clears at the single price that maximizes volume`() {
        val orders = listOf(
            order(Side.BUY, 10, 102), order(Side.BUY, 10, 100),
            order(Side.SELL, 10, 99), order(Side.SELL, 10, 101),
        )
        val result = Auction.uncross(orders, referencePrice = 100)!!
        // At 100 or 101 only 10 trade; tie broken by imbalance then proximity to 100.
        assertEquals(10, result.fills.sumOf { it.quantity })
        assertEquals(setOf(result.price), result.fills.map { it.price }.toSet())
    }

    @Test
    fun `market orders participate with priority`() {
        val mkt = order(Side.BUY, 5, null)
        val lim = order(Side.BUY, 5, 100)
        val sell = order(Side.SELL, 5, 100)
        val result = Auction.uncross(listOf(lim, mkt, sell), referencePrice = 100)!!
        assertEquals(100, result.price)
        assertEquals(0, mkt.remaining)
        assertEquals(5, lim.remaining)
    }

    @Test
    fun `no auction without both sides`() {
        assertNull(Auction.uncross(listOf(order(Side.BUY, 5, 100)), 100))
    }
}
