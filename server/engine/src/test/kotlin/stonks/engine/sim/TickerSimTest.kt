package stonks.engine.sim

import stonks.engine.book.Order
import stonks.engine.book.OrderType
import stonks.engine.book.TimeInForce
import stonks.engine.core.Side
import stonks.engine.core.TraderId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TickerSimTest {
    private val config = MarketConfig()

    private fun open(sim: TickerSim, ticks: Int = 5400) =
        sim.beginSession(Fixtures.session(ticks = ticks), Fixtures.factors(ticks), MarketRegime.NEUTRAL, 0.25)

    @Test
    fun `player impact is permanent and never reverted`() {
        val control = TickerSim(Fixtures.company(), config, seed = 11)
        val whale = TickerSim(Fixtures.company(), config, seed = 11)
        open(control); open(whale)
        repeat(100) { control.step(); whale.step() }

        // ~$4M market buy into a $100B company: sweeps most of the maker ladder.
        val shares = 4_000_000_00L / whale.last
        whale.submit(Order(1, TraderId.player(1), Side.BUY, OrderType.MARKET, shares))
        control.step(); whale.step()
        val ratioAfterImpact = whale.reference / control.reference
        assertTrue(ratioAfterImpact > 1.003, "impact should move the price, got $ratioAfterImpact")

        repeat(3000) { control.step(); whale.step() }
        val ratioLater = whale.reference / control.reference
        assertTrue(abs(ratioLater / ratioAfterImpact - 1) < 0.002, "impact decayed: $ratioAfterImpact -> $ratioLater")
    }

    @Test
    fun `a sweep's last trade stays near the new quotes`() {
        val sim = TickerSim(Fixtures.company(), config, seed = 5)
        open(sim)
        repeat(10) { sim.step() }
        sim.submit(Order(1, TraderId.player(1), Side.BUY, OrderType.MARKET, 2_000_000_00L / sim.last))
        val sweepLast = sim.step().last
        val next = sim.step()
        // No snap-back: the next quotes are centred around where the sweep ended.
        assertTrue(abs(next.ask!! - sweepLast) <= sweepLast * 0.002, "ask ${next.ask} vs sweep $sweepLast")
    }

    @Test
    fun `resting player limit orders fill when the market reaches them`() {
        val sim = TickerSim(Fixtures.company(), config, seed = 5)
        open(sim)
        sim.step()
        val bid = Order(1, TraderId.player(1), Side.BUY, OrderType.LIMIT, 10, sim.book.bestBid!!, TimeInForce.DAY)
        sim.submit(bid)
        var filled = false
        repeat(2000) { if (!filled) filled = sim.step().fills.any { it.makerOrderId == 1L || it.takerOrderId == 1L } }
        assertTrue(filled || bid.isDone, "bid at the touch should fill within a session")
    }

    @Test
    fun `day orders expire at session end`() {
        val sim = TickerSim(Fixtures.company(), config, seed = 5)
        open(sim, ticks = 10)
        sim.submit(Order(1, TraderId.player(1), Side.BUY, OrderType.LIMIT, 10, 1, TimeInForce.DAY))
        sim.submit(Order(2, TraderId.player(1), Side.BUY, OrderType.LIMIT, 10, 1, TimeInForce.GTC))
        repeat(10) { sim.step() }
        sim.endSession()
        assertEquals(listOf(2L), sim.book.restingOrders().map { it.id })
    }

    @Test
    fun `orders placed while closed cross in the opening auction`() {
        val sim = TickerSim(Fixtures.company(), config, seed = 5)
        open(sim, ticks = 10)
        repeat(10) { sim.step() }
        sim.endSession()

        val buy = Order(1, TraderId.player(1), Side.BUY, OrderType.MARKET, 100)
        sim.submit(buy)
        val fills = sim.beginSession(Fixtures.session(open = Fixtures.session().close.plusSeconds(3600), ticks = 10),
            Fixtures.factors(10), MarketRegime.NEUTRAL, 0.25)

        assertTrue(buy.isDone)
        assertEquals(1, fills.map { it.price }.toSet().size, "auction fills share one price")
    }

    @Test
    fun `volume and candles are produced`() {
        val sim = TickerSim(Fixtures.company(), config, seed = 9)
        open(sim)
        repeat(5400) { sim.step() }
        sim.endSession()
        val day = sim.candles.days.candles.single()
        assertTrue(day.volume > 0)
        assertTrue(day.low <= day.open && day.open <= day.high)
        assertEquals(5400, sim.candles.ticks.candles.size)
        assertEquals(450, sim.candles.minutes.candles.size)
    }
}
