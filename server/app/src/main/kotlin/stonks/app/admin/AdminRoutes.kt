package stonks.app.admin

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import stonks.app.App
import stonks.app.api.badRequest
import stonks.app.api.forbidden
import stonks.app.api.notFound
import stonks.app.api.principal
import stonks.app.api.signedJson
import stonks.app.market.BondOfferingDto
import stonks.app.trading.Command
import stonks.app.trading.RequestError
import stonks.engine.company.Sector
import stonks.engine.sim.Market
import stonks.engine.sim.MarketRegime
import stonks.engine.strategy.StrategyType
import stonks.engine.world.AgendaKind
import stonks.engine.world.EventType
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

@Serializable data class EconomyDashboard(val stats: List<StatRow>, val pendingReviews: Int)

const val ADMIN_KEY_HEADER = "X-Stonks-Admin-Key"

/** Whether [accountId] is listed as an admin (the key is checked per request). */
fun App.isAdmin(email: String): Boolean = config.adminKey != null && email.lowercase() in config.adminEmails

/** Admin: listed email AND the admin key. Returns the admin's account id. */
private fun App.requireAdmin(call: ApplicationCall): Long {
    val key = config.adminKey ?: throw forbidden("The admin console is disabled.")
    val given = call.request.headers[ADMIN_KEY_HEADER] ?: throw forbidden("Admin key required.")
    val sha = { s: String -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()) }
    if (!MessageDigest.isEqual(sha(given), sha(key))) throw forbidden("Wrong admin key.")
    val account = accounts.byId(call.principal.accountId) ?: throw forbidden()
    if (!isAdmin(account.email)) throw forbidden()
    return account.id
}

private suspend fun <T> CompletableFuture<T>.awaitIn(): T = withTimeout(10_000) { await() }

/** Game-master console; installed inside the signed route block. */
fun Route.adminRoutes(app: App) {
    get("/admin/overview") {
        app.requireAdmin(call)
        call.respond(app.market.query { m -> overview(app, m) }.awaitIn())
    }

    post("/admin/actions") {
        val admin = app.requireAdmin(call)
        var payload = call.signedJson<AdminPayload>()
        // Resolve a wall-clock time into a game day and tick.
        payload.at?.let { at ->
            val instant = runCatching { java.time.Instant.parse(at) }.getOrNull() ?: throw badRequest("at must be an ISO time.")
            val (d, t) = app.market.query { app.market.locate(instant) }.awaitIn() ?: throw badRequest("That time is too far ahead.")
            payload = payload.copy(at = null, day = d, tick = t)
        }
        try { payload.toEngine() } catch (e: RequestError) { throw badRequest(e.message!!, "admin_invalid") }
        val encoded = AdminPayload.encode(payload)
        val result = CompletableFuture<String?>()
        app.market.desk.submit(Command.Admin(admin, app.clock.instant(), encoded, result))
        val reason = result.awaitIn()
        app.admin.audit(admin, payload.type, payload.ticker ?: payload.target ?: payload.accountId?.toString(), encoded, reason ?: "ok")
        if (reason != null) throw badRequest(reason, "admin_refused")
        call.respond(AdminActionResult(true))
    }

    get("/admin/players") {
        app.requireAdmin(call)
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.length < 1) throw badRequest("q is required.")
        call.respond(app.admin.search(q))
    }

    get("/admin/players/{id}") {
        app.requireAdmin(call)
        val id = call.parameters["id"]!!.toLongOrNull() ?: throw badRequest("Invalid id.")
        val p = app.admin.player(id) ?: throw notFound("No such player.")
        val review = app.admin.reviews().firstOrNull { it.accountId == id }?.status
        call.respond(PlayerDetail(p, app.market.portfolio(id), app.admin.sessions(id), app.admin.fills(id, 100), app.admin.economy(id), app.admin.flags(id), review))
    }

    post("/admin/players/{id}/ban") {
        val admin = app.requireAdmin(call)
        val id = call.parameters["id"]!!.toLongOrNull() ?: throw badRequest("Invalid id.")
        val body = call.signedJson<BanRequest>()
        app.admin.player(id) ?: throw notFound("No such player.")
        if (id == admin) throw badRequest("You can't ban yourself.")
        app.admin.setBanned(id, body.reason, body.banned, app.clock.instant())
        app.sessions.forgetAccount(id)
        app.admin.audit(admin, if (body.banned) "ban" else "unban", id.toString(), """{"reason":${kotlinx.serialization.json.JsonPrimitive(body.reason ?: "")}}""", "ok")
        call.respond(AdminActionResult(true))
    }

    post("/admin/fills/{id}/void") {
        val admin = app.requireAdmin(call)
        val fillId = call.parameters["id"]!!
        val (accountId, f) = app.admin.fill(fillId) ?: throw notFound("No such fill.")
        if (f.voided) throw badRequest("Already voided.")
        val payload = AdminPayload("void_fill", ticker = f.ticker, accountId = accountId, side = f.side, quantity = f.quantity, price = f.price, commission = f.commission)
        val encoded = AdminPayload.encode(payload)
        val result = CompletableFuture<String?>()
        app.market.desk.submit(Command.Admin(admin, app.clock.instant(), encoded, result))
        val reason = result.awaitIn()
        app.admin.audit(admin, "void_fill", fillId, encoded, reason ?: "ok")
        if (reason != null) throw badRequest(reason, "admin_refused")
        app.admin.markVoided(fillId, app.clock.instant())
        call.respond(AdminActionResult(true))
    }

    get("/admin/reviews") {
        app.requireAdmin(call)
        call.respond(app.admin.reviews())
    }

    post("/admin/reviews/{id}") {
        val admin = app.requireAdmin(call)
        val id = call.parameters["id"]!!.toLongOrNull() ?: throw badRequest("Invalid id.")
        val body = call.signedJson<ReviewRequest>()
        if (body.status !in setOf("APPROVED", "REJECTED", "PENDING")) throw badRequest("status must be APPROVED, REJECTED or PENDING.")
        if (!app.admin.review(id, body.status, admin, body.note, app.clock.instant())) throw notFound("No review for that player.")
        app.admin.audit(admin, "review", id.toString(), """{"status":"${body.status}"}""", "ok")
        app.leaderboards.refresh()
        call.respond(AdminActionResult(true))
    }

    get("/admin/economy") {
        app.requireAdmin(call)
        call.respond(EconomyDashboard(app.admin.stats(500), app.admin.reviews().count { it.status == "PENDING" }))
    }

    get("/admin/audit") {
        app.requireAdmin(call)
        call.respond(app.admin.auditLog(200))
    }

    get("/admin/snapshots") {
        app.requireAdmin(call)
        call.respond(app.admin.snapshots())
    }

    post("/admin/rollback") {
        val admin = app.requireAdmin(call)
        val body = call.signedJson<RollbackRequest>()
        if (body.confirm != "ROLLBACK") throw badRequest("Type ROLLBACK to confirm.")
        if (!app.admin.requestRollback(body.snapshotId, admin)) throw notFound("No such snapshot.")
        app.admin.audit(admin, "rollback", body.snapshotId.toString(), "{}", "restarting")
        call.respond(AdminActionResult(true, "Rollback scheduled. The server is restarting."))
        app.requestRestart()
    }
}

