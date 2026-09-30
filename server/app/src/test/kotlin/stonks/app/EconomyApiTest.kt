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

class EconomyApiTest {
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
        val challenge = json(post("/api/v1/auth/otp/request") { contentType(ContentType.Application.Json); setBody("""{"email":"$email"}""") }.bodyAsText())["challengeId"]!!.jsonPrimitive.content
        // The fixed development code (STONKS_FIXED_OTP default).
        val r = json(post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challenge","code":"111111","publicKey":"${key.publicSpki}"}""")
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

    private fun count(sql: String): Long = app.db.read { c -> c.prepareStatement(sql).use { ps -> ps.executeQuery().use { it.next(); it.getLong(1) } } }

    @Test
    fun `claims, resets with cooldown, recorded once across restarts`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val s = c.login("econ@gmail.com")
        c.portfolio(s)

        val claim = c.signed(s, "POST", "/api/v1/economy/claim")
        assertEquals(HttpStatusCode.BadRequest, claim.status)
        assertTrue(claim.bodyAsText().contains("below"), claim.bodyAsText())

        val reset = c.signed(s, "POST", "/api/v1/economy/reset")
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        val standing = json(reset.bodyAsText())["standing"]!!.jsonObject
        assertTrue(standing["nextResetAt"]!!.jsonPrimitive.content > clock.instant().plusSeconds(29L * 86400).toString())
        assertEquals(HttpStatusCode.BadRequest, c.signed(s, "POST", "/api/v1/economy/reset").status, "cooldown")
        assertEquals(HttpStatusCode.BadRequest, c.signed(s, "POST", "/api/v1/economy/bankrupt").status, "positive net worth")
        assertEquals(HttpStatusCode.NotFound, c.signed(s, "POST", "/api/v1/economy/print-money").status)

        eventually { count("select count(*) from economy_events where kind = 'RESET'") == 1L }
        // Restart: the reset is replayed from the input log but recorded only once.
        app.close()
        app = App(config, TestDb.reconnect(db), clock).also { it.start() }
        val restored = app.market.portfolio(s.accountId)!!.standing
        assertEquals(standing["nextResetAt"]!!.jsonPrimitive.content, restored.nextResetAt)
        assertEquals(1L, count("select count(*) from economy_events where kind = 'RESET'"))
    }

    @Test
    fun `names, profiles and leaderboards`() = testApplication {
        application { stonksModule(app) }
        val c = createClient { }
        val a = c.login("alice@gmail.com")
        val b = c.login("bob@gmail.com")
        c.portfolio(a); c.portfolio(b)

        val me = json(c.signed(a, "GET", "/api/v1/me").bodyAsText())
        assertTrue(me["displayName"]!!.jsonPrimitive.content.startsWith("Trader"))
        assertEquals(HttpStatusCode.OK, c.signed(a, "POST", "/api/v1/me/name", """{"displayName":"BullishBanana"}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.signed(b, "POST", "/api/v1/me/name", """{"displayName":"bullishbanana"}""").status, "taken")
        assertEquals(HttpStatusCode.BadRequest, c.signed(b, "POST", "/api/v1/me/name", """{"displayName":"Trader7"}""").status, "reserved")
        assertEquals(HttpStatusCode.BadRequest, c.signed(b, "POST", "/api/v1/me/name", """{"displayName":"x"}""").status, "too short")

        val profile = json(c.signed(b, "GET", "/api/v1/players/bullishbanana").bodyAsText())
        assertEquals("BullishBanana", profile["name"]!!.jsonPrimitive.content)
        assertEquals("ROOKIE", profile["plan"]!!.jsonPrimitive.content)
        assertTrue("email" !in profile)
        assertEquals(HttpStatusCode.NotFound, c.signed(b, "GET", "/api/v1/players/nobody-here").status)

        app.leaderboards.refresh()
        val season = json(c.signed(a, "GET", "/api/v1/leaderboards/season").bodyAsText())
        assertEquals("2026-09", season["season"]!!.jsonPrimitive.content)
        assertTrue(season["entries"]!!.jsonArray.isEmpty(), "nobody has traded enough yet")
        assertTrue(season["you"]!!.jsonObject["unranked"]!!.jsonPrimitive.content.startsWith("Needs 10 trades"))

        val rich = json(c.signed(a, "GET", "/api/v1/leaderboards/millionaires").bodyAsText())
        assertTrue(rich["entries"]!!.jsonArray.isEmpty())

        // The month ends: the season is finalized and a new one starts.
        clock.now = Instant.parse("2026-10-01T00:00:30Z")
        app.leaderboards.refresh()
        assertEquals(1L, count("select count(*) from seasons where id = '2026-09' and finalized_at is not null"))
        val next = json(c.signed(a, "GET", "/api/v1/leaderboards/season").bodyAsText())
        assertEquals("2026-10", next["season"]!!.jsonPrimitive.content)
        val old = json(c.signed(a, "GET", "/api/v1/leaderboards/season?season=2026-09").bodyAsText())
        assertEquals(true, old["finalized"]!!.jsonPrimitive.content.toBoolean())
    }
}
