package stonks.app.admin

import kotlinx.serialization.Serializable

@Serializable
data class TickerAdminDto(
    val ticker: String, val name: String, val sector: String, val last: Long, val strategy: String, val status: String,
    val halted: Boolean, val volatility: Double, val depth: Double, val borrowPool: Double?,
    val pendingSplit: String?, val deal: Long?,
)

@Serializable
data class ScheduledDto(val date: String?, val day: Int, val tick: Int, val kind: String, val target: String?, val detail: String)

@Serializable
data class AdminOverview(
    val day: Int,
    val tick: Int?,
    val open: Boolean,
    val regime: String,
    val benchmarkRate: Double,
    val nextRateDecision: String?,
    val tickers: List<TickerAdminDto>,
    val scheduled: List<ScheduledDto>,
    val bonds: List<stonks.app.market.BondOfferingDto>,
    val players: Int,
    val strategies: List<String>,
    val events: List<String>,
    val regimes: List<String>,
    val sectors: List<String>,
)

@Serializable data class AdminActionResult(val ok: Boolean, val message: String? = null)
@Serializable data class PlayerDetail(
    val player: stonks.app.admin.PlayerRow,
    val portfolio: stonks.app.trading.PortfolioDto?,
    val sessions: List<SessionRow>,
    val fills: List<FillRow>,
    val economy: List<EconomyRow>,
    val flags: List<FlagDto>,
    val review: String?,
)
@Serializable data class BanRequest(val banned: Boolean, val reason: String? = null)
@Serializable data class ReviewRequest(val status: String, val note: String? = null)
@Serializable data class RollbackRequest(val snapshotId: Long, val confirm: String)
