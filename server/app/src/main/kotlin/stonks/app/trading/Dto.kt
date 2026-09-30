package stonks.app.trading

import kotlinx.serialization.Serializable
import stonks.engine.book.TimeInForce
import stonks.engine.core.Side
import stonks.engine.trading.LegSpec
import stonks.engine.trading.OrderKind
import stonks.engine.trading.OrderRequest
import stonks.engine.trading.OrderUpdate
import stonks.engine.trading.Structure

// Wire format. Prices are integer cents.

@Serializable
data class LegRequest(
    val side: String,
    val quantity: Long,
    val type: String,
    val limitPrice: Long? = null,
    val stopPrice: Long? = null,
    val trailAmount: Long? = null,
    val trailPercent: Double? = null,
    val limitOffset: Long? = null,
    val timeInForce: String = "DAY",
    /** Algo orders (TWAP/VWAP): execution window in minutes. */
    val durationMinutes: Int? = null,
)

@Serializable
data class PlaceOrderRequest(val ticker: String, val structure: String = "SINGLE", val legs: List<LegRequest>)

@Serializable
data class PlaceOrderResponse(val groupId: Long, val orderIds: List<Long>, val status: String, val executesAt: String?)

@Serializable
data class OrderDto(
    val id: Long,
    val groupId: Long,
    val ticker: String,
    val side: String,
    val type: String,
    val quantity: Long,
    val filled: Long,
    val avgPrice: Double?,
    val limitPrice: Long?,
    val stopPrice: Long?,
    val status: String,
    val reason: String?,
    val liquidation: Boolean,
    val updatedAt: String,
)

@Serializable
data class FillDto(
    val id: String,
    val orderId: Long,
    val ticker: String,
    val side: String,
    val quantity: Long,
    val price: Long,
    val commission: Long,
    val realized: Long,
    val maker: Boolean,
    val liquidation: Boolean,
    val at: String,
)

@Serializable
data class PositionDto(
    val ticker: String,
    val quantity: Long,
    val avgPrice: Double,
    val last: Long,
    val marketValue: Long,
    val unrealized: Long,
    val unrealizedPct: Double,
    val dayChange: Long?,
)

@Serializable
data class BondDto(val offeringId: Long, val name: String, val principal: Long, val payout: Long, val maturityDay: Int, val maturesAt: String?)

/** Economy standing (survives resets, except bonds). */
@Serializable
data class StandingDto(
    /** Equity plus bonds at face value. */
    val netWorth: Long,
    val startingCash: Long,
    val cashLevel: Int,
    val nextUpgradeCost: Long?,
    /** Outstanding badges of shame. */
    val shame: Int,
    val shameEver: Int,
    val eternalShame: Boolean,
    /** Cost to clear the highest badge (null if none or Eternal Shame). */
    val clearCost: Long?,
    val bankruptcies: Int,
    /** Net worth is below $1k (the weekly claim is allowed when [nextClaimAt] has passed). */
    val claimEligible: Boolean,
    val nextClaimAt: String?,
    val nextResetAt: String?,
    /** Forced bankruptcy at or below this net worth. */
    val gameOverAt: Long,
    /** Starting cash of the current run (since opening, reset or bankruptcy). */
    val runStartCash: Long,
    val bonds: List<BondDto>,
)

@Serializable
data class NoticeDto(val seq: Long, val kind: String, val text: String, val at: String)

@Serializable
data class PortfolioDto(
    val accountId: Long,
    val plan: String,
    val cash: Long,
    val equity: Long,
    val longValue: Long,
    val shortValue: Long,
    /** Equity not committed to margin requirements or open orders. */
    val availableEquity: Long,
    /** Largest long purchase possible now, at the plan's leverage. */
    val buyingPower: Long,
    val marginDebt: Long,
    val maintenanceRequirement: Long,
    val initialMargin: Double,
    val maintenanceMargin: Double,
    val lifetimeRealized: Long,
    val commissionsPaid: Long,
    val interestPaid: Long,
    val positions: List<PositionDto>,
    val standing: StandingDto,
    val openOrders: List<OrderDto>,
    val notices: List<NoticeDto>,
)

class RequestError(message: String) : RuntimeException(message)

/** Converts an API request into an engine request, validating field values. */
fun PlaceOrderRequest.toEngine(): OrderRequest {
    val structure = enumOr<Structure>(structure, "structure")
    val legs = legs.map { l ->
        LegSpec(
            side = enumOr<Side>(l.side, "side"),
            quantity = l.quantity,
            kind = enumOr<OrderKind>(l.type, "type"),
            limit = l.limitPrice,
            stop = l.stopPrice,
            trailAmount = l.trailAmount,
            trailPercent = l.trailPercent,
            limitOffset = l.limitOffset,
            tif = enumOr<TimeInForce>(l.timeInForce, "timeInForce"),
            durationTicks = l.durationMinutes?.let { it * 12 },
        )
    }
    return OrderRequest(ticker.uppercase(), structure, legs).also { r -> r.validate()?.let { throw RequestError(it) } }
}

private inline fun <reified E : Enum<E>> enumOr(value: String, field: String): E =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw RequestError("Invalid $field '$value' (expected one of ${enumValues<E>().joinToString { it.name }}).")

fun OrderUpdate.toDto(at: String) = OrderDto(
    orderId, groupId, ticker, side.name, kind.name, quantity, filled, averagePrice, limit, stop, status.name, reason, liquidation, at,
)
