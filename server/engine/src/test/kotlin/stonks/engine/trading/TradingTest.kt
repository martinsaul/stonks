package stonks.engine.trading

import stonks.engine.book.TimeInForce
import stonks.engine.core.Side
import stonks.engine.sim.Fixtures
import stonks.engine.sim.Market
import stonks.engine.sim.MarketConfig
import stonks.engine.strategy.StrategyType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A market with two tickers and an open session, driven tick by tick. */
private class Harness(config: MarketConfig = MarketConfig()) {
    val market = Market(
        listOf(
            Fixtures.company(StrategyType.STAGNANT, ticker = "AAA"),
            Fixtures.company(StrategyType.STAGNANT, ticker = "BBB"),
        ),
        config, seed = 7,
    )
    val events = ArrayList<EngineEvent>()
    private var nextGroup = 1L

    init {
        market.beginSession(Fixtures.session(ticks = 5400))
        step(2)
    }

    fun account(id: Long, cash: Long = 5_000_00) = market.openAccount(id, cash)

    fun place(account: Long, vararg legs: LegSpec, ticker: String = "AAA", structure: Structure = Structure.SINGLE, delay: Int = 0): Long {
        val g = nextGroup++
        assertNull(market.placeOrder(g, account, OrderRequest(ticker, structure, legs.toList()), market.day, market.tick + delay))
        drain()
        return g
    }

    fun step(n: Int = 1) = repeat(n) { market.step(); drain() }
    fun drain() { events += market.drainEvents() }

    fun ask(t: String = "AAA") = market.tickers.getValue(t).book.bestAsk!!
    fun bid(t: String = "AAA") = market.tickers.getValue(t).book.bestBid!!
    fun last(t: String = "AAA") = market.tickers.getValue(t).last
    fun acct(id: Long) = market.ledger.account(id)!!
    fun status(legId: Long) = events.filterIsInstance<OrderUpdate>().last { it.orderId == legId }.status
    fun update(legId: Long) = events.filterIsInstance<OrderUpdate>().last { it.orderId == legId }
    fun fills(legId: Long) = events.filterIsInstance<FillEvent>().filter { it.orderId == legId }

    /** A whale pushes the price down by selling short every tick. */
    fun dump(whale: Long, ticks: Int, ticker: String = "AAA") {
        repeat(ticks) {
            place(whale, LegSpec(Side.SELL, 200_000, OrderKind.MARKET), ticker = ticker)
            step()
        }
    }
}

private fun leg(id: Long, index: Int = 0) = id * 4 + index

class CommissionTest {
    @Test
    fun `plans charge as designed`() {
        assertEquals(495, Plan.ROOKIE.commission(100, 5000, firstFill = true, 0))
        assertEquals(0, Plan.ROOKIE.commission(100, 5000, firstFill = false, 5000))
        assertEquals(100, Plan.TRADER.commission(10, 5000, firstFill = true, 0)) // $1 minimum
        assertEquals(1000, Plan.TRADER.commission(1000, 5000, firstFill = true, 0)) // 1c/share
        assertEquals(1, Plan.TRADER.commission(1, 50, firstFill = true, 0)) // capped at 1% of $0.50
        assertEquals(500, Plan.PRO.commission(1000, 5000, firstFill = true, 0))
        // Whale: $500k order: 0.35c x 10k shares = $35, plus 0.1% of the $250k above the threshold = $250.
        assertEquals(35_00 + 250_00, Plan.WHALE.commission(10_000, 50_00, firstFill = true, 0))
    }

    @Test
    fun `plans unlock by lifetime realized profit`() {
        assertEquals(Plan.ROOKIE, Plan.forProfit(24_999_99))
        assertEquals(Plan.TRADER, Plan.forProfit(25_000_00))
        assertEquals(Plan.WHALE, Plan.forProfit(2_000_000_00))
    }
}

class LedgerTest {
    @Test
    fun `long and short round trips realize profit`() {
        val l = Ledger()
        val a = l.open(1, 10_000_00)
        l.applyFill(a, "X", Side.BUY, 100, 50_00, 0)
        assertEquals(10_000_00 - 5_000_00, a.cash)
        val r = l.applyFill(a, "X", Side.SELL, 100, 55_00, 0)
        assertEquals(500_00, r)
        assertTrue(a.positions.isEmpty())

        l.applyFill(a, "Y", Side.SELL, 10, 100_00, 0) // short
        assertEquals(-10, a.quantity("Y"))
        assertEquals(10, l.shortInterest["Y"])
        assertEquals(200_00, l.applyFill(a, "Y", Side.BUY, 10, 80_00, 0))
        assertEquals(0, l.shortInterest["Y"])
        assertEquals(10_000_00 + 700_00, a.cash)
    }

