package stonks.engine.world

import stonks.engine.company.Company
import stonks.engine.company.Sector
import stonks.engine.core.Rng
import stonks.engine.strategy.StrategyInstance
import stonks.engine.strategy.Transitions

/** Makes replacement companies for delisted ones: parody names, unique tickers. */
object Ipo {
    private val stems = mapOf(
        Sector.TECH to listOf("Byte", "Cloud", "Quantum", "Pixel", "Data", "Cyber", "Nano", "Hyper", "Giga", "Blockchain", "Synergy", "Algo"),
        Sector.CONSUMER to listOf("Snack", "Trendy", "Comfy", "Munch", "Glam", "Cozy", "Sip", "Shop", "Vibe", "Crunch", "Fluff", "Brew"),
        Sector.ENERGY to listOf("Petro", "Sun", "Gust", "Volt", "Crude", "Fusion", "Gas", "Spark", "Drill", "Ember", "Hydro", "Coal"),
        Sector.PHARMA to listOf("Cure", "Pill", "Gene", "Vita", "Dose", "Remedi", "Immuno", "Tonic", "Placebi", "Serum", "Ouch", "Heal"),
        Sector.FINANCE to listOf("Coin", "Hedge", "Yield", "Ledger", "Vault", "Bull", "Margin", "Debit", "Leverage", "Stonk", "Moola", "Bond"),
        Sector.INDUSTRIAL to listOf("Gear", "Bolt", "Rocket", "Crane", "Steel", "Forge", "Drone", "Rivet", "Widget", "Torque", "Pallet", "Girder"),
    )
    private val endings = listOf("ly", "ify", "topia", "ster", "verse", "tronix", "works", "flop", "zilla", "nomics", "hub", "rama", "o", "ium")
    private val suffixes = listOf("", "", "", " Inc.", " Holdings", " Labs", " Group", " Corp.", " & Co.")

    fun create(day: Int, usedTickers: Set<String>, usedNames: Set<String>, rng: Rng, sector: Sector? = null): Company {
        val s = sector ?: rng.pick(Sector.entries)
        var name: String
        var tries = 0
        do {
            name = rng.pick(stems.getValue(s)) + rng.pick(endings) + rng.pick(suffixes)
        } while (name in usedNames && ++tries < 50)
        if (name in usedNames) name += " ${day}"
        val price = rng.logNormal(median = 25.0, sigma = 0.6).coerceIn(5.0, 200.0)
        val marketCap = rng.logNormal(median = 8e9, sigma = 1.0).coerceIn(1e9, 2e11)
        return Company(
            ticker = tickerFor(name, usedTickers, rng),
            name = name,
            sector = s,
            sharesOutstanding = (marketCap / price).toLong(),
            initialPrice = (price * 100).toLong(),
            marketBeta = rng.nextDouble(0.8, 1.8),
            sectorBeta = rng.nextDouble(0.5, 1.2),
            initialStrategy = StrategyInstance.sample(rng.weighted(Transitions.initial), rng),
        )
    }

    /** First letters of the name, then variations, then random letters: always unique. */
    fun tickerFor(name: String, used: Set<String>, rng: Rng): String {
        val letters = name.uppercase().filter { it in 'A'..'Z' }
        val candidates = buildList {
            if (letters.length >= 4) add(letters.take(4))
            val consonants = letters.first() + letters.drop(1).filter { it !in "AEIOU" }
            if (consonants.length >= 4) add(consonants.take(4))
            if (letters.length >= 3) add(letters.take(3))
            for (c in letters.drop(1)) add(letters.take(3) + c)
        }
        candidates.firstOrNull { it !in used }?.let { return it }
        while (true) {
            val t = letters.take(1) + (1..3).map { 'A' + rng.nextInt(0, 26) }.joinToString("")
            if (t !in used) return t
        }
    }
}
