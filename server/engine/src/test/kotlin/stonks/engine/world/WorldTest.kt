package stonks.engine.world

import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.sim.Fixtures
import stonks.engine.sim.Market
import stonks.engine.strategy.StrategyType
import stonks.engine.trading.AccountEvent
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
import kotlin.test.assertTrue

private val start: Instant = Instant.parse("2026-01-05T10:00:00Z")

/** Short daily sessions, back to back. */
private fun day(i: Int, ticks: Int = 60) = Fixtures.session(start.plus(Duration.ofDays(i.toLong())), ticks)

private fun Market.run(days: IntRange, ticks: Int = 60, onDay: (Market) -> Unit = {}) {
    for (i in days) {
        runSession(day(i, ticks))
        onDay(this)
    }
}

private fun roundTrip(m: Market): Market {
    val bytes = ByteArrayOutputStream().also { m.writeSnapshot(DataOutputStream(it)) }.toByteArray()
    return Market.readSnapshot(DataInputStream(ByteArrayInputStream(bytes)), m.config)
}

class WorldTest {
    @Test
    fun `about a quarter of companies pay dividends`() {
        val m = Market(CompanyCatalog.generate(Rng(1)), seed = 11)
        val payers = m.tickers.values.count { it.corp.dividendPayer }
        assertEquals(13, payers, "25% of 50, rounded")
        m.tickers.values.filter { it.corp.aristocrat }.forEach { assertTrue(it.corp.dividendPayer) }
    }

    @Test
    fun `a year of news brings quarterly earnings, rate decisions, events and rumors`() {
        val m = Market(CompanyCatalog.generate(Rng(2)), seed = 12)
        val news = ArrayList<NewsItem>()
        m.run(0 until 250) { news += it.drainNews() }

        assertEquals((1L..news.size).toList(), news.map { it.id }, "ids are sequential")
        val earnings = news.filter { it.category == NewsCategory.EARNINGS }.groupBy { it.ticker }
        for ((ticker, items) in earnings) {
            val days = items.map { it.day }
            assertTrue(days.zipWithNext().all { (a, b) -> b - a == Corporate.QUARTER_DAYS }, "$ticker reports every quarter: $days")
        }
        assertTrue(earnings.size >= 45, "nearly every company reported: ${earnings.size}")
        assertEquals(5, news.count { it.category == NewsCategory.MACRO }, "a rate decision every ${Market.RATE_INTERVAL} days")
        assertTrue(m.benchmarkRate in 0.0..0.10)
        val rumors = news.count { it.category == NewsCategory.RUMOR }
        val events = news.count { it.ticker != null && it.category in setOf(NewsCategory.ANALYST, NewsCategory.CORPORATE, NewsCategory.LEGAL, NewsCategory.REGULATORY) }
        // ~1/12 minor + ~1/130 major per company-day.
        assertTrue(events in 700..1500, "events: $events")
        assertTrue(rumors in 10..80, "rumors: $rumors")
        assertTrue(news.any { it.category == NewsCategory.SECTOR })
    }

    @Test
    fun `dividends are fixed at the ex-date and paid on the pay date, shorts pay`() {
        val m = Market(listOf(Fixtures.company(StrategyType.DIVIDEND_ARISTOCRAT, ticker = "DIVI")), seed = 3)
        m.openAccount(1, 5_000_00)
        m.openAccount(2, 5_000_00)
        val t = m.tickers.getValue("DIVI")
        val long = m.ledger.account(1)!!
        val short = m.ledger.account(2)!!
        m.ledger.applyFill(long, "DIVI", Side.BUY, 10, t.last, 0)
        m.ledger.applyFill(short, "DIVI", Side.SELL, 4, t.last, 0)
        t.corp.nextEarningsDay = 1000 // keep earnings out of the way
        t.corp.declaredDividend = 150
        t.corp.exDay = 1
        t.corp.payDay = 3
        val cashLong = long.cash
        val cashShort = short.cash

        m.run(0..1)
        assertEquals(cashLong, long.cash, "nothing paid at the ex-date")
        m.ledger.applyFill(long, "DIVI", Side.SELL, 10, t.last, 0) // selling after the ex-date keeps the dividend
        val afterSale = long.cash
        m.run(2..3)
        assertEquals(afterSale + 10 * 150, long.cash)
        assertEquals(cashShort - 4 * 150 - short.totalInterest, short.cash, "plus borrow fees")
        val kinds = m.drainEvents().filterIsInstance<AccountEvent>().map { it.kind }
        assertEquals(2, kinds.count { it == AccountEvent.Kind.DIVIDEND })
    }

    @Test
    fun `splits scale prices and positions, reverse splits pay cash in lieu`() {
        val m = Market(listOf(Fixtures.company(StrategyType.STAGNANT, ticker = "SPLT", price = 60_000)), seed = 4)
        m.openAccount(1, 50_000_00)
        val a = m.ledger.account(1)!!
        val t = m.tickers.getValue("SPLT")
        m.ledger.applyFill(a, "SPLT", Side.BUY, 15, 60_000, 0)
        m.run(0..0)
        t.corp.splitDay = 1
        t.corp.splitRatio = 3.0
        val before = t.last
        m.beginSession(day(1))
        assertEquals(45, a.quantity("SPLT"))
        assertEquals(15 * 60_000L, a.positions.getValue("SPLT").costBasis, "cost basis unchanged")
        assertTrue(t.previousClose!! in before / 3 - 1..before / 3 + 1)
        repeat(60) { m.step() }
        m.endSession()

        t.corp.splitDay = 2
        t.corp.splitRatio = 0.1
        val cash = a.cash
        m.beginSession(day(2))
        assertEquals(4, a.quantity("SPLT"), "45 shares -> 4.5 -> 4 + cash for half a share")
        assertTrue(a.cash > cash)
    }