    @Test
    fun `selling through a long opens a short`() {
        val l = Ledger()
        val a = l.open(1, 10_000_00)
        l.applyFill(a, "X", Side.BUY, 10, 10_00, 0)
        l.applyFill(a, "X", Side.SELL, 25, 12_00, 0)
        assertEquals(-15, a.quantity("X"))
        assertEquals(15 * 12_00, a.positions.getValue("X").costBasis)
    }

    @Test
    fun `margin figures follow the plan`() {
        val l = Ledger()
        val a = l.open(1, 5_000_00)
        l.applyFill(a, "X", Side.BUY, 100, 100_00, 0) // $10k at 2x
        val f = l.figures(a, mapOf("X" to 100_00))
        assertEquals(5_000_00, f.equity)
        assertEquals(5_000_00, f.initialRequirement) // 50%
        assertEquals(3_000_00, f.maintenanceRequirement) // 30%
        assertEquals(-5_000_00, f.cash)
    }

    @Test
    fun `interest accrues on borrowed cash`() {
        val l = Ledger()
        val a = l.open(1, 0)
        a.cash = -25_200_00
        l.accrueDaily(emptyMap(), benchmarkRate = 0.04, borrowRate = { 0.0 })
        // $25,200 at 8% for one of 252 days = $8.
        assertEquals(-25_208_00, a.cash)
    }

    @Test
    fun `borrow fee rises with pool utilization`() {
        assertEquals(0.005, Ledger.borrowRate(0.2))
        assertTrue(Ledger.borrowRate(0.7) in 0.05..0.08)
        assertTrue(Ledger.borrowRate(0.99) > 0.8)
    }
}

class OrderExecutionTest {
    @Test
    fun `orders wait for their execution tick`() {
        val h = Harness()
        h.account(1)
        val g = h.place(1, LegSpec(Side.BUY, 10, OrderKind.MARKET), delay = 3)
        h.step(2)
        assertEquals(LegStatus.QUEUED, h.status(leg(g)))
        h.step(2)
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
    }

    @Test
    fun `marketable limits get price improvement`() {
        val h = Harness()
        h.account(1)
        val ask = h.ask()
        val g = h.place(1, LegSpec(Side.BUY, 5, OrderKind.LIMIT, limit = ask + 200))
        h.step()
        val fill = h.fills(leg(g)).single()
        assertTrue(fill.price < ask + 200, "filled at ${fill.price}, limit ${ask + 200}")
        assertEquals(5, h.acct(1).quantity("AAA"))
        assertEquals(5_000_00 - 5 * fill.price - 495, h.acct(1).cash)
    }

    @Test
    fun `out-of-range limits rest and fill when the price comes back`() {
        val h = Harness(MarketConfig(liquidityFraction = 1e-8))
        h.account(1)
        h.account(9, 10_000_000_00)
        val limit = h.bid() - 300
        val g = h.place(1, LegSpec(Side.BUY, 5, OrderKind.LIMIT, limit = limit, tif = TimeInForce.GTC))
        h.step()
        assertEquals(LegStatus.WORKING, h.status(leg(g)))
        assertTrue(h.market.tickers.getValue("AAA").book.depth(Side.BUY, 50).any { it.price == limit })
        assertTrue(h.acct(1).reservations.isNotEmpty())

        var n = 0
        while (h.status(leg(g)) != LegStatus.FILLED && n++ < 100) h.dump(9, 1)
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
        assertTrue(h.fills(leg(g)).all { it.price == limit && it.maker })
        assertTrue(h.acct(1).reservations.isEmpty())
    }

