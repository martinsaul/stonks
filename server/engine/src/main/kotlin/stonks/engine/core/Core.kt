package stonks.engine.core

/** Prices are integer cents. */
typealias Cents = Long

enum class Side {
    BUY, SELL;

    val opposite: Side get() = if (this == BUY) SELL else BUY
}

enum class TraderKind { MARKET_MAKER, BACKGROUND, PLAYER }

/** Identifies who owns an order. NPC traders share a single id per kind. */
data class TraderId(val kind: TraderKind, val id: Long = 0) {
    companion object {
        val MARKET_MAKER = TraderId(TraderKind.MARKET_MAKER)
        val BACKGROUND = TraderId(TraderKind.BACKGROUND)
        fun player(id: Long) = TraderId(TraderKind.PLAYER, id)
    }
}

fun Double.toCentsFloor(): Cents = maxOf(1L, kotlin.math.floor(this).toLong())
fun Double.toCentsCeil(): Cents = maxOf(1L, kotlin.math.ceil(this).toLong())
fun Cents.toDollars(): Double = this / 100.0
