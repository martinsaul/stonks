package stonks.engine.world

import stonks.engine.company.Sector

enum class NewsCategory { EARNINGS, DIVIDEND, CORPORATE, REGULATORY, LEGAL, ANALYST, RUMOR, SECTOR, MACRO, DEAL, LISTING, SPLIT, DISTRESS }

/**
 * A market headline. [tick] is the tick it broke at; -1 = before the session opened.
 * [sentiment] is the rough price impact (log return), 0 for neutral items.
 */
data class NewsItem(
    val id: Long,
    val day: Int,
    val tick: Int,
    val ticker: String?,
    val sector: Sector?,
    val category: NewsCategory,
    val headline: String,
    val sentiment: Double,
)

/** A headline before the market assigns its id. */
data class NewsDraft(
    val day: Int,
    val tick: Int,
    val ticker: String?,
    val sector: Sector?,
    val category: NewsCategory,
    val headline: String,
    val sentiment: Double = 0.0,
)
