package stonks.app.market

import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.engine.sim.Market
import stonks.engine.world.NewsItem
import java.sql.Timestamp
import java.time.Instant

/** A news item with its wall-clock publication time. */
data class StampedNews(val item: NewsItem, val at: Instant)

data class ActionRow(val ticker: String, val day: Int, val kind: String, val ratio: Double, val at: Instant)

class NewsStore(private val db: Db) {
    fun insert(news: List<StampedNews>, actions: List<ActionRow>) {
        if (news.isEmpty() && actions.isEmpty()) return
        db.tx { c ->
            if (news.isNotEmpty()) c.prepareStatement(
                """insert into news (id, published_at, day, ticker, sector, category, headline, sentiment)
                   values (?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""",
            ).use { ps ->
                for ((n, at) in news) {
                    ps.setLong(1, n.id); ps.setTimestamp(2, Timestamp.from(at)); ps.setInt(3, n.day)
                    ps.setString(4, n.ticker); ps.setString(5, n.sector?.name); ps.setString(6, n.category.name)
                    ps.setString(7, n.headline); ps.setDouble(8, n.sentiment)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            if (actions.isNotEmpty()) c.prepareStatement(
                "insert into corporate_actions (ticker, day, kind, ratio, at) values (?, ?, ?, ?, ?) on conflict do nothing",
            ).use { ps ->
                for (a in actions) {
                    ps.setString(1, a.ticker); ps.setInt(2, a.day); ps.setString(3, a.kind); ps.setDouble(4, a.ratio)
                    ps.setTimestamp(5, Timestamp.from(a.at))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Newest first. [ticker] also matches market-wide and same-sector news when [sector] is given. */
    fun query(ticker: String?, sector: String?, beforeId: Long?, afterId: Long?, limit: Int): List<NewsDto> = db.read { c ->
        c.query(
            """select id, published_at, day, ticker, sector, category, headline, sentiment from news
               where (cast(? as text) is null or ticker = cast(? as text) or (ticker is null and (sector is null or sector = cast(? as text))))
                 and id < coalesce(cast(? as bigint), 9223372036854775807) and id > coalesce(cast(? as bigint), 0)
               order by id desc limit ?""",
            ticker, ticker, sector, beforeId, afterId, limit,
        ) { rs ->
            rs.list {
                val s = it.getDouble(8)
                NewsDto(
                    it.getLong(1), it.instant("published_at")!!.toString(), it.getInt(3), it.getString(4), it.getString(5),
                    it.getString(6), it.getString(7), if (s > 0.005) "positive" else if (s < -0.005) "negative" else "neutral",
                )
            }
        }
    }

    fun latestId(): Long = db.read { c -> c.query("select coalesce(max(id), 0) from news") { rs -> rs.next(); rs.getLong(1) } }

    /** Splits for [ticker], oldest first. */
    fun splits(ticker: String): List<ActionRow> = db.read { c ->
        c.query("select ticker, day, kind, ratio, at from corporate_actions where ticker = ? and kind = ? order by day", ticker, Market.CorporateAction.Kind.SPLIT.name) { rs ->
            rs.list { ActionRow(it.getString(1), it.getInt(2), it.getString(3), it.getDouble(4), it.instant("at")!!) }
        }
    }
}

/** Rescales candles from before each split into today's shares (prices / ratio, volume * ratio). */
fun adjustForSplits(candles: List<stonks.engine.sim.Candle>, splits: List<ActionRow>): List<stonks.engine.sim.Candle> {
    if (splits.isEmpty()) return candles
    return candles.map { k ->
        val f = splits.fold(1.0) { acc, s -> if (s.at.isAfter(k.time)) acc * s.ratio else acc }
        if (f == 1.0) k else stonks.engine.sim.Candle(
            k.time, Math.round(k.open / f), Math.round(k.high / f), Math.round(k.low / f), Math.round(k.close / f), Math.round(k.volume * f),
        )
    }
}
