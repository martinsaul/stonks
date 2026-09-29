package stonks.engine.company

import stonks.engine.core.Rng
import stonks.engine.strategy.StrategyInstance
import stonks.engine.strategy.StrategyType
import stonks.engine.strategy.Transitions

enum class Sector { TECH, CONSUMER, ENERGY, PHARMA, FINANCE, INDUSTRIAL }

data class Company(
    val ticker: String,
    val name: String,
    val sector: Sector,
    val sharesOutstanding: Long,
    /** Initial price in cents. */
    val initialPrice: Long,
    /** Sensitivity to the market-wide factor. */
    val marketBeta: Double,
    /** Sensitivity to the sector factor. */
    val sectorBeta: Double,
    val initialStrategy: StrategyInstance,
)

/** The launch roster of parody companies. */
object CompanyCatalog {
    private val roster: List<Triple<String, String, Sector>> = listOf(
        Triple("FOOF", "Foofle", Sector.TECH),
        Triple("MHRD", "Macrohard", Sector.TECH),
        Triple("PEAR", "Pear Inc.", Sector.TECH),
        Triple("MTBK", "Metabook", Sector.TECH),
        Triple("NVDT", "Nvidiot", Sector.TECH),
        Triple("OTTL", "Outtel", Sector.TECH),
        Triple("MRCL", "Miracle Systems", Sector.TECH),
        Triple("SLFC", "Salesfarce", Sector.TECH),
        Triple("ADBO", "Adobo", Sector.TECH),
        Triple("NTFX", "Notflix", Sector.TECH),
        Triple("AMZG", "Amazingon", Sector.CONSUMER),
        Triple("STBK", "Starbocks", Sector.CONSUMER),
        Triple("MCDN", "McDonut's", Sector.CONSUMER),
        Triple("NKEY", "Nikey", Sector.CONSUMER),
        Triple("COOL", "Coca-Cool", Sector.CONSUMER),
        Triple("WLMT", "Wallmort", Sector.CONSUMER),
        Triple("DSNY", "Disnay", Sector.CONSUMER),
        Triple("CSTC", "Costclub", Sector.CONSUMER),
        Triple("XOFF", "Exxoff Mobile", Sector.ENERGY),
        Triple("SHIL", "Shill", Sector.ENERGY),
        Triple("CHVF", "Chevroff", Sector.ENERGY),
        Triple("BEEP", "BeePee", Sector.ENERGY),
        Triple("FLAR", "Solar Flair", Sector.ENERGY),
        Triple("WNDF", "Windfall Energy", Sector.ENERGY),
        Triple("FRAK", "Fracktastic", Sector.ENERGY),
        Triple("PFZZ", "Pfizzer", Sector.PHARMA),
        Triple("MDNS", "Modernish", Sector.PHARMA),
        Triple("JNJS", "Johnson & Johnsons", Sector.PHARMA),
        Triple("MRKY", "Mercky", Sector.PHARMA),
        Triple("AZNF", "AstraZenoff", Sector.PHARMA),
        Triple("PLCB", "Placebo Labs", Sector.PHARMA),
        Triple("PLLS", "Pillsbury Pharma", Sector.PHARMA),
        Triple("GSUX", "Goldman Sucks", Sector.FINANCE),
        Triple("JPMG", "J.P. Morgone", Sector.FINANCE),
        Triple("BOAC", "Bank of Americano", Sector.FINANCE),
        Triple("WFGN", "Wells Fargone", Sector.FINANCE),
        Triple("MBAG", "Moneybags Capital", Sector.FINANCE),
        Triple("RBHD", "Robbinghood", Sector.FINANCE),
        Triple("VSGE", "Visage", Sector.FINANCE),
        Triple("BRKS", "BlackRocks", Sector.FINANCE),
        Triple("BOIN", "Boing", Sector.INDUSTRIAL),
        Triple("CATP", "Caterpillow", Sector.INDUSTRIAL),
        Triple("GELK", "General Electrick", Sector.INDUSTRIAL),
        Triple("LKMT", "Lockheed Martini", Sector.INDUSTRIAL),
        Triple("SPCY", "SpaceY", Sector.INDUSTRIAL),
        Triple("FDUP", "Fedups", Sector.INDUSTRIAL),
        Triple("UPSE", "UPSet", Sector.INDUSTRIAL),
        Triple("HNYW", "Honeywall", Sector.INDUSTRIAL),
        Triple("DJHN", "Deere John", Sector.INDUSTRIAL),
        Triple("TSLR", "Tesler", Sector.INDUSTRIAL),
    )

    fun generate(rng: Rng): List<Company> = roster.map { (ticker, name, sector) ->
        val price = rng.logNormal(median = 80.0, sigma = 0.9).coerceIn(5.0, 900.0)
        val marketCap = rng.logNormal(median = 60e9, sigma = 1.2).coerceIn(2e9, 2e12)
        val strategyType: StrategyType = rng.weighted(Transitions.initial)
        Company(
            ticker = ticker,
            name = name,
            sector = sector,
            sharesOutstanding = (marketCap / price).toLong(),
            initialPrice = (price * 100).toLong(),
            marketBeta = rng.nextDouble(0.6, 1.5),
            sectorBeta = rng.nextDouble(0.5, 1.2),
            initialStrategy = StrategyInstance.sample(strategyType, rng),
        )
    }
}
