package stonks.engine.sim

/** Tunables for the simulated market. All admin-adjustable in later milestones. */
data class MarketConfig(
    /** Market-maker price levels per side. */
    val makerLevels: Int = 10,
    /** Base half-spread in basis points. */
    val baseHalfSpreadBps: Double = 2.0,
    /** Additional half-spread per unit of annualized volatility, in bps. */
    val volHalfSpreadBps: Double = 10.0,
    /**
     * Notional per market-maker level as a fraction of market cap. Scales how much
     * money it takes to move a price; tuned from daily active players later.
     */
    val liquidityFraction: Double = 1e-6,
    /** Level size growth per step away from the touch. */
    val levelGrowth: Double = 0.5,
    /** Mean background market orders per tick. */
    val backgroundRate: Double = 1.0,
    /** Median background order notional as a fraction of one maker level. */
    val backgroundSizeFraction: Double = 0.15,
    /** Log-normal dispersion of background order size. */
    val backgroundSizeSigma: Double = 0.6,
    /** Annualized volatility of the market-wide factor. */
    val marketFactorVol: Double = 0.12,
    /** Annualized volatility of each sector factor. */
    val sectorFactorVol: Double = 0.10,
    /** Share of a game day's variance realized while the market is closed. */
    val gapVarianceDays: Double = 0.25,
    val retention: CandleRetention = CandleRetention(),
)

/** How many candles of each resolution to keep in memory. */
data class CandleRetention(
    /** 5-second bars: ~10 weekday sessions. */
    val ticks: Int = 10 * 5400,
    /** 1-minute bars: ~90 weekday sessions. */
    val minutes: Int = 90 * 450,
    val days: Int = Int.MAX_VALUE,
)
