package stonks.app

import stonks.app.db.query
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTest {
    private val config = AppConfig(backfillSessions = 2, worldSeed = 7)

    private fun count(app: App, table: String) = app.db.read { c -> c.query("select count(*) from $table") { it.next(); it.getLong(1) } }

    @Test
    fun `restart restores the snapshot and catches up on missed sessions`() {
        val clock = TestClock(Instant.parse("2026-09-29T17:45:00Z")) // Tuesday midday break
        val db = TestDb.fresh()
        val first = App(config, db, clock).also { it.start(liveLoop = false) }
        val completed = first.market.market.sessionsCompleted
        val foof = first.market.state.quotesByTicker.getValue("FOOF").last
        first.close()

        // Down until Wednesday 09:00 ET: Tuesday session B and part of Wednesday A were missed.
        clock.now = Instant.parse("2026-09-30T13:00:00Z")
        val second = App(config, TestDb.reconnect(db), clock).also { it.start(liveLoop = false) }
        try {
            val m = second.market.market
            assertEquals(completed + 1, m.sessionsCompleted)
            assertEquals("OPEN", second.market.state.session.state)
            assertEquals(3 * 720, m.tick) // 3 hours into the session
            assertTrue(second.market.state.quotesByTicker.getValue("FOOF").last != foof)
            second.market.flushCandles()
            assertEquals(50L * 3, count(second, "candles_1d"))
        } finally {
            second.close()
        }
    }

    @Test
    fun `a restored world continues exactly as an uninterrupted one`() {
        val t0 = Instant.parse("2026-09-29T17:45:00Z")
        val later = Instant.parse("2026-09-30T02:05:00Z") // after Tuesday session B
        val clockA = TestClock(t0)
        val a = App(config, TestDb.fresh(), clockA).also { it.start(liveLoop = false) }
        clockA.now = later
        a.market.pump(later)
        val uninterrupted = a.market.state.quotes.map { it.last }
        a.close()

        val clockB = TestClock(t0)
        val dbB = TestDb.fresh()
        App(config, dbB, clockB).also { it.start(liveLoop = false) }.close()
        clockB.now = later
        val b = App(config, TestDb.reconnect(dbB), clockB).also { it.start(liveLoop = false) }
        try {
            assertEquals(uninterrupted, b.market.state.quotes.map { it.last })
        } finally {
            b.close()
        }
    }
}
