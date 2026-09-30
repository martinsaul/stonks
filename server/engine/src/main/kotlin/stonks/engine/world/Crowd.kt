package stonks.engine.world

import stonks.engine.core.Rng
import stonks.engine.core.Side
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.writeList
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.pow
import kotlin.math.roundToLong

/** A virtual trader's market order, due at ([day], [tick]). */
data class CrowdOrder(val day: Int, val tick: Int, val side: Side, val quantity: Long)

/**
 * Copycats and inversecats (docs/DESIGN.md, "Virtual traders"). After a player's
 * trade, a random crowd may pile in behind it (copycats) or against it (inversecats),
 * spread over the next few ticks. Nothing about the crowd is guaranteed.
 *
 * Tuned so the expected copied volume is below the original (~0.6x on average, heavy
 * tailed): gaming the crowd costs more in slippage than it earns on average.
 */
class Crowd(private var rng: Rng) {
    /** 0..1: how much attention the stock is getting (big trades, news). */
    var hype = 0.0
        private set
    private val orders = ArrayList<CrowdOrder>()

    /**
     * A player's order traded [quantity] at about [price].
     *
     * @param depthNotional value of the visible book on one side, for scale
     * @param fame 0..1, successful players attract more copycats
     * @param shame 0..1, shamed players attract more inversecats
     */
    fun onPlayerTrade(day: Int, tick: Int, side: Side, quantity: Long, price: Long, depthNotional: Double, fame: Double, shame: Double) {
        if (quantity <= 0) return
        val size = (quantity.toDouble() * price / depthNotional.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        hype = (hype + 0.2 * size).coerceAtMost(1.0)
        val chance = (0.05 + 0.6 * size + 0.3 * hype + 0.3 * fame).coerceIn(0.0, 0.9)
        if (!rng.chance(chance)) return

        val multiple = minOf(3.0, 0.12 * (1 - rng.nextDouble()).pow(-0.8))
        val total = (quantity * multiple).roundToLong()
        if (total <= 0) return
        val copyShare = (0.7 + 0.2 * fame - 0.5 * shame).coerceIn(0.1, 0.95)
        val members = minOf(30, 1 + rng.nextDouble().coerceAtLeast(1e-6).pow(-1.2).toInt())
        var left = total
        repeat(members) { i ->
            val q = if (i == members - 1) left else (total.toDouble() / members * rng.nextDouble(0.5, 1.5)).roundToLong().coerceIn(0, left)
            left -= q
            if (q > 0) {
                val s = if (rng.chance(copyShare)) side else side.opposite
                orders += CrowdOrder(day, tick + rng.nextInt(1, 11), s, q)
            }
        }
    }

    /** Big news gets noticed. */
    fun onNews(logJump: Double) {
        hype = (hype + minOf(0.5, kotlin.math.abs(logJump) * 3)).coerceAtMost(1.0)
    }

    /** Orders due at this tick (removed from the queue). */
    fun due(day: Int, tick: Int): List<CrowdOrder> {
        if (orders.isEmpty()) return emptyList()
        val now = orders.filter { it.day == day && it.tick <= tick }
        if (now.isNotEmpty()) orders.removeAll(now.toSet())
        return now
    }

    fun afterTick() {
        hype *= 0.995
    }

    /** The crowd goes home at the close. */
    fun endSession() {
        orders.clear()
    }

    fun writeTo(out: DataOutputStream) {
        out.writeLong(rng.state)
        out.writeDouble(hype)
        out.writeList(orders) { writeInt(it.day); writeInt(it.tick); writeUTF(it.side.name); writeLong(it.quantity) }
    }

    fun readFrom(input: DataInputStream) {
        rng = Rng.restore(input.readLong())
        hype = input.readDouble()
        orders.clear()
        orders += input.readList { CrowdOrder(readInt(), readInt(), Side.valueOf(readUTF()), readLong()) }
    }
}
