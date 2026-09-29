package stonks.engine.sim

import stonks.engine.clock.Session
import stonks.engine.clock.SessionKind
import stonks.engine.company.Company
import stonks.engine.company.Sector
import stonks.engine.core.Rng
import stonks.engine.strategy.StrategyInstance
import stonks.engine.strategy.StrategyType
import java.time.Instant

object Fixtures {
    fun company(type: StrategyType = StrategyType.STEADY_GROWTH, price: Long = 10_000) = Company(
        ticker = "TEST",
        name = "Test Corp",
        sector = Sector.TECH,
        sharesOutstanding = 1_000_000_000,
        initialPrice = price,
        marketBeta = 1.0,
        sectorBeta = 1.0,
        initialStrategy = StrategyInstance.sample(type, Rng(7)),
    )

    fun session(open: Instant = Instant.parse("2026-09-29T10:00:00Z"), ticks: Int = 5400) =
        Session(SessionKind.WEEKDAY_A, open, open.plusSeconds(ticks * 5L))

    fun factors(ticks: Int, seed: Long = 3) = SessionFactors.generate(Rng(seed), ticks)
}
