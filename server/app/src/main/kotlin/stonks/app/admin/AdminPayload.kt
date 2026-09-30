package stonks.app.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import stonks.app.trading.RequestError
import stonks.engine.company.Sector
import stonks.engine.core.Side
import stonks.engine.sim.AdminAction
import stonks.engine.sim.MarketRegime
import stonks.engine.strategy.StrategyType

/**
 * Wire and log format of a game-master action. [type] selects the action; the other
 * fields are that action's parameters. [at] (ISO time) is resolved by the server into
 * a game ([day], [tick]) before logging.
 */
@Serializable
data class AdminPayload(
    val type: String,
    val ticker: String? = null,
    val strategy: String? = null,
    val nextSession: Boolean = false,
    val event: String? = null,
    val at: String? = null,
    val day: Int? = null,
    val tick: Int? = null,
    val scope: String? = null,
    val target: String? = null,
    val percent: Double? = null,
    val headline: String? = null,
    val regime: String? = null,
    val halted: Boolean? = null,
    val volatility: Double? = null,
    val depth: Double? = null,
    val borrowPool: Double? = null,
    val ratio: Double? = null,
    val amount: Long? = null,
    val sector: String? = null,
    val days: Int? = null,
    val name: String? = null,
    val returnPct: Double? = null,
    val windowDays: Int? = null,
    val termDays: Int? = null,
    val cap: Long? = null,
    val accountId: Long? = null,
    val side: String? = null,
    val quantity: Long? = null,
    val price: Long? = null,
    val commission: Long? = null,
    val reason: String? = null,
) {
    fun toEngine(): AdminAction = when (type) {
        "strategy" -> AdminAction.SetStrategy(req(ticker, "ticker"), enumOf<StrategyType>(strategy, "strategy"), nextSession)
        "event" -> AdminAction.CompanyEvent(req(ticker, "ticker"), req(event, "event"), day, tick)
        "shock" -> AdminAction.Shock(enumOf(scope, "scope"), target, req(percent, "percent"), req(headline, "headline").trim(), day, tick)
        "rate" -> AdminAction.SetRate(req(percent, "percent"))
        "regime" -> AdminAction.SetRegime(enumOf<MarketRegime>(regime, "regime"))
        "halt" -> AdminAction.Halt(ticker, req(halted, "halted"))
        "tune" -> AdminAction.Tune(req(ticker, "ticker"), volatility, depth, borrowPool)
        "split" -> AdminAction.Split(req(ticker, "ticker"), req(ratio, "ratio"))
        "dividend" -> AdminAction.SpecialDividend(req(ticker, "ticker"), req(amount, "amount"))
        "buyback" -> AdminAction.Buyback(req(ticker, "ticker"), req(percent, "percent"))
        "ipo" -> AdminAction.Ipo(sector?.let { enumOf<Sector>(it, "sector") }, days ?: 3)
        "bonds" -> AdminAction.IssueBonds(req(name, "name").trim(), req(returnPct, "returnPct"), req(windowDays, "windowDays"), req(termDays, "termDays"), req(cap, "cap"))
        "void_fill" -> AdminAction.VoidFill(req(accountId, "accountId"), req(ticker, "ticker"), enumOf<Side>(side, "side"), req(quantity, "quantity"), req(price, "price"), commission ?: 0)
        "adjust_cash" -> AdminAction.AdjustCash(req(accountId, "accountId"), req(amount, "amount"), req(reason, "reason").trim())
        else -> throw RequestError("Unknown action type '$type'.")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun encode(p: AdminPayload): String = json.encodeToString(serializer(), p)
        fun decode(s: String): AdminAction = json.decodeFromString(serializer(), s).toEngine()

        private fun <T> req(v: T?, name: String): T = v ?: throw RequestError("$name is required.")
        private inline fun <reified E : Enum<E>> enumOf(v: String?, name: String): E =
            enumValues<E>().firstOrNull { it.name == v?.uppercase() } ?: throw RequestError("Invalid $name.")
    }
}
