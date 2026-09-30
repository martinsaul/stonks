package stonks.engine.trading

import stonks.engine.core.Cents
import stonks.engine.core.Side

/** Something the app must persist or tell a player about. Emitted on the sim thread. */
sealed interface EngineEvent {
    val accountId: Long
}

data class OrderUpdate(
    val orderId: Long,
    val groupId: Long,
    override val accountId: Long,
    val ticker: String,
    val side: Side,
    val kind: OrderKind,
    val quantity: Long,
    val status: LegStatus,
    val filled: Long,
    val averagePrice: Double?,
    val limit: Cents?,
    val stop: Cents?,
    val reason: String?,
    val liquidation: Boolean,
) : EngineEvent

data class FillEvent(
    /** Deterministic id: stable across replays. */
    val fillId: String,
    val orderId: Long,
    override val accountId: Long,
    val ticker: String,
    val side: Side,
    val quantity: Long,
    val price: Cents,
    val commission: Cents,
    val realized: Cents,
    val maker: Boolean,
    val liquidation: Boolean,
    val day: Int,
    val tick: Int,
    /** The other player's account when both sides were players (collusion review). */
    val counterparty: Long? = null,
) : EngineEvent

/**
 * An account-level event. [key], when set, is a deterministic id (stable across
 * replays) for events the app records exactly once: economy actions, achievements.
 */
data class AccountEvent(
    override val accountId: Long,
    val kind: Kind,
    val detail: String,
    val amount: Cents = 0,
    val key: String? = null,
) : EngineEvent {
    enum class Kind {
        OPENED, PLAN_UPGRADED, PLAN_LOST, MARGIN_CALL, DIVIDEND, SPLIT, DELISTED,
        CLAIMED, RESET, BANKRUPT, UPGRADED, BADGE_CLEARED, BOND_BOUGHT, BOND_MATURED, ACHIEVEMENT, ADJUSTED,
    }
}
