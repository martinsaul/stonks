package stonks.app

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import stonks.app.auth.RequestSignature
import stonks.app.db.Db
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class TestClock(@Volatile var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

object TestDb {
    private val pg: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }
    private val counter = AtomicInteger()

    /** A fresh, migrated database. */
    fun fresh(): Db {
        val name = "stonks_test_${counter.incrementAndGet()}"
        pg.postgresDatabase.connection.use { it.createStatement().execute("create database $name") }
        return Db.connect(pg.getJdbcUrl("postgres", name), "postgres", "postgres", poolSize = 4)
    }

    /** Reconnects to an existing test database (simulates a server restart). */
    fun reconnect(db: Db): Db {
        val url = (db.dataSource as com.zaxxer.hikari.HikariDataSource).jdbcUrl
        return Db.connect(url, "postgres", "postgres", poolSize = 4)
    }
}

/** A client-side device key, as the web client would hold in WebCrypto. */
class DeviceKey {
    val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicSpki: String = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
    private val random = SecureRandom()

    fun nonce(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun sign(method: String, pathAndQuery: String, ts: Long, nonce: String, body: ByteArray = ByteArray(0)): String {
        val canonical = RequestSignature.canonical(method, pathAndQuery, ts, nonce, body)
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(pair.private)
            update(canonical.toByteArray())
        }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }
}
