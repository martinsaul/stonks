package stonks.engine.sim

import stonks.engine.company.Sector
import stonks.engine.core.Rng

/**
 * Market-wide phase. Adds annualized drift (scaled by each stock's market beta) and
 * scales volatility. [expectedDays] is the mean time spent in the phase. With the
 * transition odds in [Market], the long-run regime drift is roughly zero.
 */
enum class MarketRegime(val driftAdd: Double, val volMultiplier: Double, val expectedDays: Double) {
    NEUTRAL(0.0, 1.0, 60.0),
    BULL(0.20, 0.9, 60.0),
    BEAR(-0.20, 1.2, 45.0),
    CRASH(-2.5, 2.2, 8.0),
    BUBBLE(1.0, 1.4, 20.0),
}

/**
 * Correlated shocks shared by all tickers during one session, generated up front so
 * tickers can then be simulated independently (and in parallel) deterministically.
 */
class SessionFactors(
    val market: DoubleArray,
    val sector: Map<Sector, DoubleArray>,
    val gapMarket: Double,
    val gapSector: Map<Sector, Double>,
) {
    companion object {
        fun generate(rng: Rng, ticks: Int): SessionFactors = SessionFactors(
            market = DoubleArray(ticks) { rng.gaussian() },
            sector = Sector.entries.associateWith { DoubleArray(ticks) { rng.gaussian() } },
            gapMarket = rng.gaussian(),
            gapSector = Sector.entries.associateWith { rng.gaussian() },
        )
    }
}
