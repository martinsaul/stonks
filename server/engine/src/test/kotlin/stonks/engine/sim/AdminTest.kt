package stonks.engine.sim

import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.strategy.StrategyType
import stonks.engine.world.NewsCategory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun day(i: Int, ticks: Int = 60) = Fixtures.session(Instant.parse("2026-10-05T10:00:00Z").plus(Duration.ofDays(i.toLong())), ticks)

private fun roundTrip(m: Market): Market {
    val bytes = ByteArrayOutputStream().also { m.writeSnapshot(DataOutputStream(it)) }.toByteArray()
    return Market.readSnapshot(DataInputStream(ByteArrayInputStream(bytes)), m.config)
}

class AdminTest {
    private fun market() = Market(CompanyCatalog.generate(Rng(1)), seed = 1)

    @Test
    fun `halted tickers don't move or trade`() {
        val m = market()
        m.runSession(day(0))
        assertNull(m.admin(AdminAction.Halt("FOOF", true)))
        m.beginSession(day(1))
        val t = m.tickers.getValue("FOOF")
        val start = t.last
        repeat(30) { m.step() }
        assertEquals(start, t.last)
        assertEquals(0, t.dayVolume)
        assertNull(m.admin(AdminAction.Halt(null, false)))
        repeat(30) { m.step() }
        assertTrue(t.dayVolume > 0)
        assertTrue(m.drainNews().any { it.headline.contains("halts") })
    }

    @Test
    fun `shocks move prices now or on schedule`() {
        val m = market()
        m.runSession(day(0))
        m.beginSession(day(1))
        repeat(5) { m.step() }
        val t = m.tickers.getValue("MHRD")
        val before = t.reference
        assertNull(m.admin(AdminAction.Shock(AdminAction.Shock.Scope.TICKER, "MHRD", -30.0, "Macrohard's servers achieve sentience, ask for a raise")))
        m.step()
        assertTrue(t.reference / before in 0.6..0.8, "${t.reference / before}")

        assertNull(m.admin(AdminAction.Shock(AdminAction.Shock.Scope.MARKET, null, 10.0, "Aliens buy the index", day = m.day, tick = 20)))
        assertEquals(1, m.scheduledShocks.size)
        val foof = m.tickers.getValue("FOOF")
        repeat(20 - m.tick) { m.step() }
        val atShock = foof.reference
        m.step()
        assertTrue(foof.reference / atShock > 1.05)
        assertTrue(m.scheduledShocks.isEmpty())
        assertNotNull(m.admin(AdminAction.Shock(AdminAction.Shock.Scope.MARKET, null, 5.0, "Too late", day = m.day, tick = 1)), "past")
        assertTrue(m.drainNews().any { it.category == NewsCategory.MACRO && it.headline == "Aliens buy the index" })
    }

    @Test
    fun `macro, strategy, corporate actions and IPOs`() {
        val m = market()
        m.runSession(day(0))
        assertNull(m.admin(AdminAction.SetRate(6.5)))
        assertEquals(0.065, m.benchmarkRate, 1e-9)
        assertNull(m.admin(AdminAction.SetRegime(MarketRegime.CRASH)))
        assertEquals(MarketRegime.CRASH, m.regime)
        assertNull(m.admin(AdminAction.SetStrategy("FOOF", StrategyType.DEATH_SPIRAL, nextSession = true)))
        assertNull(m.admin(AdminAction.Split("PEAR", 2.0)))
        assertNotNull(m.admin(AdminAction.Split("PEAR", 7.0)))
        assertNull(m.admin(AdminAction.Ipo(null, 2)))
        val ipo = m.ipoPipeline.single().company.ticker
        assertNull(m.admin(AdminAction.Tune("NVDT", volatility = 3.0, depth = 0.5, borrowPool = 0.05)))

        val restored = roundTrip(m)
        for (mm in listOf(m, restored)) {
            mm.runSession(day(1))
            assertEquals(StrategyType.DEATH_SPIRAL, mm.tickers.getValue("FOOF").strategy.current.type)
            assertEquals(-1, mm.tickers.getValue("PEAR").corp.splitDay, "split applied")
            assertEquals(3.0, mm.tickers.getValue("NVDT").volMultiplier)
            mm.runSession(day(2))
            assertTrue(ipo in mm.tickers)
        }
        assertEquals(m.tickers.values.map { it.last }, restored.tickers.values.map { it.last })
    }

    @Test
    fun `cash adjustments and voided fills`() {
        val m = market()
        m.openAccount(1, 5_000_00)
        val a = m.ledger.account(1)!!
        assertNull(m.admin(AdminAction.AdjustCash(1, 250_00, "compensation")))
        assertEquals(5_250_00, a.cash)
        m.ledger.applyFill(a, "FOOF", Side.BUY, 10, 100_00, 4_95)
        assertNull(m.admin(AdminAction.VoidFill(1, "FOOF", Side.BUY, 10, 100_00, 4_95)))
        assertEquals(5_250_00, a.cash)
        assertTrue(a.positions.isEmpty())
        assertTrue(abs(a.totalCommissions) == 0L)
    }
}