/** Builds the admin overview. Simulation thread only. */
private fun overview(app: App, m: Market): AdminOverview {
    val rt = app.market
    val date = { d: Int -> rt.dateOf(d)?.toString() }
    val scheduled = ArrayList<ScheduledDto>()
    for (s in m.scheduledShocks) scheduled += ScheduledDto(date(s.day), s.day, s.tick, "SHOCK_${s.scope}", s.target,
        "${"%+.1f".format((Math.exp(s.logReturn) - 1) * 100)}%: ${s.headline}")
    for (t in m.tickers.values) {
        for (a in t.corp.agenda) {
            val what = when (a.kind) {
                AgendaKind.EVENT -> a.type?.name ?: "EVENT"
                AgendaKind.RUMOR -> "RUMOR of ${a.type?.name}"
                AgendaKind.JUMP -> a.headline ?: "JUMP"
            }
            scheduled += ScheduledDto(date(a.day), a.day, a.tick, a.kind.name, t.company.ticker, what)
        }
        if (t.corp.splitDay >= 0) scheduled += ScheduledDto(date(t.corp.splitDay), t.corp.splitDay, -1, "SPLIT", t.company.ticker, "ratio ${t.corp.splitRatio}")
        t.corp.deal?.let { scheduled += ScheduledDto(date(it.closeDay), it.closeDay, -1, "DEAL_CLOSE", t.company.ticker, "offer ${it.offer}, completes=${it.completes}") }
    }
    for (ipo in m.ipoPipeline) scheduled += ScheduledDto(date(ipo.day), ipo.day, -1, "IPO", ipo.company.ticker, ipo.company.name)
    scheduled.sortWith(compareBy({ it.day }, { it.tick }))
    return AdminOverview(
        day = m.day,
        tick = if (m.session != null) m.tick else null,
        open = m.session != null,
        regime = m.regime.name,
        benchmarkRate = m.benchmarkRate * 100,
        nextRateDecision = date(m.nextRateDay),
        tickers = m.tickers.values.map { t ->
            TickerAdminDto(
                t.company.ticker, t.company.name, t.company.sector.name, t.last, t.strategy.current.type.name, t.corp.status.name,
                t.halted, t.volMultiplier, t.depthMultiplier, t.borrowPoolFraction,
                t.corp.splitDay.takeIf { it >= 0 }?.let { "${t.corp.splitRatio} on day $it" }, t.corp.deal?.offer,
            )
        },
        scheduled = scheduled.take(300),
        bonds = m.bondOfferings.map { o ->
            BondOfferingDto(o.id, o.name, o.returnRate * 100, o.capPerPlayer, m.day in o.openDay..o.closeDay, date(o.closeDay), date(o.maturityDay))
        },
        players = m.ledger.accounts.size,
        strategies = StrategyType.entries.map { it.name },
        events = EventType.entries.map { it.name } + listOf("DISTRESS", "BUYOUT"),
        regimes = MarketRegime.entries.map { it.name },
        sectors = Sector.entries.map { it.name },
    )
}
