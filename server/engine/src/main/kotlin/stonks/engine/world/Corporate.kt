package stonks.engine.world

import stonks.engine.company.Sector
import stonks.engine.core.Rng
import stonks.engine.snapshot.readDoubles
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.writeDoubles
import stonks.engine.snapshot.writeList
import stonks.engine.strategy.StrategyEngine
import stonks.engine.strategy.StrategyInstance
import stonks.engine.strategy.StrategyType
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

enum class CompanyStatus { ACTIVE, DISTRESS, DEAL_PENDING }

/** A pending acquisition: holders are cashed out at [offer] on [closeDay] (if it completes). */
data class Deal(val offer: Long, val closeDay: Int, val completes: Boolean, val takePrivate: Boolean)

/**
 * A company's corporate life: fundamentals and earnings, dividends, scheduled and
 * random news, splits and lifecycle status. Owned by its ticker's simulation; things
 * that touch accounts or the listing (dividend payments, splits, delisting) are
 * orchestrated by the market.
 *
 * Its random stream is separate from the price path's, so news doesn't perturb the
 * underlying market microstructure.
 */
class Corporate(
    val ticker: String,
    val name: String,
    val sector: Sector,
    var shares: Long,
    initialPrice: Long,
    initialStrategy: StrategyType,
    private var rng: Rng,
    firstDay: Int = 0,
) {
    // ---- Fundamentals -----------------------------------------------------------
    /** Underlying quarterly earnings power (cents/share, > 0). */
    var earningsPower: Double
    /** Reported quarterly EPS, newest last (up to 4). */
    val epsHistory = ArrayDeque<Long>()
    /** Analysts' estimate for the next report (cents). */
    var consensus: Long
    var nextEarningsDay: Int
    /** Curve-only value index (excludes player impact); earnings track it. */
    var fundamental = 1.0
    private var fundamentalAtEarnings = 1.0

    // ---- Dividends --------------------------------------------------------------
    var dividendPayer: Boolean
    val aristocrat = initialStrategy == StrategyType.DIVIDEND_ARISTOCRAT
    /** Quarterly dividend per share (cents). */
    var dividend: Long
    /** Pending ex-dividend / pay days for the last declared dividend (-1 = none). */
    var exDay = -1
    var payDay = -1
    var declaredDividend = 0L
    /** Dividend if this company pays (only used when the market sets the policy). */
    private val potentialDividend: Long
    private val initialStrategyType = initialStrategy

    /** Whether this company can pay dividends at all (never-pay strategies can't). */
    val canPayDividends: Boolean get() = aristocrat || initialStrategyType !in NEVER_PAY

    // ---- Lifecycle --------------------------------------------------------------
    var status = CompanyStatus.ACTIVE
    var distressEnd = -1
    var deal: Deal? = null
    val closes = ArrayDeque<Long>()
    var daysUnderDollar = 0
    var splitDay = -1
    var splitRatio = 1.0
    /** Strategy to force at the next open (lifecycle outcomes). */
    var pendingStrategy: StrategyType? = null

    val agenda = ArrayList<AgendaItem>()
    val news = ArrayList<NewsDraft>()

    val epsTtm: Long? get() = if (epsHistory.size < 4) null else epsHistory.sum()
    val average20: Double get() = if (closes.isEmpty()) 0.0 else closes.average()

    init {
        val pe = SECTOR_PE.getValue(sector) * rng.logNormal(1.0, 0.3)
        earningsPower = (initialPrice / pe / 4).coerceAtLeast(1.0)
        repeat(4) { i -> epsHistory.addLast((earningsPower * exp(-0.02 * (3 - i))).roundToLong().coerceAtLeast(1)) }
        consensus = (earningsPower * 1.02).roundToLong().coerceAtLeast(1)
        nextEarningsDay = firstDay + rng.nextInt(0, QUARTER_DAYS)
        dividendPayer = aristocrat || (initialStrategy !in NEVER_PAY && rng.chance(0.2))
        val yield = if (aristocrat) rng.nextDouble(0.02, 0.05) else rng.nextDouble(0.01, 0.04)
        potentialDividend = (initialPrice * yield / 4).roundToLong().coerceAtLeast(1)
        dividend = if (dividendPayer) potentialDividend else 0
    }

    /** Sets dividend policy at world creation (the market picks ~25% of companies). */
    fun assignDividendPolicy(pays: Boolean) {
        dividendPayer = pays && canPayDividends
        dividend = if (dividendPayer) potentialDividend else 0
    }

    /** Called with each tick's curve return (not player impact). */
    fun onCurve(logReturn: Double) {
        fundamental *= exp(logReturn)
    }

    /**
     * Pre-market for game day [day]: rolls today's random news, releases earnings when
     * due, applies scheduled items at the open and the ex-dividend price drop.
     * Returns the log return to gap the price by.
     */
    fun preMarket(day: Int, weekend: Boolean, price: Double, strategy: StrategyEngine, sessionTicks: Int): Double {
        pendingStrategy?.let { strategy.force(StrategyInstance.sample(it, rng)); pendingStrategy = null }
        if (status == CompanyStatus.ACTIVE) rollEvents(day, weekend, sessionTicks)
        var jump = 0.0
        if (day >= nextEarningsDay) {
            if (weekend) nextEarningsDay = day + 1 else jump += releaseEarnings(day, strategy)
        }
        if (exDay in 0..day && !weekend) {
            jump += ln((1 - declaredDividend / price).coerceIn(0.5, 1.0))
            news += NewsDraft(day, -1, ticker, sector, NewsCategory.DIVIDEND, "$name ($ticker) trades ex-dividend today (${Headlines.money(declaredDividend)} per share)")
            exDay = -1
        }
        jump += processAgenda(day, -1, price, strategy)
        fundamental *= exp(jump)
        return jump
    }

    /** Log return of news breaking at [tick] of [day]. */
    fun jumpAt(day: Int, tick: Int, price: Double, strategy: StrategyEngine): Double {
        if (agenda.isEmpty()) return 0.0
        val j = processAgenda(day, tick, price, strategy)
        fundamental *= exp(j)
        return j
    }

    /** End of session: tracks closes, sub-$1 streaks and announces splits. */
    fun endSession(day: Int, close: Long) {
        closes.addLast(close)
        while (closes.size > 20) closes.removeFirst()
        daysUnderDollar = if (close < 100) daysUnderDollar + 1 else 0
        agenda.removeIf { it.day <= day }
        if (status != CompanyStatus.ACTIVE || splitDay >= 0) return
        when {
            daysUnderDollar >= 20 -> announceSplit(day, 0.1)
            close > 500_00 && rng.chance(1.0 / 30) -> announceSplit(day, if (close < 1_000_00) 2.0 else if (close < 2_000_00) 3.0 else 5.0)
        }
    }

    private fun announceSplit(day: Int, ratio: Double) {
        splitDay = day + 3
        splitRatio = ratio
        val text = if (ratio >= 1) "$name announces a ${ratio.toInt()}-for-1 stock split, effective in 3 sessions"
            else "$name announces a 1-for-${(1 / ratio).roundToLong()} reverse split, effective in 3 sessions, to stay listed"
        news += NewsDraft(day, Int.MAX_VALUE, ticker, sector, NewsCategory.SPLIT, text)
    }

    /** Applies a split to per-share figures (prices and orders are the market's job). */
    fun applySplit(day: Int) {
        val r = splitRatio
        earningsPower /= r
        val scaled = epsHistory.map { (it / r).roundToLong() }
        epsHistory.clear(); epsHistory.addAll(scaled)
        consensus = (consensus / r).roundToLong()
        dividend = (dividend / r).roundToLong()
        declaredDividend = (declaredDividend / r).roundToLong()
        val c = closes.map { (it / r).roundToLong() }
        closes.clear(); closes.addAll(c)
        shares = (shares * r).roundToLong()
        deal = deal?.let { it.copy(offer = (it.offer / r).roundToLong()) }
        daysUnderDollar = 0
        splitDay = -1
        news += NewsDraft(day, -1, ticker, sector, NewsCategory.SPLIT,
            if (r >= 1) "$name's ${r.toInt()}-for-1 split takes effect today" else "$name's 1-for-${(1 / r).roundToLong()} reverse split takes effect today")
    }

    // ---- Random events ----------------------------------------------------------

    private fun rollEvents(day: Int, weekend: Boolean, sessionTicks: Int) {
        val minorRate = (if (weekend) 0.5 else 1.0) / 12
        val majorRate = (if (weekend) 0.25 else 1.0) / 130
        if (rng.chance(minorRate)) {
            val type = rng.pick(EventType.minor)
            schedule(AgendaItem(day, randomTick(sessionTicks), AgendaKind.EVENT, type, category = type.category))
        }
        if (rng.chance(majorRate)) {
            val type = rng.weighted(EventType.majorWeights(sector))
            if (rng.chance(0.3)) {
                schedule(AgendaItem(day, randomTick(sessionTicks), AgendaKind.RUMOR, type, category = NewsCategory.RUMOR))
                schedule(AgendaItem(day + rng.nextInt(1, 4), randomTick(sessionTicks), AgendaKind.EVENT, type, category = type.category))
            } else {
                schedule(AgendaItem(day, randomTick(sessionTicks), AgendaKind.EVENT, type, category = type.category))
            }
        }
        // False rumors: 30% of all rumors lead nowhere.
        if (rng.chance(majorRate * 0.3 * 3 / 7)) {
            val type = rng.weighted(EventType.majorWeights(sector))
            schedule(AgendaItem(day, randomTick(sessionTicks), AgendaKind.RUMOR, type, category = NewsCategory.RUMOR))
        }
    }

    /** Pre-market 30% of the time, otherwise at a random tick. */
    private fun randomTick(sessionTicks: Int) = if (rng.chance(0.3)) -1 else rng.nextInt(0, sessionTicks)

    fun schedule(item: AgendaItem) {
        agenda += item
    }

    private fun processAgenda(day: Int, tick: Int, price: Double, strategy: StrategyEngine): Double {
        var jump = 0.0
        val due = agenda.filter { it.day < day || (it.day == day && it.tick <= tick) }
        if (due.isEmpty()) return 0.0
        agenda.removeAll(due.toSet())
        for (item in due) {
            jump += when (item.kind) {
                AgendaKind.RUMOR -> {
                    news += NewsDraft(day, tick, ticker, sector, NewsCategory.RUMOR, Headlines.rumor(item.type!!, name, ticker, rng))
                    0.0
                }
                AgendaKind.JUMP -> {
                    news += NewsDraft(day, tick, ticker, sector, item.category, item.headline!!, item.jump)
                    item.jump
                }
                AgendaKind.EVENT -> applyEvent(item.type!!, day, tick, price * exp(jump), strategy)
            }
        }
        return jump
    }

    private fun applyEvent(type: EventType, day: Int, tick: Int, price: Double, strategy: StrategyEngine): Double {
        if (status != CompanyStatus.ACTIVE && type.major) return 0.0
        if (type == EventType.BUYOUT_OFFER) return buyoutOffer(day, tick, price, strategy)
        val jump = type.sampleJump(rng)
        when (type) {
            EventType.SECONDARY_OFFERING -> shares = (shares * rng.nextDouble(1.05, 1.15)).roundToLong()
            EventType.BUYBACK -> shares = (shares * rng.nextDouble(0.97, 0.99)).roundToLong()
            else -> {}
        }
        if (type.strategyChance > 0 && rng.chance(type.strategyChance)) {
            strategy.force(StrategyInstance.sample(rng.weighted(type.strategies), rng))
        }
        news += NewsDraft(day, tick, ticker, sector, type.category, Headlines.event(type, name, ticker, rng), jump)
        return jump
    }

    private fun buyoutOffer(day: Int, tick: Int, price: Double, strategy: StrategyEngine): Double {
        val base = if (closes.isEmpty()) price else average20
        val offer = (base * rng.nextDouble(1.20, 1.45)).roundToLong()
        return startDeal(day, tick, price, strategy, offer, rng.nextInt(5, 16), rng.chance(0.85), takePrivate = false)
    }

    /** Opens a deal: the price jumps near the offer (a small merger-arbitrage discount). */
    fun startDeal(day: Int, tick: Int, price: Double, strategy: StrategyEngine?, offer: Long, closeIn: Int, completes: Boolean, takePrivate: Boolean): Double {
        deal = Deal(offer, day + closeIn, completes, takePrivate)
        status = CompanyStatus.DEAL_PENDING
        if (strategy != null) strategy.force(StrategyInstance.sample(StrategyType.STAGNANT, rng)) else pendingStrategy = StrategyType.STAGNANT
        val target = offer * (1 - rng.nextDouble(0.02, 0.06))
        val jump = ln((target / price).coerceIn(0.2, 5.0))
        val who = if (takePrivate) "is being taken private" else "agrees to be acquired"
        news += NewsDraft(day, tick, ticker, sector, NewsCategory.DEAL, "$name $who for ${Headlines.money(offer)} per share in cash; deal expected to close in $closeIn sessions", jump)
        return jump
    }

    /** Called by the market when a pending deal fails to complete. */
    fun breakDeal(day: Int) {
        deal = null
        status = CompanyStatus.ACTIVE
        pendingStrategy = StrategyType.VOLATILE
        schedule(AgendaItem(day, -1, AgendaKind.JUMP, headline = "$name takeover collapses; shares plunge", jump = ln(1 - rng.nextDouble(0.15, 0.30)), category = NewsCategory.DEAL))
    }

    // ---- Distress ---------------------------------------------------------------

    fun enterDistress(day: Int) {
        if (status != CompanyStatus.ACTIVE) return
        status = CompanyStatus.DISTRESS
        distressEnd = day + rng.nextInt(10, 21)
        pendingStrategy = StrategyType.SLOW_DECLINE
        news += NewsDraft(day, -1, ticker, sector, NewsCategory.DISTRESS, "$name warns of 'substantial doubt' about its ability to continue as a going concern")
        schedule(AgendaItem(day + rng.nextInt(1, 4), -1, AgendaKind.JUMP, headline = "Credit agencies cut $name to junk", jump = ln(1 - rng.nextDouble(0.05, 0.10)), category = NewsCategory.DISTRESS))
        schedule(AgendaItem(day + rng.nextInt(4, 9), randomTick(5400), AgendaKind.JUMP, headline = "$name hires restructuring advisers", jump = ln(1 - rng.nextDouble(0.05, 0.15)), category = NewsCategory.DISTRESS))
        if (dividendPayer && dividend > 0) suspendDividend(day, "as it fights for survival")
    }

    enum class Outcome { BANKRUPTCY, BAILOUT, TAKEN_PRIVATE }

    fun resolveDistress(): Outcome = rng.weighted(mapOf(Outcome.BANKRUPTCY to 70.0, Outcome.BAILOUT to 15.0, Outcome.TAKEN_PRIVATE to 15.0))

    fun bailout(day: Int) {
        status = CompanyStatus.ACTIVE
        distressEnd = -1
        shares = (shares * rng.nextDouble(2.0, 4.0)).roundToLong()
        pendingStrategy = StrategyType.TURNAROUND
        schedule(AgendaItem(day, -1, AgendaKind.JUMP, headline = "$name rescued by emergency bailout; shareholders heavily diluted", jump = ln(1 - rng.nextDouble(0.40, 0.60)), category = NewsCategory.DISTRESS))
    }

    fun takePrivateOffer(day: Int, price: Double): Double {
        val offer = ((if (closes.isEmpty()) price else average20) * rng.nextDouble(1.20, 1.40)).roundToLong()
        distressEnd = -1
        return startDeal(day, -1, price, null, offer, 3, completes = true, takePrivate = true)
    }

    fun randomDays(from: Int, until: Int) = rng.nextInt(from, until)

    // ---- Earnings & dividends ---------------------------------------------------

    private fun releaseEarnings(day: Int, strategy: StrategyEngine): Double {
        val expectedGrowth = strategy.current.driftAt(0.0) * QUARTER_DAYS / 252.0
        val realized = ln((fundamental / fundamentalAtEarnings).coerceIn(0.05, 20.0)) + rng.gaussian() * 0.05
        earningsPower = (earningsPower * exp(realized)).coerceAtLeast(0.5)
        val troubled = status == CompanyStatus.DISTRESS || strategy.current.type == StrategyType.DEATH_SPIRAL
        val losses = if (troubled) earningsPower * rng.nextDouble(0.5, 1.5) else 0.0
        val actual = (earningsPower - losses).roundToLong()
        val estimate = consensus
        val surprise = (actual - estimate).toDouble() / maxOf(1L, abs(estimate))
        var jump = (surprise * 1.2).coerceIn(-0.20, 0.20) + rng.gaussian() * 0.01

        val verdict = when {
            surprise > 0.02 -> "beats estimates"
            surprise < -0.02 -> "misses estimates"
            else -> "meets estimates"
        }
        var headline = "$name ($ticker) $verdict: EPS ${Headlines.money(actual)} vs ${Headlines.money(estimate)} expected"
        var guidance = 0.0
        if (rng.chance(0.3)) {
            guidance = rng.nextDouble(0.03, 0.12) * if (rng.chance(0.5 + surprise.coerceIn(-0.4, 0.4))) 1 else -1
            jump += guidance.coerceIn(-0.05, 0.05)
            headline += if (guidance > 0) "; raises guidance" else "; cuts guidance"
        }

        // Outcome pattern: the market's longer reaction.
        if (rng.chance(0.35)) {
            val beat = surprise > 0
            val next = if (beat) rng.weighted(mapOf(StrategyType.STEADY_GROWTH to 3.0, StrategyType.PARABOLIC to 1.0, StrategyType.STAGNANT to 2.0, StrategyType.SLOW_DECLINE to 1.0))
                else rng.weighted(mapOf(
                    StrategyType.SLOW_DECLINE to 3.0, StrategyType.TURNAROUND to 1.5, StrategyType.STAGNANT to 1.5,
                    StrategyType.DEATH_SPIRAL to if (surprise < -0.25) 1.0 else 0.1,
                ))
            strategy.force(StrategyInstance.sample(next, rng))
        }

        epsHistory.addLast(actual)
        while (epsHistory.size > 4) epsHistory.removeFirst()
        consensus = (earningsPower * exp(expectedGrowth + guidance + rng.gaussian() * 0.03)).roundToLong().coerceAtLeast(1)
        fundamentalAtEarnings = fundamental
        nextEarningsDay = day + QUARTER_DAYS
        news += NewsDraft(day, -1, ticker, sector, NewsCategory.EARNINGS, headline, jump)
        declareDividend(day, surprise, actual)
        return jump
    }

    private fun declareDividend(day: Int, surprise: Double, actual: Long) {
        if (!dividendPayer || dividend <= 0 || status != CompanyStatus.ACTIVE) return
        when {
            actual <= 0 -> return suspendDividend(day, "after reporting a loss")
            !aristocrat && surprise < -0.25 && rng.chance(0.5) -> {
                dividend = (dividend / 2).coerceAtLeast(1)
                news += NewsDraft(day, -1, ticker, sector, NewsCategory.DIVIDEND, "$name halves its dividend", -0.02)
            }
            aristocrat -> dividend = (dividend * rng.nextDouble(1.01, 1.03)).roundToLong().coerceAtLeast(dividend + 1)
        }
        declaredDividend = dividend
        exDay = day + 3
        payDay = day + 6
        news += NewsDraft(day, -1, ticker, sector, NewsCategory.DIVIDEND,
            "$name declares quarterly dividend of ${Headlines.money(dividend)} per share (ex-dividend in 3 sessions)")
    }

    private fun suspendDividend(day: Int, why: String) {
        dividendPayer = false
        dividend = 0
        exDay = -1
        news += NewsDraft(day, -1, ticker, sector, NewsCategory.DIVIDEND, "$name suspends its dividend $why", -0.03)
    }

    // ---- Snapshot ---------------------------------------------------------------

    fun writeTo(out: DataOutputStream) {
        out.writeLong(rng.state); out.writeLong(shares); out.writeDouble(earningsPower)
        out.writeList(epsHistory.toList()) { writeLong(it) }
        out.writeLong(consensus); out.writeInt(nextEarningsDay); out.writeDouble(fundamental); out.writeDouble(fundamentalAtEarnings)
        out.writeBoolean(dividendPayer); out.writeLong(dividend); out.writeInt(exDay); out.writeInt(payDay); out.writeLong(declaredDividend)
        out.writeUTF(status.name); out.writeInt(distressEnd)
        out.writeBoolean(deal != null)
        deal?.let { out.writeLong(it.offer); out.writeInt(it.closeDay); out.writeBoolean(it.completes); out.writeBoolean(it.takePrivate) }
        out.writeList(closes.toList()) { writeLong(it) }
        out.writeInt(daysUnderDollar); out.writeInt(splitDay); out.writeDouble(splitRatio)
        out.writeUTF(pendingStrategy?.name ?: "")
        out.writeList(agenda) { a ->
            writeInt(a.day); writeInt(a.tick); writeUTF(a.kind.name); writeUTF(a.type?.name ?: ""); writeUTF(a.headline ?: "")
            writeDouble(a.jump); writeUTF(a.category.name)
        }
    }

    fun readFrom(input: DataInputStream) {
        rng = Rng.restore(input.readLong()); shares = input.readLong(); earningsPower = input.readDouble()
        epsHistory.clear(); epsHistory.addAll(input.readList { readLong() })
        consensus = input.readLong(); nextEarningsDay = input.readInt(); fundamental = input.readDouble(); fundamentalAtEarnings = input.readDouble()
        dividendPayer = input.readBoolean(); dividend = input.readLong(); exDay = input.readInt(); payDay = input.readInt(); declaredDividend = input.readLong()
        status = CompanyStatus.valueOf(input.readUTF()); distressEnd = input.readInt()
        deal = if (input.readBoolean()) Deal(input.readLong(), input.readInt(), input.readBoolean(), input.readBoolean()) else null
        closes.clear(); closes.addAll(input.readList { readLong() })
        daysUnderDollar = input.readInt(); splitDay = input.readInt(); splitRatio = input.readDouble()
        pendingStrategy = input.readUTF().takeIf { it.isNotEmpty() }?.let(StrategyType::valueOf)
        agenda.clear()
        agenda += input.readList {
            AgendaItem(readInt(), readInt(), AgendaKind.valueOf(readUTF()), readUTF().takeIf { it.isNotEmpty() }?.let(EventType::valueOf),
                readUTF().takeIf { it.isNotEmpty() }, readDouble(), NewsCategory.valueOf(readUTF()))
        }
    }

    companion object {
        /** Game days between earnings (~4 real weeks). */
        const val QUARTER_DAYS = 48
        private val NEVER_PAY = setOf(StrategyType.PARABOLIC, StrategyType.VOLATILE, StrategyType.DEATH_SPIRAL, StrategyType.BLOW_OFF_TOP)
        private val SECTOR_PE = mapOf(
            Sector.TECH to 30.0, Sector.CONSUMER to 20.0, Sector.ENERGY to 12.0,
            Sector.PHARMA to 24.0, Sector.FINANCE to 13.0, Sector.INDUSTRIAL to 18.0,
        )
    }
}
