package stonks.engine.sim

import stonks.engine.clock.MarketSchedule
import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketTest {
    private val schedule = MarketSchedule()
    private val liveAt = Instant.parse("2026-09-29T12:00:00Z")
    private val config = MarketConfig()

    private fun market(seed: Long) = Market(CompanyCatalog.generate(Rng(Rng.derive(seed, -1))), config, seed)

    private fun closes(m: Market) = m.tickers.mapValues { (_, t) -> t.candles.days.candles.map { it.close } }

    @Test
    fun `same seed produces the same world, sequential or parallel`() {
        val a = market(42)
        val b = market(42)
        Backfill.run(a, schedule, liveAt, sessions = 3, threads = 1)
        Backfill.run(b, schedule, liveAt, sessions = 3, threads = 4)
        assertEquals(closes(a), closes(b))
    }

    @Test
    fun `different seeds produce different worlds`() {
        val a = market(1)
        val b = market(2)
        Backfill.run(a, schedule, liveAt, sessions = 1, threads = 1)
        Backfill.run(b, schedule, liveAt, sessions = 1, threads = 1)
        assertTrue(closes(a) != closes(b))
    }

    @Test
    fun `live stepping matches backfill`() {
        val a = market(7)
        val b = market(7)
        val sessions = schedule.sessionsBefore(liveAt, 2)
        val pool = Executors.newFixedThreadPool(2)
        sessions.forEach { a.runSession(it, pool) }
        pool.shutdown()
        for (s in sessions) {
            b.beginSession(s)
            repeat(s.ticks) { b.step() }
            b.endSession()
        }
        assertEquals(closes(a), closes(b))
    }

    @Test
    fun `every ticker trades each session`() {
        val m = market(3)
        Backfill.run(m, schedule, liveAt, sessions = 1, threads = 4)
        assertTrue(m.tickers.values.all { it.candles.days.candles.size == 1 && it.candles.days.candles[0].volume > 0 })
    }
}