    @Test
    fun `same price fills first come first served`() {
        val h = Harness(MarketConfig(liquidityFraction = 1e-8))
        h.account(1); h.account(2); h.account(9, 10_000_000_00)
        val price = h.bid() - 200
        val first = h.place(1, LegSpec(Side.BUY, 3, OrderKind.LIMIT, limit = price))
        val second = h.place(2, LegSpec(Side.BUY, 3, OrderKind.LIMIT, limit = price))
        h.step()
        var n = 0
        while (h.fills(leg(first)).isEmpty() && n++ < 100) h.dump(9, 1)
        val firstFillTick = h.fills(leg(first)).first().let { it.day * 100_000 + it.tick }
        val secondFillTick = h.fills(leg(second)).firstOrNull()?.let { it.day * 100_000 + it.tick } ?: Int.MAX_VALUE
        assertTrue(firstFillTick <= secondFillTick)
        if (secondFillTick == firstFillTick) assertEquals(3, h.fills(leg(first)).sumOf { it.quantity })
    }

    @Test
    fun `orders beyond buying power are rejected`() {
        val h = Harness()
        h.account(1)
        val qty = 2 * 5_000_00 / h.ask() + 5 // beyond 2x leverage
        val g = h.place(1, LegSpec(Side.BUY, qty, OrderKind.MARKET))
        h.step()
        assertEquals(LegStatus.REJECTED, h.status(leg(g)))
        assertTrue(h.update(leg(g)).reason!!.startsWith("Insufficient buying power"))
        assertTrue(h.acct(1).positions.isEmpty())
    }

    @Test
    fun `short sales need borrowable shares`() {
        val h = Harness(MarketConfig(borrowPoolFraction = 1e-8)) // 10 shares in the pool
        h.account(1)
        val ok = h.place(1, LegSpec(Side.SELL, 8, OrderKind.MARKET))
        h.step()
        assertEquals(LegStatus.FILLED, h.status(leg(ok)))
        assertEquals(-8, h.acct(1).quantity("AAA"))
        val tooMany = h.place(1, LegSpec(Side.SELL, 5, OrderKind.MARKET))
        h.step()
        assertEquals(LegStatus.REJECTED, h.status(leg(tooMany)))
    }
}

class ConditionalOrderTest {
    @Test
    fun `stop loss triggers when the price falls through`() {
        val h = Harness(MarketConfig(liquidityFraction = 1e-8))
        h.account(1); h.account(9, 10_000_000_00)
        h.place(1, LegSpec(Side.BUY, 10, OrderKind.MARKET))
        h.step()
        val stop = h.last() - 100
        val g = h.place(1, LegSpec(Side.SELL, 10, OrderKind.STOP, stop = stop, tif = TimeInForce.GTC))
        h.step()
        assertEquals(LegStatus.ARMED, h.status(leg(g)))
        var n = 0
        while (h.status(leg(g)) != LegStatus.FILLED && n++ < 100) h.dump(9, 1)
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
        assertEquals(0, h.acct(1).quantity("AAA"))
        assertTrue(h.fills(leg(g)).first().price <= stop)
    }

    @Test
    fun `trailing stops follow the price`() {
        val h = Harness(MarketConfig(liquidityFraction = 1e-8))
        h.account(1); h.account(9, 10_000_000_00)
        h.place(1, LegSpec(Side.BUY, 10, OrderKind.MARKET))
        h.step()
        val g = h.place(1, LegSpec(Side.SELL, 10, OrderKind.TRAILING_STOP, trailPercent = 1.0, tif = TimeInForce.GTC))
        h.step(2)
        val initialStop = h.update(leg(g)).stop!!
        assertTrue(initialStop < h.last())
        var n = 0
        while (h.status(leg(g)) != LegStatus.FILLED && n++ < 200) h.dump(9, 1)
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
    }

    @Test
    fun `OCO cancels the other leg on the first fill`() {
        val h = Harness()
        h.account(1)
        val g = h.place(
            1,
            LegSpec(Side.BUY, 5, OrderKind.LIMIT, limit = h.ask() + 100),
            LegSpec(Side.BUY, 5, OrderKind.LIMIT, limit = h.bid() - 1000),
            structure = Structure.OCO,
        )
        h.step()
        assertEquals(LegStatus.FILLED, h.status(leg(g, 0)))
        assertEquals(LegStatus.CANCELLED, h.status(leg(g, 1)))
        assertEquals(5, h.acct(1).quantity("AAA"))
    }

