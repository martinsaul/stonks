package stonks.engine.sim

import stonks.engine.book.Order
import stonks.engine.book.OrderType
import stonks.engine.book.TimeInForce
import stonks.engine.clock.MarketSchedule
import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.core.TraderId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotTest {
    private val schedule = MarketSchedule()
    private val sessions = schedule.sessionsBefore(Instant.parse("2026-09-29T12:00:00Z"), 4)

    private fun roundTrip(m: Market): Market {
        val bytes = ByteArrayOutputStream().also { m.writeSnapshot(DataOutputStream(it)) }.toByteArray()
        return Market.readSnapshot(DataInputStream(ByteArrayInputStream(bytes)), m.config)
    }

    private fun state(m: Market) = m.tickers.mapValues { (_, t) ->
        listOf(t.reference, t.last, t.previousClose, t.strategy.current.type, t.strategy.history.size)
    }

    @Test
    fun `a restored market continues exactly like the original`() {
        val original = Market(CompanyCatalog.generate(Rng(1)), seed = 99)
        sessions.take(2).forEach { original.runSession(it) }
        val gtc = Order(7, TraderId.player(1), Side.BUY, OrderType.LIMIT, 50, 1, TimeInForce.GTC)
        original.tickers.getValue("FOOF").submit(gtc)

        val restored = roundTrip(original)
        assertEquals(state(original), state(restored))
        assertEquals(original.regimeHistory, restored.regimeHistory)
        assertEquals(original.sessionsCompleted, restored.sessionsCompleted)

        sessions.drop(2).forEach { original.runSession(it); restored.runSession(it) }
        assertEquals(state(original), state(restored))
        val restoredOrder = restored.tickers.getValue("FOOF").book.restingOrder(7)!!
        assertEquals(50, restoredOrder.remaining)
    }

    @Test
    fun `restore mid-way then fast-forward matches uninterrupted live stepping`() {
        val a = Market(CompanyCatalog.generate(Rng(2)), seed = 5)
        val b = roundTrip(a)
        val s = sessions.first()
        a.beginSession(s)
        repeat(s.ticks) { a.step() }
        a.endSession()
        b.beginSession(s)
        b.fastForward(1234)
        b.fastForward(s.ticks)
        b.endSession()
        assertEquals(state(a), state(b))
    }

    @Test
    fun `snapshots require a closed market`() {
        val m = Market(CompanyCatalog.generate(Rng(3)), seed = 1)
        m.beginSession(sessions.first())
        assertFailsWith<IllegalStateException> { m.writeSnapshot(DataOutputStream(ByteArrayOutputStream())) }
    }

    @Test
    fun `candle sink receives closed candles`() {
        val m = Market(CompanyCatalog.generate(Rng(4)), MarketConfig(retention = CandleRetention(0, 0, 0)), seed = 1)
        val counts = mutableMapOf<Resolution, Int>()
        m.setCandleSink { _, res, _ -> counts.merge(res, 1, Int::plus) }
        val s = sessions.first()
        m.runSession(s)
        assertEquals(50 * s.ticks, counts[Resolution.S5])
        assertEquals(50 * s.ticks / 12, counts[Resolution.M1])
        assertEquals(50, counts[Resolution.D1])
        assertEquals(0, m.tickers.values.first().candles.ticks.candles.size)
    }
}
