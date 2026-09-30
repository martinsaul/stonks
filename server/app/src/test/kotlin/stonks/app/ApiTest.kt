package stonks.app

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stonks.app.auth.RequestSignature
import java.net.URLEncoder
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {
    // Tuesday 2026-09-29 13:45 ET: the midday break, so the next session opens at 14:30.
    private val clock = TestClock(Instant.parse("2026-09-29T17:45:00Z"))
    private val config = AppConfig(
        devMode = true,
        fixedOtp = null, // random codes, as in production
        limits = AppConfig.Limits(sessionBurst = 30.0, sessionPerSecond = 0.001, accountBurst = 1000.0, otpPerIpHour = 100, verifyPerIpHour = 200),
        backfillSessions = 3,
        worldSeed = 42,
    )
    private val app = App(config, TestDb.fresh(), clock).also { it.start(liveLoop = false) }

    @AfterTest
    fun tearDown() = app.close()

    private fun json(r: String) = Json.parseToJsonElement(r).jsonObject

    private fun ApplicationTestBuilder.client(): HttpClient {
        application { stonksModule(app) }
        return createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
    }

    private data class Session(val id: String, val key: DeviceKey, val accountId: Long)

    private suspend fun HttpClient.login(email: String, key: DeviceKey = DeviceKey()): Session {
        val (challenge, code) = requestCode(email)
        val r = verify(challenge, code, key)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = json(r.bodyAsText())
        return Session(body["sessionId"]!!.jsonPrimitive.content, key, body["accountId"]!!.jsonPrimitive.long)
    }

    /** Returns (challengeId, code). */
    private suspend fun HttpClient.requestCode(email: String): Pair<String, String> {
        val r = post("/api/v1/auth/otp/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        return b["challengeId"]!!.jsonPrimitive.content to b["devCode"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.verify(challenge: String, code: String, key: DeviceKey = DeviceKey()) =
        post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challenge","code":"$code","publicKey":"${key.publicSpki}"}""")
        }

    private suspend fun HttpClient.signedGet(
        s: Session,
        path: String,
        ts: Long = clock.millis(),
        nonce: String = s.key.nonce(),
        signedPath: String = path,
    ): HttpResponse = get(path) {
        header(RequestSignature.HEADER_SESSION, s.id)
        header(RequestSignature.HEADER_TIMESTAMP, ts.toString())
        header(RequestSignature.HEADER_NONCE, nonce)
        header(RequestSignature.HEADER_SIGNATURE, s.key.sign("GET", signedPath, ts, nonce))
    }

    private suspend fun HttpResponse.error() = json(bodyAsText())["error"]!!.jsonPrimitive.content

    @Test
    fun `sign in and make signed requests`() = testApplication {
        val c = client()
        val s = c.login("trader@gmail.com")
        val me = c.signedGet(s, "/api/v1/me")
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("trader@gmail.com", json(me.bodyAsText())["email"]!!.jsonPrimitive.content)

        // Same person signing in from another device reaches the same account.
        val again = c.login("trader@gmail.com")
        assertEquals(s.accountId, again.accountId)
    }

    @Test
    fun `every API call requires a valid, fresh, unique signature`() = testApplication {
        val c = client()
        val s = c.login("careful@outlook.com")

        assertEquals("auth_required", c.get("/api/v1/market").error())

        val ts = clock.millis()
        val nonce = s.key.nonce()
        assertEquals(HttpStatusCode.OK, c.signedGet(s, "/api/v1/market", ts, nonce).status)
        val replay = c.signedGet(s, "/api/v1/market", ts, nonce)
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals("replayed", replay.error())

        assertEquals("bad_signature", c.signedGet(s, "/api/v1/market", signedPath = "/api/v1/me").error())
        val stale = c.signedGet(s, "/api/v1/market", ts = clock.millis() - 60_000)
        assertEquals("clock_skew", stale.error())
        assertEquals(clock.millis().toString(), stale.headers[RequestSignature.HEADER_SERVER_TIME])

        // A stolen session id is useless without the device's private key.
        val thief = s.copy(key = DeviceKey())
        assertEquals("bad_signature", c.signedGet(thief, "/api/v1/market").error())
    }

    @Test
    fun `requests are rate limited per session`() = testApplication {
        val c = client()
        val s = c.login("spammer@yahoo.com")
        val statuses = (1..40).map { c.signedGet(s, "/api/v1/me").status }
        assertTrue(HttpStatusCode.TooManyRequests in statuses)
        val limited = c.signedGet(s, "/api/v1/me")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers["Retry-After"])
    }

    @Test
    fun `the alias trap refuses aliases and badges the main account`() = testApplication {
        val c = client()
        val main = c.login("bananas@gmail.com")

        for (alias in listOf("bananas+abc@gmail.com", "bananas+xyz@googlemail.com")) {
            val (challenge, code) = c.requestCode(alias)
            val r = c.verify(challenge, code)
            assertEquals(HttpStatusCode.Forbidden, r.status)
            assertEquals("alias_refused", r.error())
        }

        val badges = json(c.signedGet(main, "/api/v1/me").bodyAsText())["badges"]!!.jsonArray
        val niceTry = badges.single().jsonObject
        assertEquals("NICE_TRY", niceTry["badge"]!!.jsonPrimitive.content)
        assertEquals(2, niceTry["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `plus aliases are refused the same way whether or not the base account exists`() = testApplication {
        val c = client()
        c.login("exists@gmail.com")
        val responses = listOf("exists+alt@gmail.com", "nobody+alt@gmail.com").map { alias ->
            val (challenge, code) = c.requestCode(alias) // same 202 for both
            val r = c.verify(challenge, code)
            r.status to r.bodyAsText()
        }
        assertEquals(responses[0], responses[1]) // indistinguishable: no account enumeration
        assertEquals(HttpStatusCode.Forbidden, responses[0].first)
    }

    @Test
    fun `unknown providers are refused`() = testApplication {
        val c = client()
        for (email in listOf("me@mycompany.io", "x@mailinator.com")) {
            val r = c.post("/api/v1/auth/otp/request") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status, email)
            assertEquals("email_rejected", r.error())
        }
    }

    @Test
    fun `gmail dot variants sign in to the same account without a badge`() = testApplication {
        val c = client()
        val main = c.login("john.doe@gmail.com")
        val dotted = c.login("johndoe@gmail.com")
        val again = c.login("j.o.h.n.d.o.e@googlemail.com")
        assertEquals(main.accountId, dotted.accountId)
        assertEquals(main.accountId, again.accountId)
        assertTrue(json(c.signedGet(main, "/api/v1/me").bodyAsText())["badges"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `other people's code requests can't lock a player out`() = testApplication {
        val c = client()
        val (mine, code) = c.requestCode("victim@gmail.com")
        // An attacker requests many codes for the same address and guesses wrong on them.
        repeat(8) {
            val (theirs, _) = c.requestCode("vic.tim@gmail.com")
            repeat(5) { c.verify(theirs, "000000") }
        }
        // The victim's own challenge is untouched.
        assertEquals(HttpStatusCode.OK, c.verify(mine, code).status)
    }

    @Test
    fun `codes lock after too many wrong guesses`() = testApplication {
        val c = client()
        val (challenge, code) = c.requestCode("guess@icloud.com")
        val wrong = if (code == "000000") "111112" else "000000"
        repeat(5) { c.verify(challenge, wrong) }
        assertEquals("otp_rejected", c.verify(challenge, code).error())
    }

    @Test
    fun `logout revokes the session`() = testApplication {
        val c = client()
        val s = c.login("leaver@proton.me")
        val ts = clock.millis()
        val nonce = s.key.nonce()
        val r = c.post("/api/v1/auth/logout") {
            header(RequestSignature.HEADER_SESSION, s.id)
            header(RequestSignature.HEADER_TIMESTAMP, ts.toString())
            header(RequestSignature.HEADER_NONCE, nonce)
            header(RequestSignature.HEADER_SIGNATURE, s.key.sign("POST", "/api/v1/auth/logout", ts, nonce))
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertEquals("session_invalid", c.signedGet(s, "/api/v1/me").error())
    }

    @Test
    fun `market data endpoints`() = testApplication {
        val c = client()
        val s = c.login("reader@gmail.com")

        val market = json(c.signedGet(s, "/api/v1/market").bodyAsText())
        assertEquals(50, market["quotes"]!!.jsonArray.size)
        assertEquals("CLOSED", market["session"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        val quote = json(c.signedGet(s, "/api/v1/quotes/foof").bodyAsText())
        assertEquals("Foofle", quote["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val daily = json(c.signedGet(s, "/api/v1/quotes/FOOF/candles?res=1d").bodyAsText())
        assertEquals(3, daily["candles"]!!.jsonArray.size)

        assertEquals(HttpStatusCode.NotFound, c.signedGet(s, "/api/v1/quotes/NOPE").status)
        assertEquals(HttpStatusCode.BadRequest, c.signedGet(s, "/api/v1/quotes/FOOF/candles?res=7h").status)
    }

    @Test
    fun `news, calendar and fundamentals`() = testApplication {
        val c = client()
        val s = c.login("analyst@gmail.com")

        val all = json(c.signedGet(s, "/api/v1/news?limit=100").bodyAsText())["news"]!!.jsonArray.map { it.jsonObject }
        assertTrue(all.isNotEmpty(), "3 sessions of 50 companies make some news")
        val ids = all.map { it["id"]!!.jsonPrimitive.long }
        assertEquals(ids.sortedDescending(), ids, "newest first")
        val market = json(c.signedGet(s, "/api/v1/market").bodyAsText())
        assertEquals(ids.first(), market["latestNewsId"]!!.jsonPrimitive.long)

        val ticker = all.firstNotNullOf { it["ticker"]?.takeIf { t -> t !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content }
        val filtered = json(c.signedGet(s, "/api/v1/news?ticker=$ticker").bodyAsText())["news"]!!.jsonArray.map { it.jsonObject }
        assertTrue(filtered.isNotEmpty())
        filtered.forEach { n -> val t = n["ticker"]; assertTrue(t is kotlinx.serialization.json.JsonNull || t!!.jsonPrimitive.content == ticker) }
        val older = json(c.signedGet(s, "/api/v1/news?before=${ids.first()}&limit=1").bodyAsText())["news"]!!.jsonArray
        if (ids.size > 1) assertEquals(ids[1], older.single().jsonObject["id"]!!.jsonPrimitive.long)

        val cal = json(c.signedGet(s, "/api/v1/calendar").bodyAsText())
        val events = cal["events"]!!.jsonArray.map { it.jsonObject }
        assertTrue(events.any { it["kind"]!!.jsonPrimitive.content == "RATE_DECISION" })
        assertTrue(events.count { it["kind"]!!.jsonPrimitive.content == "EARNINGS" } >= 40, "most companies report within 60 days")
        assertEquals(4.0, cal["benchmarkRate"]!!.jsonPrimitive.content.toDouble())

        val quote = json(c.signedGet(s, "/api/v1/quotes/FOOF").bodyAsText())
        val f = quote["fundamentals"]!!.jsonObject
        assertTrue(f["epsTtm"]!!.jsonPrimitive.long > 0)
        assertEquals("ACTIVE", f["status"]!!.jsonPrimitive.content)
        assertNotNull(f["nextEarnings"]!!.jsonPrimitive.content)
    }

    @Test
    fun `websocket requires a signed handshake and streams ticks`() = testApplication {
        val c = client()
        val s = c.login("streamer@gmail.com")

        val ts = clock.millis()
        val nonce = s.key.nonce()
        val sig = s.key.sign("GET", "/api/v1/ws", ts, nonce)
        val q = "session=${s.id}&ts=$ts&nonce=$nonce&sig=${URLEncoder.encode(sig, "UTF-8")}"

        c.webSocket("/api/v1/ws?$q") {
            val hello = json((incoming.receive() as Frame.Text).readText())
            assertEquals("tick", hello["type"]!!.jsonPrimitive.content)
            send(Frame.Text("""{"op":"subscribe","quotes":["FOOF","MHRD"],"depth":["FOOF"]}"""))
            assertEquals("subscribed", json((incoming.receive() as Frame.Text).readText())["type"]!!.jsonPrimitive.content)
            // Current data arrives immediately, even while the market is closed.
            val snapshot = json((incoming.receive() as Frame.Text).readText())
            assertEquals("CLOSED", snapshot["session"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(2, snapshot["quotes"]!!.jsonArray.size)

            // Open the 14:30 session and run a minute of ticks.
            clock.now = Instant.parse("2026-09-29T18:31:00Z")
            app.market.pump(clock.instant())
            var frame: JsonObject
            do {
                frame = json((incoming.receive() as Frame.Text).readText())
            } while (frame["quotes"]!!.jsonArray.isEmpty())
            assertEquals("OPEN", frame["session"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(setOf("FOOF", "MHRD"), frame["quotes"]!!.jsonArray.map { it.jsonObject["ticker"]!!.jsonPrimitive.content }.toSet())
            assertEquals(10, frame["depth"]!!.jsonObject["FOOF"]!!.jsonObject["asks"]!!.jsonArray.size)
        }

        // Replaying the handshake is refused before the upgrade.
        assertNotNull(kotlin.runCatching { c.webSocket("/api/v1/ws?$q") { } }.exceptionOrNull())
        // …while a freshly signed handshake still connects.
        val ts2 = clock.millis()
        val nonce2 = s.key.nonce()
        val q2 = "session=${s.id}&ts=$ts2&nonce=$nonce2&sig=${s.key.sign("GET", "/api/v1/ws", ts2, nonce2)}"
        c.webSocket("/api/v1/ws?$q2") { assertTrue(incoming.receive() is Frame.Text) }
    }
}