    @Test
    fun `bracket exits activate after the entry and cancel each other`() {
        val h = Harness()
        h.account(1)
        val g = h.place(
            1,
            LegSpec(Side.BUY, 5, OrderKind.MARKET),
            LegSpec(Side.SELL, 5, OrderKind.LIMIT, limit = h.bid() - 50, tif = TimeInForce.GTC), // take-profit (marketable for the test)
            LegSpec(Side.SELL, 5, OrderKind.STOP, stop = 1, tif = TimeInForce.GTC),
            structure = Structure.BRACKET,
        )
        assertEquals(LegStatus.WAITING, h.status(leg(g, 1)))
        h.step()
        assertEquals(LegStatus.FILLED, h.status(leg(g, 0)))
        assertEquals(LegStatus.QUEUED, h.status(leg(g, 1)))
        h.step(2)
        assertEquals(LegStatus.FILLED, h.status(leg(g, 1)))
        assertEquals(LegStatus.CANCELLED, h.status(leg(g, 2)))
        assertEquals(0, h.acct(1).quantity("AAA"))
    }

    @Test
    fun `TWAP spreads execution over its window`() {
        val h = Harness()
        h.account(1, 1_000_000_00)
        val g = h.place(1, LegSpec(Side.BUY, 1200, OrderKind.TWAP, durationTicks = 12))
        h.step(14)
        val fills = h.fills(leg(g))
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
        assertEquals(1200, fills.sumOf { it.quantity })
        assertTrue(fills.map { it.tick }.distinct().size >= 10, "should trade on most ticks")
    }

    @Test
    fun `day orders expire at the close and GTC orders carry over`() {
        val h = Harness()
        h.account(1)
        val day = h.place(1, LegSpec(Side.BUY, 1, OrderKind.LIMIT, limit = 100))
        val gtc = h.place(1, LegSpec(Side.BUY, 1, OrderKind.LIMIT, limit = 100, tif = TimeInForce.GTC))
        h.step()
        while (h.market.tick < 5400) h.step()
        h.market.endSession(); h.drain()
        assertEquals(LegStatus.EXPIRED, h.status(leg(day)))
        assertEquals(LegStatus.WORKING, h.status(leg(gtc)))
        assertNotNull(h.market.tickers.getValue("AAA").book.restingOrder(leg(gtc)))
    }
}

class RiskTest {
    @Test
    fun `leveraged longs are liquidated below maintenance`() {
        val h = Harness(MarketConfig(liquidityFraction = 1e-8))
        h.account(1); h.account(9, 100_000_000_00)
        val qty = (2 * 5_000_00 - 1_000) / h.ask() // close to 2x
        h.place(1, LegSpec(Side.BUY, qty, OrderKind.MARKET))
        h.step()
        assertEquals(qty, h.acct(1).quantity("AAA"))
        var n = 0
        while (h.events.none { it is AccountEvent && it.kind == AccountEvent.Kind.MARGIN_CALL } && n++ < 400) h.dump(9, 1)
        h.step(3)
        assertTrue(h.events.any { it is AccountEvent && it.kind == AccountEvent.Kind.MARGIN_CALL && it.accountId == 1L })
        assertTrue(h.acct(1).quantity("AAA") < qty)
        assertTrue(h.events.filterIsInstance<FillEvent>().any { it.accountId == 1L && it.liquidation })
    }

    @Test
    fun `snapshots keep accounts and working orders`() {
        val h = Harness()
        h.account(1)
        h.place(1, LegSpec(Side.BUY, 3, OrderKind.MARKET))
        val g = h.place(1, LegSpec(Side.BUY, 1, OrderKind.LIMIT, limit = 100, tif = TimeInForce.GTC))
        h.step(2)
        while (h.market.tick < 5400) h.step()
        h.market.endSession()
        val bytes = ByteArrayOutputStream().also { h.market.writeSnapshot(DataOutputStream(it)) }.toByteArray()
        val restored = Market.readSnapshot(DataInputStream(ByteArrayInputStream(bytes)), h.market.config)
        val a = restored.ledger.account(1)!!
        assertEquals(h.acct(1).cash, a.cash)
        assertEquals(3, a.quantity("AAA"))
        assertEquals(h.acct(1).reservations, a.reservations)
        assertNotNull(restored.tickers.getValue("AAA").playerOrders.legs[leg(g)])
        assertNotNull(restored.tickers.getValue("AAA").book.restingOrder(leg(g)))
        assertEquals(1, restored.day)
    }
}

