package stonks.app.trading

import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.update
import stonks.engine.trading.FillEvent
import stonks.engine.trading.OrderUpdate
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

data class LoggedInput(val seq: Long, val applyDay: Int, val applyTick: Int, val kind: String, val accountId: Long, val payload: String)

data class NewInput(val applyDay: Int, val applyTick: Int, val kind: String, val accountId: Long, val payload: String)

class TradingStore(private val db: Db) {

    /** Appends inputs in one transaction; returns their sequence numbers in order. */
    fun log(inputs: List<NewInput>): List<Long> = if (inputs.isEmpty()) emptyList() else db.tx { c ->
        c.prepareStatement(
            "insert into engine_inputs (apply_day, apply_tick, kind, account_id, payload) values (?, ?, ?, ?, ?::jsonb) returning seq",
        ).use { ps ->
            inputs.map { i ->
                ps.setInt(1, i.applyDay); ps.setInt(2, i.applyTick); ps.setString(3, i.kind)
                ps.setLong(4, i.accountId); ps.setString(5, i.payload)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    fun inputsAfter(seq: Long): List<LoggedInput> = db.read { c ->
        c.query("select * from engine_inputs where seq > ? order by seq", seq) { rs ->
            rs.list { LoggedInput(it.getLong("seq"), it.getInt("apply_day"), it.getInt("apply_tick"), it.getString("kind"), it.getLong("account_id"), it.getString("payload")) }
        }
    }

    fun writeEvents(orders: Collection<OrderUpdate>, fills: List<FillEvent>, at: Instant) {
        if (orders.isEmpty() && fills.isEmpty()) return
        db.tx { c ->
            if (orders.isNotEmpty()) c.prepareStatement(
                """insert into orders (id, group_id, account_id, ticker, side, kind, quantity, status, filled, avg_price, limit_price, stop_price, reason, liquidation, created_at, updated_at)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   on conflict (id) do update set quantity = excluded.quantity, status = excluded.status, filled = excluded.filled,
                     avg_price = excluded.avg_price, limit_price = excluded.limit_price, stop_price = excluded.stop_price,
                     reason = excluded.reason, updated_at = excluded.updated_at""",
            ).use { ps ->
                val ts = Timestamp.from(at)
                for (o in orders) {
                    ps.setLong(1, o.orderId); ps.setLong(2, o.groupId); ps.setLong(3, o.accountId); ps.setString(4, o.ticker)
                    ps.setString(5, o.side.name); ps.setString(6, o.kind.name); ps.setLong(7, o.quantity); ps.setString(8, o.status.name)
                    ps.setLong(9, o.filled)
                    if (o.averagePrice == null) ps.setNull(10, Types.DOUBLE) else ps.setDouble(10, o.averagePrice!!)
                    if (o.limit == null) ps.setNull(11, Types.BIGINT) else ps.setLong(11, o.limit!!)
                    if (o.stop == null) ps.setNull(12, Types.BIGINT) else ps.setLong(12, o.stop!!)
                    ps.setString(13, o.reason); ps.setBoolean(14, o.liquidation); ps.setTimestamp(15, ts); ps.setTimestamp(16, ts)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            if (fills.isNotEmpty()) c.prepareStatement(
                """insert into fills (id, order_id, account_id, ticker, side, quantity, price, commission, realized, maker, liquidation, game_day, tick, at)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing""",
            ).use { ps ->
                for (f in fills) {
                    ps.setString(1, f.fillId); ps.setLong(2, f.orderId); ps.setLong(3, f.accountId); ps.setString(4, f.ticker)
                    ps.setString(5, f.side.name); ps.setLong(6, f.quantity); ps.setLong(7, f.price); ps.setLong(8, f.commission)
                    ps.setLong(9, f.realized); ps.setBoolean(10, f.maker); ps.setBoolean(11, f.liquidation)
                    ps.setInt(12, f.day); ps.setInt(13, f.tick); ps.setTimestamp(14, Timestamp.from(at))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Records economy events and awards achievement badges, once per event key. */
    fun writeEconomy(events: List<stonks.engine.trading.AccountEvent>, at: Instant) {
        if (events.isEmpty()) return
        db.tx { c ->
            for (e in events) {
                val key = e.key ?: continue
                c.update(
                    "insert into economy_events (key, account_id, kind, amount, detail, at) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                    key, e.accountId, e.kind.name, e.amount, e.detail, at,
                )
                if (e.kind == stonks.engine.trading.AccountEvent.Kind.ACHIEVEMENT && stonks.app.accounts.Badge.of(e.detail) != null) {
                    c.update(
                        "insert into account_badges (account_id, badge, event_key, awarded_at) values (?, ?, ?, ?) on conflict do nothing",
                        e.accountId, e.detail, key, at,
                    )
                }
            }
        }
    }

    fun writePortfolios(rows: Collection<PortfolioDto>) {
        if (rows.isEmpty()) return
        db.tx { c ->
            c.prepareStatement(
                """insert into portfolios (account_id, plan, cash, equity, lifetime_realized, updated_at) values (?, ?, ?, ?, ?, now())
                   on conflict (account_id) do update set plan = excluded.plan, cash = excluded.cash, equity = excluded.equity,
                     lifetime_realized = excluded.lifetime_realized, updated_at = now()""",
            ).use { ps ->
                for (p in rows) {
                    ps.setLong(1, p.accountId); ps.setString(2, p.plan); ps.setLong(3, p.cash); ps.setLong(4, p.equity)
                    ps.setLong(5, p.lifetimeRealized)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun recentOrders(accountId: Long, limit: Int): List<OrderDto> = db.read { c ->
        c.query("select * from orders where account_id = ? order by updated_at desc, id desc limit ?", accountId, limit) { rs ->
            rs.list {
                OrderDto(
                    id = it.getLong("id"), groupId = it.getLong("group_id"), ticker = it.getString("ticker"),
                    side = it.getString("side"), type = it.getString("kind"), quantity = it.getLong("quantity"),
                    filled = it.getLong("filled"), avgPrice = (it.getObject("avg_price") as Double?),
                    limitPrice = it.getObject("limit_price") as Long?, stopPrice = it.getObject("stop_price") as Long?,
                    status = it.getString("status"), reason = it.getString("reason"), liquidation = it.getBoolean("liquidation"),
                    updatedAt = it.instant("updated_at").toString(),
                )
            }
        }
    }

    fun fills(accountId: Long, limit: Int): List<FillDto> = db.read { c ->
        c.query("select * from fills where account_id = ? order by at desc, id desc limit ?", accountId, limit) { rs ->
            rs.list {
                FillDto(
                    id = it.getString("id"), orderId = it.getLong("order_id"), ticker = it.getString("ticker"), side = it.getString("side"),
                    quantity = it.getLong("quantity"), price = it.getLong("price"), commission = it.getLong("commission"),
                    realized = it.getLong("realized"), maker = it.getBoolean("maker"), liquidation = it.getBoolean("liquidation"),
                    at = it.instant("at").toString(),
                )
            }
        }
    }
}
