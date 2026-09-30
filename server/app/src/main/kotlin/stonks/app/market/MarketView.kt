package stonks.app.market

import kotlinx.serialization.Serializable

// Wire format for market data. All prices are integer cents.

@Serializable
data class SessionInfo(
    val state: String,
    val kind: String? = null,
    val opensAt: String? = null,
    val closesAt: String? = null,
    val nextOpen: String? = null,
    val nextKind: String? = null,
)

@Serializable
data class Quote(
    val ticker: String,
    val name: String,
    val sector: String,
    val last: Long,
    val bid: Long?,
    val ask: Long?,
    val bidSize: Long?,
    val askSize: Long?,
    val prevClose: Long?,
    val open: Long?,
    val high: Long?,
    val low: Long?,
    val volume: Long,
    val change: Long?,
    val changePct: Double?,
    val marketCap: Long,
)

@Serializable
data class IndexQuote(val name: String, val value: Double, val prevClose: Double?, val change: Double?, val changePct: Double?)

@Serializable
data class DepthLevel(val price: Long, val size: Long)

@Serializable
data class Depth(val bids: List<DepthLevel>, val asks: List<DepthLevel>)

@Serializable
data class CandleDto(val time: String, val open: Long, val high: Long, val low: Long, val close: Long, val volume: Long)

@Serializable
data class NewsDto(
    val id: Long,
    val time: String,
    val day: Int,
    val ticker: String?,
    val sector: String?,
    val category: String,
    val headline: String,
    /** positive, negative or neutral. */
    val tone: String,
)

/** Company fundamentals and corporate status. Per-share amounts in cents. */
@Serializable
data class Fundamentals(
    val shares: Long,
    val epsTtm: Long?,
    val pe: Double?,
    /** Quarterly dividend per share (0 if none). */
    val dividend: Long,
    /** Annual dividend yield, percent. */
    val dividendYield: Double?,
    val exDividendDate: String?,
    val dividendPayDate: String?,
    val nextEarnings: String?,
    val consensusEps: Long,
    /** ACTIVE, DISTRESS or DEAL_PENDING. */
    val status: String,
    val dealOffer: Long?,
    val splitDate: String?,
    val splitRatio: Double?,
    val shortInterest: Long,
    val shortInterestPct: Double,
    /** Annualized borrow fee for shorts, percent. */
    val borrowFee: Double,
)

@Serializable
data class CalendarEvent(
    val date: String,
    val day: Int,
    /** EARNINGS, EX_DIVIDEND, DIVIDEND_PAY, SPLIT, DEAL_CLOSE, IPO, RATE_DECISION. */
    val kind: String,
    val ticker: String? = null,
    val name: String? = null,
    val detail: String,
)

/** A bond offering; dates are session opens. */
@Serializable
data class BondOfferingDto(
    val id: Long,
    val name: String,
    /** Total return at maturity, percent. */
    val returnPct: Double,
    val capPerPlayer: Long,
    val open: Boolean,
    val closesAt: String?,
    val maturesAt: String?,
)

@Serializable
data class DelistingDto(val ticker: String, val name: String, val day: Int, val price: Long, val reason: String)

/** Everything published after each tick. Immutable; shared across threads. */
data class MarketState(
    val time: java.time.Instant,
    val session: SessionInfo,
    val regime: String,
    val index: IndexQuote,
    val quotes: List<Quote>,
    val depth: Map<String, Depth>,
    /** In-progress 1-minute candle per ticker (open session only). */
    val liveMinute: Map<String, CandleDto>,
    val fundamentals: Map<String, Fundamentals> = emptyMap(),
    val calendar: List<CalendarEvent> = emptyList(),
    val delistings: List<DelistingDto> = emptyList(),
    /** Benchmark (central bank) rate, percent. */
    val benchmarkRate: Double = 0.0,
    val latestNewsId: Long = 0,
    val bondOfferings: List<BondOfferingDto> = emptyList(),
) {
    val quotesByTicker: Map<String, Quote> = quotes.associateBy { it.ticker }
}