class MoreOrderTypesTest {
    @Test
    fun `orders placed while closed trade in the opening auction`() {
        val h = Harness()
        h.account(1)
        while (h.market.tick < 5400) h.step()
        h.market.endSession(); h.drain()
        val g = h.place(1, LegSpec(Side.BUY, 10, OrderKind.MARKET), delay = 0).also { }
        // Closed market: tick index is the last session's; schedule for the next auction.
        h.market.cancelOrder(1, g)
        val g2 = 99L
        assertNull(h.market.placeOrder(g2, 1, OrderRequest("AAA", Structure.SINGLE, listOf(LegSpec(Side.BUY, 10, OrderKind.MARKET))), h.market.day, -1))
        val open = Fixtures.session().close.plusSeconds(3600)
        h.market.beginSession(stonks.engine.clock.Session(stonks.engine.clock.SessionKind.WEEKDAY_B, open, open.plusSeconds(5400 * 5)))
        h.drain()
        assertEquals(LegStatus.FILLED, h.status(leg(g2)))
        assertEquals(-1, h.fills(leg(g2)).first().tick)
    }

    @Test
    fun `IOC limits never rest`() {
        val h = Harness()
        h.account(1)
        val g = h.place(1, LegSpec(Side.BUY, 5, OrderKind.LIMIT, limit = h.bid() - 500, tif = TimeInForce.IOC))
        h.step()
        assertEquals(LegStatus.CANCELLED, h.status(leg(g)))
        assertTrue(h.acct(1).reservations.isEmpty())
    }

    @Test
    fun `stop-limit becomes a limit when triggered`() {
        val h = Harness()
        h.account(1)
        // Buy stop below the market triggers immediately; its limit is far below, so it rests.
        val stop = h.last() - 1000
        val limit = h.last() - 2000
        val g = h.place(1, LegSpec(Side.BUY, 5, OrderKind.STOP_LIMIT, stop = stop, limit = limit, tif = TimeInForce.GTC))
        h.step()
        // Buy stops trigger at or above the stop: the last price is above, so it arms and
        // triggers on the same tick, then executes (and rests at its limit) on the next.
        assertTrue(h.events.filterIsInstance<OrderUpdate>().any { it.orderId == leg(g) && it.status == LegStatus.ARMED })
        assertEquals(LegStatus.QUEUED, h.status(leg(g)))
        h.step()
        assertEquals(LegStatus.WORKING, h.status(leg(g)))
        assertEquals(limit, h.update(leg(g)).limit)
    }

    @Test
    fun `OTO children activate after the parent fills`() {
        val h = Harness()
        h.account(1)
        val g = h.place(
            1,
            LegSpec(Side.BUY, 4, OrderKind.MARKET),
            LegSpec(Side.SELL, 4, OrderKind.LIMIT, limit = h.ask() + 5000, tif = TimeInForce.GTC),
            structure = Structure.OTO,
        )
        assertEquals(LegStatus.WAITING, h.status(leg(g, 1)))
        h.step(3)
        assertEquals(LegStatus.FILLED, h.status(leg(g, 0)))
        assertEquals(LegStatus.WORKING, h.status(leg(g, 1)))
    }

    @Test
    fun `VWAP completes its quantity`() {
        val h = Harness()
        h.account(1, 1_000_000_00)
        val g = h.place(1, LegSpec(Side.BUY, 600, OrderKind.VWAP, durationTicks = 20))
        h.step(22)
        assertEquals(LegStatus.FILLED, h.status(leg(g)))
        assertEquals(600, h.fills(leg(g)).sumOf { it.quantity })
    }

    @Test
    fun `invalid requests are refused before anything happens`() {
        val h = Harness()
        h.account(1)
        assertTrue(h.market.placeOrder(50, 1, OrderRequest("AAA", Structure.SINGLE, listOf(LegSpec(Side.BUY, 5, OrderKind.LIMIT))), 0, 0)!!.contains("Limit"))
        assertTrue(h.market.placeOrder(51, 1, OrderRequest("ZZZ", Structure.SINGLE, listOf(LegSpec(Side.BUY, 5, OrderKind.MARKET))), 0, 0)!!.contains("Unknown"))
        assertTrue(h.market.placeOrder(52, 2, OrderRequest("AAA", Structure.SINGLE, listOf(LegSpec(Side.BUY, 5, OrderKind.MARKET))), 0, 0)!!.contains("account"))
    }
}
