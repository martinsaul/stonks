package stonks.engine.book

import stonks.engine.core.Cents
import stonks.engine.core.Side
import stonks.engine.core.TraderId

enum class OrderType { MARKET, LIMIT }

enum class TimeInForce { DAY, GTC, IOC }

class Order(
    val id: Long,
    val trader: TraderId,
    val side: Side,
    val type: OrderType,
    val quantity: Long,
    val limitPrice: Cents? = null,
    val tif: TimeInForce = TimeInForce.DAY,
) {
    init {
        require(quantity > 0) { "quantity must be positive" }
        require((type == OrderType.LIMIT) == (limitPrice != null)) { "limit price required iff LIMIT" }
        require(limitPrice == null || limitPrice > 0) { "limit price must be positive" }
    }

    var remaining: Long = quantity
        internal set

    /** Arrival sequence; lower is earlier (time priority). */
    var sequence: Long = 0
        internal set

    val filled: Long get() = quantity - remaining
    val isDone: Boolean get() = remaining == 0L

    /** Whether this order is willing to trade at [price]. */
    fun accepts(price: Cents): Boolean = when {
        limitPrice == null -> true
        side == Side.BUY -> price <= limitPrice
        else -> price >= limitPrice
    }

    override fun toString() = "Order($id $trader $side $type $remaining/$quantity @${limitPrice ?: "MKT"} $tif)"
}

data class Fill(
    val price: Cents,
    val quantity: Long,
    /** Side of the aggressor (taker). */
    val takerSide: Side,
    val takerOrderId: Long,
    val takerTrader: TraderId,
    val makerOrderId: Long,
    val makerTrader: TraderId,
)
