package stonks.engine.book

import stonks.engine.core.Cents
import stonks.engine.core.Side
import kotlin.math.abs

data class AuctionResult(val price: Cents, val fills: List<Fill>)

/**
 * Single-price call auction used at session open.
 *
 * The clearing price maximizes executable volume; ties are broken by the smallest
 * order imbalance, then by proximity to [referencePrice]. All matched orders trade at
 * that one price. Unmatched quantity stays on the orders for the caller to handle.
 */
object Auction {

    fun uncross(orders: List<Order>, referencePrice: Cents): AuctionResult? {
        val buys = orders.filter { it.side == Side.BUY && it.remaining > 0 }
        val sells = orders.filter { it.side == Side.SELL && it.remaining > 0 }
        if (buys.isEmpty() || sells.isEmpty()) return null

        val candidates = (orders.mapNotNull { it.limitPrice } + referencePrice).toSortedSet()
        var bestPrice: Cents? = null
        var bestVolume = 0L
        var bestImbalance = Long.MAX_VALUE
        for (p in candidates) {
            val demand = buys.filter { it.accepts(p) }.sumOf { it.remaining }
            val supply = sells.filter { it.accepts(p) }.sumOf { it.remaining }
            val volume = minOf(demand, supply)
            if (volume == 0L) continue
            val imbalance = abs(demand - supply)
            val better = volume > bestVolume ||
                (volume == bestVolume && imbalance < bestImbalance) ||
                (volume == bestVolume && imbalance == bestImbalance &&
                    abs(p - referencePrice) < abs(bestPrice!! - referencePrice))
            if (better) {
                bestPrice = p
                bestVolume = volume
                bestImbalance = imbalance
            }
        }
        val price = bestPrice ?: return null

        // Priority: market orders first, then most aggressive limit, then arrival.
        val buyQueue = ArrayDeque(buys.filter { it.accepts(price) }
            .sortedWith(compareBy<Order>({ it.limitPrice != null }, { -(it.limitPrice ?: 0) }, { it.sequence })))
        val sellQueue = ArrayDeque(sells.filter { it.accepts(price) }
            .sortedWith(compareBy<Order>({ it.limitPrice != null }, { it.limitPrice ?: 0 }, { it.sequence })))

        val fills = ArrayList<Fill>()
        var toFill = bestVolume
        while (toFill > 0) {
            val b = buyQueue.first()
            val s = sellQueue.first()
            val qty = minOf(b.remaining, s.remaining, toFill)
            b.remaining -= qty
            s.remaining -= qty
            toFill -= qty
            // Auction fills have no aggressor; record the later-arriving order as taker.
            val (taker, maker) = if (b.sequence >= s.sequence) b to s else s to b
            fills += Fill(price, qty, taker.side, taker.id, taker.trader, maker.id, maker.trader)
            if (b.isDone) buyQueue.removeFirst()
            if (s.isDone) sellQueue.removeFirst()
        }
        return AuctionResult(price, fills)
    }
}
