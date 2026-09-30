package stonks.engine.trading

import stonks.engine.core.Cents
import stonks.engine.snapshot.readList
import stonks.engine.snapshot.writeList
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.pow
import kotlin.math.roundToLong

/** A reset performed at [at] (epoch ms), [inDebt] if net worth was negative. */
data class ResetRecord(val at: Long, val inDebt: Boolean)

/** A bond held to maturity: [principal] paid, [payout] returned on [maturityDay]. */
data class BondHolding(val offeringId: Long, val principal: Cents, val payout: Cents, val maturityDay: Int)

/** Price extremes while a long position is held (for Midas' / Sadim's Hands). */
class PositionWatch(val openDay: Int, var low: Cents, var high: Cents)

/**
 * A player's standing: everything that survives monthly resets (docs/DESIGN.md,
 * "Bailouts, resets and bankruptcy").
 */
class Standing {
    /** Starting-cash upgrade level (0 = $5k). */
    var cashLevel = 0
    /** Outstanding badges of shame. */
    var shame = 0
    /** Badges of shame ever received. */
    var shameEver = 0
    /** Reached 30 outstanding badges: none can be cleared again. */
    var eternal = false
    var bankruptcies = 0
    /** Last weekly claim (epoch ms), or null. */
    var lastClaimAt: Long? = null
    val resets = ArrayList<ResetRecord>()
    /** Earliest time the next monthly reset is allowed (epoch ms). */
    var nextResetAt = 0L
    /** Achievements and milestones already awarded (never twice). */
    val awarded = TreeSet<String>()
    /** Starting cash of the current run (since opening, reset or bankruptcy). */
    var runStartCash: Cents = 0
    var runStartedAt = 0L
    /** Game day the current run started. */
    var runStartDay = 0

    fun writeTo(out: DataOutputStream) {
        out.writeInt(cashLevel); out.writeInt(shame); out.writeInt(shameEver); out.writeBoolean(eternal); out.writeInt(bankruptcies)
        out.writeLong(lastClaimAt ?: Long.MIN_VALUE)
        out.writeList(resets) { writeLong(it.at); writeBoolean(it.inDebt) }
        out.writeLong(nextResetAt)
        out.writeList(awarded.toList()) { writeUTF(it) }
        out.writeLong(runStartCash); out.writeLong(runStartedAt); out.writeInt(runStartDay)
    }

    fun readFrom(input: DataInputStream) {
        cashLevel = input.readInt(); shame = input.readInt(); shameEver = input.readInt(); eternal = input.readBoolean(); bankruptcies = input.readInt()
        lastClaimAt = input.readLong().takeIf { it != Long.MIN_VALUE }
        resets.clear(); resets += input.readList { ResetRecord(readLong(), readBoolean()) }
        nextResetAt = input.readLong()
        awarded.clear(); awarded += input.readList { readUTF() }
        runStartCash = input.readLong(); runStartedAt = input.readLong(); runStartDay = input.readInt()
    }
}

/** A player economy action (all go through the input log). */
sealed interface EconomyAction {
    data object Claim : EconomyAction
    data object Reset : EconomyAction
    data object Bankrupt : EconomyAction
    data object Upgrade : EconomyAction
    data object ClearBadge : EconomyAction
    data class BuyBond(val offeringId: Long, val amount: Cents) : EconomyAction
}

/** An admin-issued bond offering (docs/DESIGN.md, "Bond offerings"). */
data class BondOffering(
    val id: Long,
    val name: String,
    /** Total return at maturity, e.g. 0.08 for +8%. */
    val returnRate: Double,
    /** Subscriptions are accepted on game days [openDay, closeDay]. */
    val openDay: Int,
    val closeDay: Int,
    val maturityDay: Int,
    val capPerPlayer: Cents,
)

/** The economy's numbers, all in cents unless noted. */
object EconomyRules {
    const val BASE_STARTING_CASH: Cents = 5_000_00
    const val LEVEL_CASH: Cents = 1_000_00
    const val MAX_LEVEL = 30
    const val CLAIM_AMOUNT: Cents = 1_000_00
    /** Claims are for players with net worth below this. */
    const val CLAIM_BELOW: Cents = 1_000_00
    const val CLAIM_INTERVAL_MS = 7L * 24 * 3600 * 1000
    const val DAY_MS = 24L * 3600 * 1000
    const val ETERNAL_SHAME = 30
    /** Forced bankruptcy at net worth <= -10 x starting cash. */
    const val GAME_OVER_MULTIPLE = 10

    fun startingCash(level: Int): Cents = BASE_STARTING_CASH + level * LEVEL_CASH

    /** Cost of upgrade level [k] (k >= 1): $1M x 2^(k-1). */
    fun upgradeCost(k: Int): Cents = 1_000_000_00L * (1L shl (k - 1))

    /** Cost of clearing badge #[n]: $1,000 x 2^(n-1) / n. */
    fun clearCost(n: Int): Cents = (1_000_00.0 * 2.0.pow(n - 1) / n).roundToLong()

    /**
     * Cooldown before the next reset, in days: (30 + 0.55 D + 0.015 D^2) x 1.25^r,
     * D = debt in $k at the reset, r = earlier in-debt resets in the trailing 180 days.
     */
    fun resetCooldownDays(debt: Cents, recentDebtResets: Int): Double {
        val d = debt / 1_000_00.0
        return (30 + 0.55 * d + 0.015 * d * d) * 1.25.pow(recentDebtResets)
    }

    /** Net-worth multiples of the run's starting cash that earn a badge. */
    val MILESTONES = listOf(2.0 to "DOUBLED_UP", 10.0 to "TEN_BAGGER", 100.0 to "HUNDRED_BAGGER")
}

internal fun DataOutputStream.writeBonds(bonds: List<BondHolding>) =
    writeList(bonds) { writeLong(it.offeringId); writeLong(it.principal); writeLong(it.payout); writeInt(it.maturityDay) }

internal fun DataInputStream.readBonds(): List<BondHolding> = readList { BondHolding(readLong(), readLong(), readLong(), readInt()) }

internal fun DataOutputStream.writeWatches(w: Map<String, PositionWatch>) =
    writeList(TreeMap(w).entries.toList()) { (t, p) -> writeUTF(t); writeInt(p.openDay); writeLong(p.low); writeLong(p.high) }

internal fun DataInputStream.readWatches(into: MutableMap<String, PositionWatch>) {
    readList { into[readUTF()] = PositionWatch(readInt(), readLong(), readLong()) }
}