    @Test
    fun `completed deals delist the company, cash out holders and list a replacement`() {
        val m = Market(CompanyCatalog.generate(Rng(5)), seed = 5)
        m.openAccount(1, 50_000_00)
        m.openAccount(2, 50_000_00)
        val t = m.tickers.getValue("FOOF")
        m.ledger.applyFill(m.ledger.account(1)!!, "FOOF", Side.BUY, 10, t.last, 0)
        m.ledger.applyFill(m.ledger.account(2)!!, "FOOF", Side.SELL, 10, t.last, 0)
        m.run(0..0)
        t.corp.deal = Deal(offer = 12_345, closeDay = 1, completes = true, takePrivate = false)
        t.corp.status = CompanyStatus.DEAL_PENDING
        val cash1 = m.ledger.account(1)!!.cash
        val cash2 = m.ledger.account(2)!!.cash

        m.run(1..1)
        assertFalse("FOOF" in m.tickers)
        assertEquals(cash1 + 10 * 12_345, m.ledger.account(1)!!.cash)
        assertEquals(cash2 - 10 * 12_345, m.ledger.account(2)!!.cash)
        assertTrue(m.ledger.account(1)!!.positions.isEmpty())
        assertEquals("FOOF", m.delistings.single().ticker)
        val ipo = m.ipoPipeline.single()
        assertTrue(ipo.day in 3..6)
        assertTrue(ipo.company.ticker !in m.tickers)

        m.run(2..7)
        val listed = assertNotNull(m.tickers[ipo.company.ticker])
        assertEquals(50, m.tickers.size)
        assertTrue(listed.day >= ipo.day)
        val news = m.drainNews()
        assertTrue(news.any { it.category == NewsCategory.LISTING && it.ticker == ipo.company.ticker })
        assertTrue(news.any { it.category == NewsCategory.DEAL && it.ticker == "FOOF" })
    }

    @Test
    fun `bankruptcy wipes longs and shorts keep their gains`() {
        val m = Market(listOf(Fixtures.company(ticker = "OOPS"), Fixtures.company(ticker = "FINE")), seed = 6)
        m.openAccount(1, 50_000_00)
        m.openAccount(2, 50_000_00)
        val t = m.tickers.getValue("OOPS")
        m.ledger.applyFill(m.ledger.account(1)!!, "OOPS", Side.BUY, 10, 10_000, 0)
        m.ledger.applyFill(m.ledger.account(2)!!, "OOPS", Side.SELL, 10, 10_000, 0)
        m.run(0..0)
        // Force distress until a bankruptcy comes up.
        var d = 1
        while ("OOPS" in m.tickers && d < 200) {
            t.corp.status = CompanyStatus.DISTRESS
            t.corp.distressEnd = d
            t.corp.deal = null
            m.run(d..d)
            d++
        }
        val delisting = m.delistings.single()
        if (delisting.price == 0L) {
            assertEquals(50_000_00L - 10 * 10_000, m.ledger.account(1)!!.cash)
            val s = m.ledger.account(2)!!
            assertEquals(50_000_00L + 10 * 10_000 - s.totalInterest, s.cash, "minus borrow fees")
        }
    }

    @Test
    fun `world state survives snapshots exactly`() {
        val a = Market(CompanyCatalog.generate(Rng(8)), seed = 8)
        a.run(0 until 60)
        a.drainNews()
        val b = roundTrip(a)
        val newsA = ArrayList<NewsItem>()
        val newsB = ArrayList<NewsItem>()
        a.run(60 until 120) { newsA += it.drainNews() }
        b.run(60 until 120) { newsB += it.drainNews() }
        assertEquals(newsA, newsB)
        assertEquals(a.tickers.keys, b.tickers.keys)
        assertEquals(a.tickers.values.map { it.last }, b.tickers.values.map { it.last })
        assertEquals(a.benchmarkRate, b.benchmarkRate)
    }
}

class CrowdTest {
    @Test
    fun `copycats copy less than they see, on average`() {
        val crowd = Crowd(Rng(1))
        var copied = 0L
        var against = 0L
        val trials = 20_000
        repeat(trials) { i ->
            crowd.onPlayerTrade(i, 0, Side.BUY, 1_000, 10_000, depthNotional = 50_000_000.0, fame = 0.0, shame = 0.0)
            for (o in crowd.due(i, 20)) if (o.side == Side.BUY) copied += o.quantity else against += o.quantity
            crowd.endSession()
        }
        val ratio = (copied + against).toDouble() / (trials * 1_000L)
        assertTrue(ratio in 0.01..0.6, "crowd volume per player share: $ratio")
        assertTrue(copied > against)
    }

    @Test
    fun `shamed players attract inversecats`() {
        val crowd = Crowd(Rng(2))
        var copied = 0L
        var against = 0L
        repeat(20_000) { i ->
            crowd.onPlayerTrade(i, 0, Side.BUY, 1_000, 10_000, 50_000_000.0, fame = 0.0, shame = 1.0)
            for (o in crowd.due(i, 20)) if (o.side == Side.BUY) copied += o.quantity else against += o.quantity
            crowd.endSession()
        }
        assertTrue(against > copied)
    }
}
