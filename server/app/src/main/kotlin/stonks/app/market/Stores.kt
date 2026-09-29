package stonks.app.market

import org.postgresql.PGConnection
import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.singleOrNull
import stonks.app.db.update
import stonks.engine.sim.Candle
import stonks.engine.sim.Resolution
import java.io.StringReader
import java.sql.Timestamp
import java.time.Instant

data class WorldRow(val seed: Long, val liveAt: Instant)

class WorldStore(private val db: Db) {
    fun load(): WorldRow? = db.read { c ->
        c.query("select seed, live_at from world where id = 1") { rs -> rs.singleOrNull { WorldRow(it.getLong(1), it.instant("live_at")!!) } }
    }

    fun create(seed: Long, liveAt: Instant) {
        db.tx { c -> c.update("insert into world (id, seed, live_at) values (1, ?, ?)", seed, liveAt) }
    }

    fun saveSnapshot(sessionsCompleted: Int, lastClose: Instant?, data: ByteArray, keep: Int = 48) {
        db.tx { c ->
            c.update(
                "insert into world_snapshots (sessions_completed, last_close, data) values (?, ?, ?)",
                sessionsCompleted, lastClose, data,
            )
            c.update("delete from world_snapshots where id not in (select id from world_snapshots order by id desc limit ?)", keep)
        }
    }

    fun latestSnapshot(): ByteArray? = db.read { c ->
        c.query("select data from world_snapshots order by id desc limit 1") { rs -> rs.singleOrNull { it.getBytes(1) } }
    }
}

data class TickerCandle(val ticker: String, val resolution: Resolution, val candle: Candle)

class CandleStore(private val db: Db) {
    private fun table(r: Resolution) = when (r) {
        Resolution.S5 -> "candles_5s"
        Resolution.M1 -> "candles_1m"
        Resolution.D1 -> "candles_1d"
    }

    /** Idempotent batch insert (replayed ticks after a crash are ignored). */
    fun insert(batch: List<TickerCandle>) {
        if (batch.isEmpty()) return
        db.tx { c ->
            for ((res, rows) in batch.groupBy { it.resolution }) {
                c.prepareStatement(
                    "insert into ${table(res)} (ticker, time, open, high, low, close, volume) values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                ).use { ps ->
                    for (r in rows) {
                        ps.setString(1, r.ticker)
                        ps.setTimestamp(2, Timestamp.from(r.candle.time))
                        ps.setLong(3, r.candle.open); ps.setLong(4, r.candle.high); ps.setLong(5, r.candle.low)
                        ps.setLong(6, r.candle.close); ps.setLong(7, r.candle.volume)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    /** Fast bulk load via COPY into staging, then an idempotent merge. */
    fun bulkInsert(batch: List<TickerCandle>) {
        if (batch.isEmpty()) return
        db.tx { c ->
            for ((res, rows) in batch.groupBy { it.resolution }) {
                c.update("create temp table if not exists candle_stage (like candles_5s including defaults) on commit drop")
                c.update("truncate candle_stage")
                val csv = StringBuilder(rows.size * 64)
                for (r in rows) {
                    val k = r.candle
                    csv.append(r.ticker).append(',').append(k.time).append(',').append(k.open).append(',').append(k.high)
                        .append(',').append(k.low).append(',').append(k.close).append(',').append(k.volume).append('\n')
                }
                c.unwrap(PGConnection::class.java).copyAPI.copyIn("copy candle_stage from stdin with (format csv)", StringReader(csv.toString()))
                c.update("insert into ${table(res)} select * from candle_stage on conflict do nothing")
            }
        }
    }

    fun query(ticker: String, res: Resolution, from: Instant?, to: Instant?, limit: Int): List<Candle> = db.read { c ->
        // Newest `limit` candles in range, returned oldest first.
        c.query(
            """select * from (
                 select time, open, high, low, close, volume from ${table(res)}
                 where ticker = ? and time >= coalesce(?, '-infinity'::timestamptz) and time <= coalesce(?, 'infinity'::timestamptz)
                 order by time desc limit ?
               ) t order by time""",
            ticker, from?.let(Timestamp::from), to?.let(Timestamp::from), limit,
        ) { rs -> rs.list { Candle(it.instant("time")!!, it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6)) } }
    }

    data class DailyStats(val high52: Long?, val low52: Long?, val avgVolume30: Long?)

    /** 52-week (252 game days) range and 30-day average volume. */
    fun dailyStats(ticker: String): DailyStats = db.read { c ->
        c.query(
            """with d as (select high, low, volume, row_number() over (order by time desc) as n from candles_1d where ticker = ?)
               select max(high) filter (where n <= 252), min(low) filter (where n <= 252), avg(volume) filter (where n <= 30) from d""",
            ticker,
        ) { rs ->
            rs.next()
            DailyStats(rs.getObject(1) as Long?, rs.getObject(2) as Long?, (rs.getObject(3) as java.math.BigDecimal?)?.toLong())
        }
    }

    /** Deletes expired intraday candles when TimescaleDB retention is unavailable. */
    fun sweepExpired(now: Instant) {
        if (db.hasTimescale) return
        db.tx { c ->
            c.update("delete from candles_5s where time < ?", now.minus(RETENTION_5S))
            c.update("delete from candles_1m where time < ?", now.minus(RETENTION_1M))
        }
    }

    companion object {
        val RETENTION_5S: java.time.Duration = java.time.Duration.ofDays(7)
        val RETENTION_1M: java.time.Duration = java.time.Duration.ofDays(60)
    }
}
