package stonks.engine.trading

import stonks.engine.core.Side
import stonks.engine.sim.Fixtures
import stonks.engine.sim.Market
import stonks.engine.strategy.StrategyType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 24L * 3600 * 1000
private val t0 = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

private class Econ {
    val market = Market(listOf(Fixtures.company(StrategyType.STAGNANT, ticker = "AAA")), seed = 1)
    var seq = 0L
    fun act(id: Long, action: EconomyAction, at: Long = t0) = market.economy(id, action, at, "k${++seq}")
    fun acct(id: Long) = market.ledger.account(id)!!
    fun events() = market.drainEvents().filterIsInstance<AccountEvent>()
    /** Gives [id] a position bought at [price] (bypassing the book). */
    fun hold(id: Long, qty: Long, price: Long) = market.ledger.applyFill(acct(id), "AAA", if (qty > 0) Side.BUY else Side.SELL, kotlin.math.abs(qty), price, 0)
    fun session(i: Int) = market.runSession(Fixtures.session(Instant.parse("2026-10-05T10:00:00Z").plus(Duration.ofDays(i.toLong())), 40))
}

class EconomyTest {
    @Test
    fun `weekly claim only below $1k, once a week`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        assertNotNull(e.act(1, EconomyAction.Claim), "too rich")
        e.acct(1).cash = 500_00
        assertNull(e.act(1, EconomyAction.Claim))
        assertEquals(1_500_00, e.acct(1).cash)
        e.acct(1).cash = -3_000_00 // in debt: still allowed, next week
        assertNotNull(e.act(1, EconomyAction.Claim, t0 + 6 * DAY))
        assertNull(e.act(1, EconomyAction.Claim, t0 + 7 * DAY))
        assertEquals(-2_000_00, e.acct(1).cash)
        assertEquals(2, e.events().count { it.kind == AccountEvent.Kind.CLAIMED })
    }

    @Test
    fun `resets wipe everything and grow the cooldown with debt`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        e.hold(1, 10, 100_00)
        e.acct(1).plan = Plan.PRO
        assertNull(e.act(1, EconomyAction.Reset))
        val a = e.acct(1)
        assertEquals(5_000_00, a.cash)
        assertTrue(a.positions.isEmpty())
        assertEquals(Plan.ROOKIE, a.plan)
        assertEquals(t0 + 30 * DAY, a.standing.nextResetAt, "no debt: 30 days")
        assertNotNull(e.act(1, EconomyAction.Reset, t0 + 29 * DAY))

        a.cash = -10_000_00 // $10k debt: 37 days
        val at = t0 + 30 * DAY
        assertNull(e.act(1, EconomyAction.Reset, at))
        assertEquals(at + (37 * DAY), a.standing.nextResetAt)
        a.cash = -10_000_00 // again in debt within 180 days: x1.25
        val at2 = at + 37 * DAY
        assertNull(e.act(1, EconomyAction.Reset, at2))
        assertEquals(at2 + (37 * 1.25 * DAY).toLong(), a.standing.nextResetAt)
        assertEquals(0, a.standing.shame, "resets aren't shameful")
    }

    @Test
    fun `bankruptcy costs a level and a badge, and clearing badges costs cash`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        val a = e.acct(1)
        a.standing.cashLevel = 2
        assertNotNull(e.act(1, EconomyAction.Bankrupt), "positive net worth")
        a.cash = -1_00
        assertNull(e.act(1, EconomyAction.Bankrupt))
        assertEquals(1, a.standing.cashLevel)
        assertEquals(6_000_00, a.cash)
        assertEquals(1, a.standing.shame)
        assertEquals(0L, a.standing.nextResetAt, "no cooldown")

        a.cash = 999_00
        assertNotNull(e.act(1, EconomyAction.ClearBadge), "badge #1 costs $1,000")
        a.cash = 1_000_00
        assertNull(e.act(1, EconomyAction.ClearBadge))
        assertEquals(0, a.standing.shame)
        assertEquals(0, a.cash)
        assertEquals(1_000_00, EconomyRules.clearCost(2))
        assertEquals(320_000, EconomyRules.clearCost(5))
        assertEquals(26_214_400_00, EconomyRules.clearCost(20))

        a.standing.shame = 29
        a.cash = -1
        assertNull(e.act(1, EconomyAction.Bankrupt))
        assertTrue(a.standing.eternal)
        a.cash = Long.MAX_VALUE / 4
        assertNotNull(e.act(1, EconomyAction.ClearBadge), "Eternal Shame")
    }

    @Test
    fun `game over at -10x starting cash`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        e.market.openAccount(2, 5_000_00)
        e.acct(1).cash = -49_000_00 // a day of interest stays above -$50k
        e.acct(2).cash = -50_000_00
        e.session(0)
        val ev = e.events()
        assertTrue(ev.none { it.accountId == 1L && it.kind == AccountEvent.Kind.BANKRUPT })
        assertTrue(ev.any { it.accountId == 2L && it.kind == AccountEvent.Kind.BANKRUPT && it.key != null })
        assertEquals(5_000_00, e.acct(2).cash)
        assertEquals(1, e.acct(2).standing.shame)
    }

    @Test
    fun `upgrades raise starting cash and survive resets`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        val a = e.acct(1)
        assertNotNull(e.act(1, EconomyAction.Upgrade))
        a.cash = 3_000_000_00
        assertNull(e.act(1, EconomyAction.Upgrade))
        assertNull(e.act(1, EconomyAction.Upgrade))
        assertEquals(0, a.cash, "$1M + $2M")
        assertEquals(2, a.standing.cashLevel)
        assertNull(e.act(1, EconomyAction.Reset))
        assertEquals(7_000_00, a.cash)
    }

    @Test
    fun `bonds lock cash until maturity and pay the guaranteed return`() {
        val e = Econ()
        e.market.openAccount(1, 10_000_00)
        val o = e.market.issueBonds("October Bond", 0.08, windowDays = 2, termDays = 3, capPerPlayer = 5_000_00)
        assertNull(e.act(1, EconomyAction.BuyBond(o.id, 3_000_00)))
        assertNotNull(e.act(1, EconomyAction.BuyBond(o.id, 2_500_00)), "over the cap")
        val a = e.acct(1)
        assertEquals(7_000_00, a.cash)
        assertEquals(10_000_00, e.market.netWorth(a), "face value counts toward net worth")
        assertEquals(7_000_00, e.market.ledger.figures(a, e.market.prices).equity, "but not as collateral")
        (0 until 4).forEach { e.session(it) }
        assertNotNull(e.act(1, EconomyAction.BuyBond(o.id, 1_00)), "window closed")
        assertEquals(7_000_00, a.cash)
        e.session(4)
        assertEquals(10_240_00, a.cash)
        assertTrue(a.bonds.isEmpty())
    }

    @Test
    fun `milestones are awarded once`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        e.acct(1).cash = 10_000_00
        e.session(0)
        e.session(1)
        val ach = e.events().filter { it.kind == AccountEvent.Kind.ACHIEVEMENT }
        assertEquals(listOf("DOUBLED_UP"), ach.map { it.detail })
    }

    @Test
    fun `economy state survives snapshots`() {
        val e = Econ()
        e.market.openAccount(1, 5_000_00)
        val a = e.acct(1)
        a.cash = -1
        e.act(1, EconomyAction.Bankrupt)
        e.act(1, EconomyAction.Reset)
        e.market.issueBonds("B", 0.2, 5, 10, 5_000_00)
        e.act(1, EconomyAction.BuyBond(1, 1_000_00))
        val bytes = ByteArrayOutputStream().also { e.market.writeSnapshot(DataOutputStream(it)) }.toByteArray()
        val m = Market.readSnapshot(DataInputStream(ByteArrayInputStream(bytes)), e.market.config)
        val b = m.ledger.account(1)!!
        assertEquals(a.standing.shame, b.standing.shame)
        assertEquals(a.standing.nextResetAt, b.standing.nextResetAt)
        assertEquals(a.standing.resets, b.standing.resets)
        assertEquals(a.bonds, b.bonds)
        assertEquals(1, m.bondOfferings.size)
        assertFalse(b.standing.eternal)
    }
}
