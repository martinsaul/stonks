package stonks.app.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** Thin JDBC layer: a pooled data source plus transaction and mapping helpers. */
class Db(val dataSource: DataSource) : AutoCloseable {

    fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    fun <T> read(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.isReadOnly = true
        try { block(c) } finally { c.isReadOnly = false }
    }

    /** True when the TimescaleDB extension is installed in this database. */
    val hasTimescale: Boolean by lazy {
        read { c -> c.query("select 1 from pg_extension where extname = 'timescaledb'") { it.next() } }
    }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        fun connect(url: String, user: String, password: String, poolSize: Int = 16): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                poolName = "stonks"
            }
            val ds = HikariDataSource(cfg)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds)
        }
    }
}

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): T =
    prepareStatement(sql).use { ps ->
        ps.bind(*params)
        ps.executeQuery().use(map)
    }

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps ->
        ps.bind(*params)
        ps.executeUpdate()
    }

fun <T> ResultSet.list(map: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += map(this)
    return out
}

fun <T> ResultSet.singleOrNull(map: (ResultSet) -> T): T? = if (next()) map(this) else null

fun PreparedStatement.bind(vararg params: Any?) {
    params.forEachIndexed { i, p ->
        val idx = i + 1
        when (p) {
            null -> setObject(idx, null)
            is Instant -> setTimestamp(idx, Timestamp.from(p))
            is ByteArray -> setBytes(idx, p)
            else -> setObject(idx, p)
        }
    }
}

fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
