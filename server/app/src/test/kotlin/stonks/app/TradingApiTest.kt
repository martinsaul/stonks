package stonks.app

import io.ktor.client.HttpClient
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

class TradingApiTest {
    // Tuesday 11:00 ET: session A is open.
    private val clock = TestClock(Instant.parse("2026-09-29T15:00:00Z"))
    private val config = AppConfig(devMode = true, backfillSessions = 3, worldSeed = 42)
    private val db: Db = TestDb.fresh()
    private var app = App(config, db, clock).also { it.start() }

    @AfterTest
    fun tearDown() = app.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private data class S(val id: String, val key: DeviceKey, val accountId: Long)

    private suspend fun HttpClient.login(email: String): S {
        val key = DeviceKey()
        val code = json(post("/api/v1/auth/otp/request") { contentType(ContentType.Application.Json); setBody("""{"email":"$email"}""") }.bodyAsText())["devCode"]!!.jsonPrimitive.content
        val r = json(post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","code":"$code","publicKey":"${key.publicSpki}"}""")
        }.bodyAsText())
        return S(r["sessionId"]!!.jsonPrimitive.content, key, r["accountId"]!!.jsonPrimitive.long)
    }

    private suspend fun HttpClient.signed(s: S, method: String, path: String, body: String? = null): HttpResponse {
        val ts = clock.millis()
        val nonce = s.key.nonce()
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val sig = s.key.sign(method, path, ts, nonce, bytes)
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(RequestSignature.HEADER_SESSION, s.id)
            header(RequestSignature.HEADER_TIMESTAMP, ts.toString())
            header(RequestSignature.HEADER_NONCE, nonce)
            header(RequestSignature.HEADER_SIGNATURE, sig)
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

    @Test
    fun `new accounts start with 5000 on the Rookie plan`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("newbie@gmail.com")
        val p = c.portfolio(s)
        assertEquals(5_000_00, p["cash"]!!.jsonPrimitive.long)
        assertEquals("ROOKIE", p["plan"]!!.jsonPrimitive.content)
        assertEquals(10_000_00, p["buyingPower"]!!.jsonPrimitive.long)
    }

    @Test
    fun `buy, see the position, sell`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("buyer@gmail.com")
        c.portfolio(s)
        val r = c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"BUY","quantity":10,"type":"MARKET"}]}""")
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        val placed = json(r.bodyAsText())
        assertEquals("QUEUED", placed["status"]!!.jsonPrimitive.content)
        assertTrue(placed["executesAt"]!!.jsonPrimitive.content > clock.instant().toString())

        eventually { c.portfolio(s)["positions"]!!.jsonArray.isNotEmpty() }
        val p = c.portfolio(s)
        val pos = p["positions"]!!.jsonArray.single().jsonObject
        assertEquals(10, pos["quantity"]!!.jsonPrimitive.long)
        assertTrue(p["cash"]!!.jsonPrimitive.long < 5_000_00 - 495)
        assertTrue(p["notices"]!!.jsonArray.any { it.jsonObject["text"]!!.jsonPrimitive.content.startsWith("Bought 10 FOOF") })

        c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"SELL","quantity":10,"type":"MARKET"}]}""")
        eventually { c.portfolio(s)["positions"]!!.jsonArray.isEmpty() }
        app.market.desk.flush()
        val fills = Json.parseToJsonElement(c.signed(s, "GET", "/api/v1/fills").bodyAsText()).jsonArray
        assertEquals(2, fills.size)
    }

    @Test
    fun `resting orders can be cancelled`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("patient@gmail.com")
        c.portfolio(s)
        val r = json(c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"BUY","quantity":5,"type":"LIMIT","limitPrice":100,"timeInForce":"GTC"}]}""").bodyAsText())
        val id = r["orderIds"]!!.jsonArray.first().jsonPrimitive.long
        eventually { c.portfolio(s)["openOrders"]!!.jsonArray.any { it.jsonObject["status"]!!.jsonPrimitive.content == "WORKING" } }
        assertTrue(c.portfolio(s)["availableEquity"]!!.jsonPrimitive.long < 5_000_00) // buying power held

        assertEquals(HttpStatusCode.NoContent, c.signed(s, "DELETE", "/api/v1/orders/$id").status)
        eventually { c.portfolio(s)["openOrders"]!!.jsonArray.isEmpty() }
        assertEquals(5_000_00, c.portfolio(s)["availableEquity"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.NotFound, c.signed(s, "DELETE", "/api/v1/orders/$id").status)
    }

    @Test
    fun `invalid and unaffordable orders are refused`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("reckless@gmail.com")
        c.portfolio(s)
        val bad = c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"BUY","quantity":5,"type":"LIMIT"}]}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("order_invalid", json(bad.bodyAsText())["error"]!!.jsonPrimitive.content)

        c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"BUY","quantity":100000,"type":"MARKET"}]}""")
        eventually { c.portfolio(s)["notices"]!!.jsonArray.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "rejected" } }
        assertTrue(c.portfolio(s)["positions"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a restart replays player inputs exactly`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("durable@gmail.com")
        c.portfolio(s)
        c.signed(s, "POST", "/api/v1/orders", """{"ticker":"MHRD","legs":[{"side":"BUY","quantity":7,"type":"MARKET"}]}""")
        c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"SELL","quantity":3,"type":"MARKET"}]}""")
        c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"BUY","quantity":2,"type":"LIMIT","limitPrice":100,"timeInForce":"GTC"}]}""")
        eventually { c.portfolio(s)["positions"]!!.jsonArray.size == 2 }
        clock.advance(60)
        Thread.sleep(300)
        val before = c.portfolio(s)

        // Stop at the same instant and restart: the snapshot predates these orders.
        app.close()
        app = App(config, TestDb.reconnect(db), clock).also { it.start(liveLoop = false) }
        val after = app.market.state.portfolios.getValue(s.accountId)
        assertEquals(before["cash"]!!.jsonPrimitive.long, after.cash)
        assertEquals(
            before["positions"]!!.jsonArray.map { it.jsonObject["ticker"]!!.jsonPrimitive.content to it.jsonObject["quantity"]!!.jsonPrimitive.long },
            after.positions.map { it.ticker to it.quantity },
        )
        assertEquals(1, after.openOrders.size)
    }

    @Test
    fun `absurd prices are refused by the API`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("overflow@gmail.com")
        c.portfolio(s)
        val r = c.signed(s, "POST", "/api/v1/orders", """{"ticker":"FOOF","legs":[{"side":"SELL","quantity":3,"type":"LIMIT","limitPrice":4000000000000000000,"timeInForce":"GTC"}]}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("order_invalid", json(r.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a poisoned input already in the log can't stop the server from starting`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("poison@gmail.com")
        c.portfolio(s)
        app.close()
        // Simulate an input logged before prices were capped (the old crash-loop).
        val db2 = TestDb.reconnect(db)
        db2.tx { conn ->
            conn.update(
                "insert into engine_inputs (apply_day, apply_tick, kind, account_id, payload) select apply_day, apply_tick, 'place', ?, ?::jsonb from engine_inputs order by seq desc limit 1",
                s.accountId,
                """{"request":{"ticker":"FOOF","structure":"SINGLE","legs":[{"side":"SELL","quantity":3,"type":"LIMIT","limitPrice":4000000000000000000,"timeInForce":"GTC"}]},"executeDay":0,"executeTick":0}""",
            )
        }
        app = App(config, db2, clock).also { it.start(liveLoop = false) }
        clock.advance(60)
        app.market.pump(clock.instant())
        val p = app.market.state.portfolios.getValue(s.accountId)
        assertEquals(5_000_00, p.cash)
        assertTrue(p.openOrders.isEmpty())
    }
}
