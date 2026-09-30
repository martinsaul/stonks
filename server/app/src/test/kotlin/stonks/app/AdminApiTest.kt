package stonks.app

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stonks.app.auth.RequestSignature
import stonks.app.db.Db
import stonks.app.db.update
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminApiTest {
    // Tuesday 11:00 ET: session A is open.
    private val clock = TestClock(Instant.parse("2026-09-29T15:00:00Z"))
    private val adminKey = "correct-horse-battery-staple"
    private val config = AppConfig(devMode = true, backfillSessions = 3, worldSeed = 42, adminEmails = setOf("boss@gmail.com"), adminKey = adminKey)
    private val db: Db = TestDb.fresh()
    private var restarts = 0
    private var app = App(config, db, clock).also { it.restart = { restarts++ }; it.start() }

    @AfterTest
    fun tearDown() = app.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private data class S(val id: String, val key: DeviceKey, val accountId: Long)

    private suspend fun HttpClient.login(email: String): S {
        val key = DeviceKey()
        val challenge = json(post("/api/v1/auth/otp/request") { contentType(ContentType.Application.Json); setBody("""{"email":"$email"}""") }.bodyAsText())["challengeId"]!!.jsonPrimitive.content
        // The fixed development code (STONKS_FIXED_OTP default).
        val r = json(post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challenge","code":"111111","publicKey":"${key.publicSpki}"}""")
        }.bodyAsText())
        return S(r["sessionId"]!!.jsonPrimitive.content, key, r["accountId"]!!.jsonPrimitive.long)
    }

    private suspend fun HttpClient.signed(s: S, method: String, path: String, body: String? = null, key: String? = null): HttpResponse {
        val ts = clock.millis()
        val nonce = s.key.nonce()
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val sig = s.key.sign(method, path, ts, nonce, bytes)
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(RequestSignature.HEADER_SESSION, s.id)
            header(RequestSignature.HEADER_TIMESTAMP, ts.toString())
            header(RequestSignature.HEADER_NONCE, nonce)
            header(RequestSignature.HEADER_SIGNATURE, sig)
            if (key != null) header("X-Stonks-Admin-Key", key)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "DELETE" -> delete(path, block)
            else -> error(method)
        }
    }

    private suspend fun HttpClient.portfolio(s: S): JsonObject = json(signed(s, "GET", "/api/v1/portfolio").bodyAsText())

    /** Advances the clock tick by tick until [check] passes. */
    private suspend fun eventually(maxTicks: Int = 20, check: suspend () -> Boolean) {
        repeat(maxTicks) {
            if (check()) return
            clock.advance(5)
            Thread.sleep(150)
        }
        assertTrue(check(), "condition not met after $maxTicks ticks")
    }

    private fun count(sql: String): Long = app.db.read { c -> c.prepareStatement(sql).use { ps -> ps.executeQuery().use { it.next(); it.getLong(1) } } }

    private suspend fun HttpClient.admin(s: S, method: String, path: String, body: String? = null) = signed(s, method, path, body, adminKey)

    @Test
    fun `admin needs a listed email and the key`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val boss = c.login("boss@gmail.com")
        val pleb = c.login("pleb@gmail.com")
        assertEquals(true, json(c.signed(boss, "GET", "/api/v1/me").bodyAsText())["admin"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, json(c.signed(pleb, "GET", "/api/v1/me").bodyAsText())["admin"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(HttpStatusCode.Forbidden, c.signed(boss, "GET", "/api/v1/admin/overview").status, "no key")
        assertEquals(HttpStatusCode.Forbidden, c.signed(boss, "GET", "/api/v1/admin/overview", key = "wrong-key-wrong-key").status)
        assertEquals(HttpStatusCode.Forbidden, c.signed(pleb, "GET", "/api/v1/admin/overview", key = adminKey).status, "not listed")
        val o = json(c.admin(boss, "GET", "/api/v1/admin/overview").bodyAsText())
        assertEquals(50, o["tickers"]!!.jsonArray.size)
    }

    @Test
    fun `game master actions are applied, logged and audited`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val boss = c.login("boss@gmail.com")
        val r = c.admin(boss, "POST", "/api/v1/admin/actions", """{"type":"halt","ticker":"FOOF","halted":true}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val foof = json(c.admin(boss, "GET", "/api/v1/admin/overview").bodyAsText())["tickers"]!!.jsonArray.map { it.jsonObject }.first { it["ticker"]!!.jsonPrimitive.content == "FOOF" }
        assertEquals(true, foof["halted"]!!.jsonPrimitive.content.toBoolean())

        assertEquals(HttpStatusCode.BadRequest, c.admin(boss, "POST", "/api/v1/admin/actions", """{"type":"halt","ticker":"NOPE","halted":true}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.admin(boss, "POST", "/api/v1/admin/actions", """{"type":"teleport"}""").status)

        val at = clock.instant().plusSeconds(600).toString()
        val shock = c.admin(boss, "POST", "/api/v1/admin/actions", """{"type":"shock","scope":"SECTOR","target":"TECH","percent":-5,"headline":"Tech bros discover taxes","at":"$at"}""")
        assertEquals(HttpStatusCode.OK, shock.status, shock.bodyAsText())
        val scheduled = json(c.admin(boss, "GET", "/api/v1/admin/overview").bodyAsText())["scheduled"]!!.jsonArray.map { it.jsonObject }
        assertTrue(scheduled.any { it["kind"]!!.jsonPrimitive.content == "SHOCK_SECTOR" })

        val audit = kotlinx.serialization.json.Json.parseToJsonElement(c.admin(boss, "GET", "/api/v1/admin/audit").bodyAsText()).jsonArray
        assertTrue(audit.map { it.jsonObject["action"]!!.jsonPrimitive.content }.containsAll(listOf("halt", "shock")))

        // Logged like player inputs: a restart replays the halt.
        eventually { app.db.read { c2 -> c2.prepareStatement("select count(*) from engine_inputs where kind = 'admin'").use { ps -> ps.executeQuery().use { it.next(); it.getInt(1) } } } >= 2 }
        app.close()
        app = App(config, TestDb.reconnect(db), clock).also { it.restart = { restarts++ }; it.start() }
        assertTrue(app.market.query { m -> m.tickers.getValue("FOOF").halted }.get())
    }

    @Test
    fun `ban, void a fill, adjust cash`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val boss = c.login("boss@gmail.com")
        val p = c.login("cheater@gmail.com")
        c.portfolio(p)
        c.signed(p, "POST", "/api/v1/orders", """{"ticker":"MHRD","legs":[{"side":"BUY","quantity":5,"type":"MARKET"}]}""")
        eventually { c.portfolio(p)["positions"]!!.jsonArray.isNotEmpty() }
        eventually { json(c.admin(boss, "GET", "/api/v1/admin/players/${p.accountId}").bodyAsText())["fills"]!!.jsonArray.isNotEmpty() }
        val detail = json(c.admin(boss, "GET", "/api/v1/admin/players/${p.accountId}").bodyAsText())
        val fillId = detail["fills"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, c.admin(boss, "POST", "/api/v1/admin/fills/$fillId/void").status)
        assertEquals(HttpStatusCode.BadRequest, c.admin(boss, "POST", "/api/v1/admin/fills/$fillId/void").status, "twice")
        eventually { c.portfolio(p)["positions"]!!.jsonArray.isEmpty() }

        assertEquals(HttpStatusCode.OK, c.admin(boss, "POST", "/api/v1/admin/actions", """{"type":"adjust_cash","accountId":${p.accountId},"amount":12345,"reason":"sorry"}""").status)
        val search = kotlinx.serialization.json.Json.parseToJsonElement(c.admin(boss, "GET", "/api/v1/admin/players?q=cheater").bodyAsText()).jsonArray
        assertEquals(p.accountId, search.single().jsonObject["id"]!!.jsonPrimitive.long)

        assertEquals(HttpStatusCode.OK, c.admin(boss, "POST", "/api/v1/admin/players/${p.accountId}/ban", """{"banned":true,"reason":"test"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, c.signed(p, "GET", "/api/v1/portfolio").status)
        val challenge = json(c.post("/api/v1/auth/otp/request") { contentType(ContentType.Application.Json); setBody("""{"email":"cheater@gmail.com"}""") }.bodyAsText())["challengeId"]!!.jsonPrimitive.content
        val relogin = c.post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challenge","code":"111111","publicKey":"${DeviceKey().publicSpki}"}""")
        }
        assertTrue(relogin.bodyAsText().contains("suspended"), relogin.bodyAsText())
    }

    @Test
    fun `world rollback discards later inputs on the next start`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val boss = c.login("boss@gmail.com")
        val p = c.login("latecomer@gmail.com")
        c.portfolio(p)
        val snaps = kotlinx.serialization.json.Json.parseToJsonElement(c.admin(boss, "GET", "/api/v1/admin/snapshots").bodyAsText()).jsonArray
        val latest = snaps.first().jsonObject["id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.BadRequest, c.admin(boss, "POST", "/api/v1/admin/rollback", """{"snapshotId":$latest,"confirm":"yes"}""").status)
        assertEquals(HttpStatusCode.OK, c.admin(boss, "POST", "/api/v1/admin/rollback", """{"snapshotId":$latest,"confirm":"ROLLBACK"}""").status)
        assertEquals(1, restarts)

        app.close()
        app = App(config, TestDb.reconnect(db), clock).also { it.restart = { restarts++ }; it.start() }
        assertEquals(null, app.market.portfolio(p.accountId), "the account opening was rolled back")
        val inputs = app.db.read { c2 -> c2.prepareStatement("select count(*) from engine_inputs").use { ps -> ps.executeQuery().use { it.next(); it.getInt(1) } } }
        assertEquals(0, inputs)
    }
}
