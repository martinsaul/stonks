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
    /** Every trading account's portfolio, by account id. */
    val portfolios: Map<Long, stonks.app.trading.PortfolioDto> = emptyMap(),
) {
    val quotesByTicker: Map<String, Quote> = quotes.associateBy { it.ticker }
}
