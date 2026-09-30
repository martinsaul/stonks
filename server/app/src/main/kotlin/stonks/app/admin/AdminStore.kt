package stonks.app.admin

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.singleOrNull
import stonks.app.db.update
import stonks.engine.sim.Market
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.Instant

@Serializable data class AuditEntry(val id: Long, val at: String, val admin: String, val action: String, val target: String?, val payload: String, val result: String?)
@Serializable data class PlayerRow(val id: Long, val name: String, val email: String, val createdAt: String, val bannedAt: String?)
@Serializable data class SessionRow(val ip: String?, val userAgent: String?, val createdAt: String, val lastSeenAt: String, val revoked: Boolean)
@Serializable data class FillRow(
    val id: String, val ticker: String, val side: String, val quantity: Long, val price: Long, val commission: Long,
    val realized: Long, val counterparty: Long?, val at: String, val voided: Boolean, val liquidation: Boolean,
)
@Serializable data class EconomyRow(val kind: String, val amount: Long, val detail: String?, val at: String)
@Serializable data class FlagDto(val kind: String, val detail: String, val accountId: Long? = null)
@Serializable data class ReviewRow(val accountId: Long, val name: String, val status: String, val firstQualifiedAt: String, val reviewer: String?, val note: String?)
@Serializable data class StatRow(
    val day: Int, val at: String, val players: Int, val totalWorth: Long, val totalCash: Long, val totalDebt: Long,
    val inDebt: Int, val millionaires: Int, val medianWorth: Long,
)
@Serializable data class SnapshotRow(val id: Long, val takenAt: String, val sessionsCompleted: Int, val lastClose: String?)

/** Database side of the admin console: audit trail, bans, reviews, flags, stats, rollbacks. */
class AdminStore(private val db: Db) {
    private val log = LoggerFactory.getLogger(AdminStore::class.java)

    fun audit(adminId: Long, action: String, target: String?, payload: String, result: String?) {
        db.tx { c ->
            c.update("insert into admin_actions (admin_id, action, target, payload, result) values (?, ?, ?, ?::jsonb, ?)", adminId, action, target, payload, result)
        }
    }

    fun auditLog(limit: Int): List<AuditEntry> = db.read { c ->
        c.query(
            """select x.id, x.at, coalesce(a.display_name, x.admin_id::text), x.action, x.target, x.payload::text, x.result
               from admin_actions x left join accounts a on a.id = x.admin_id order by x.id desc limit ?""",
            limit,
        ) { rs -> rs.list { AuditEntry(it.getLong(1), it.instant("at")!!.toString(), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)) } }
    }

    fun search(q: String): List<PlayerRow> = db.read { c ->
        val id = q.toLongOrNull()
        c.query(
            """select id, display_name, email, created_at, banned_at from accounts
               where id = ? or display_name ilike ? or email ilike ? order by id limit 50""",
            id ?: -1L, "%${q.replace("%", "")}%", "%${q.replace("%", "")}%",
        ) { rs -> rs.list(::player) }
    }

    fun player(id: Long): PlayerRow? = db.read { c ->
        c.query("select id, display_name, email, created_at, banned_at from accounts where id = ?", id) { rs -> rs.singleOrNull(::player) }
    }

    private fun player(rs: java.sql.ResultSet) =
        PlayerRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.instant("created_at")!!.toString(), rs.instant("banned_at")?.toString())

    fun sessions(id: Long): List<SessionRow> = db.read { c ->
        c.query("select ip, user_agent, created_at, last_seen_at, revoked_at from sessions where account_id = ? order by created_at desc limit 20", id) { rs ->
            rs.list { SessionRow(it.getString(1), it.getString(2), it.instant("created_at")!!.toString(), it.instant("last_seen_at")!!.toString(), it.instant("revoked_at") != null) }
        }
    }

    fun fills(id: Long, limit: Int): List<FillRow> = db.read { c ->
        c.query(
            """select id, ticker, side, quantity, price, commission, realized, counterparty_id, at, voided_at, liquidation
               from fills where account_id = ? order by at desc, id desc limit ?""",
            id, limit,
        ) { rs ->
            rs.list {
                FillRow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6), it.getLong(7),
                    it.getObject(8) as Long?, it.instant("at")!!.toString(), it.instant("voided_at") != null, it.getBoolean(11))
            }
        }
    }

    fun fill(fillId: String): Pair<Long, FillRow>? = db.read { c ->
        c.query(
            "select id, ticker, side, quantity, price, commission, realized, counterparty_id, at, voided_at, liquidation, account_id from fills where id = ?",
            fillId,
        ) { rs ->
            rs.singleOrNull {
                it.getLong(12) to FillRow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6), it.getLong(7),
                    it.getObject(8) as Long?, it.instant("at")!!.toString(), it.instant("voided_at") != null, it.getBoolean(11))
            }
        }
    }

    fun markVoided(fillId: String, at: Instant) {
        db.tx { c -> c.update("update fills set voided_at = ? where id = ?", at, fillId) }
    }

    fun economy(id: Long): List<EconomyRow> = db.read { c ->
        c.query("select kind, amount, detail, at from economy_events where account_id = ? order by at desc limit 50", id) { rs ->
            rs.list { EconomyRow(it.getString(1), it.getLong(2), it.getString(3), it.instant("at")!!.toString()) }
        }
    }

    /**
     * Soft collusion signals (never automatic bans): shared sign-in IPs, repeated
     * player counterparties, alias attempts.
     */
    fun flags(id: Long): List<FlagDto> = db.read { c ->
        val out = ArrayList<FlagDto>()
        c.query(
            """select distinct s2.account_id, a.display_name, s1.ip from sessions s1
               join sessions s2 on s2.ip = s1.ip and s2.account_id <> s1.account_id
               join accounts a on a.id = s2.account_id
               where s1.account_id = ? and s1.ip is not null limit 20""",
            id,
        ) { rs -> rs.list { FlagDto("shared_ip", "Signed in from ${it.getString(3)}, as did ${it.getString(2)}", it.getLong(1)) } }.let(out::addAll)
        c.query(
            """select f.counterparty_id, a.display_name, count(*), sum(f.quantity * f.price) from fills f
               join accounts a on a.id = f.counterparty_id
               where f.account_id = ? and f.counterparty_id is not null group by 1, 2 having count(*) >= 3 order by 3 desc limit 20""",
            id,
        ) { rs ->
            rs.list { FlagDto("counterparty", "${it.getLong(3)} fills (${stonks.engine.trading.Ledger.money(it.getLong(4))}) against ${it.getString(2)}", it.getLong(1)) }
        }.let(out::addAll)
        c.query("select count(*) from account_badges where account_id = ? and badge = 'NICE_TRY'", id) { rs ->
            rs.next(); rs.getInt(1)
        }.takeIf { it > 0 }?.let { out += FlagDto("alias", "$it alias sign-in attempts") }
        out
    }

    fun setBanned(id: Long, reason: String?, banned: Boolean, now: Instant) {
        db.tx { c ->
            if (banned) {
                c.update("update accounts set banned_at = ?, ban_reason = ? where id = ?", now, reason, id)
                c.update("update sessions set revoked_at = ? where account_id = ? and revoked_at is null", now, id)
            } else {
                c.update("update accounts set banned_at = null, ban_reason = null where id = ?", id)
            }
        }
    }

    fun reviews(): List<ReviewRow> = db.read { c ->
        c.query(
            """select r.account_id, a.display_name, r.status, r.first_qualified_at, coalesce(ad.display_name, r.reviewer), r.note
               from leaderboard_reviews r join accounts a on a.id = r.account_id
               left join accounts ad on ad.id::text = r.reviewer
               order by case r.status when 'PENDING' then 0 else 1 end, r.first_qualified_at""",
        ) { rs -> rs.list { ReviewRow(it.getLong(1), it.getString(2), it.getString(3), it.instant("first_qualified_at")!!.toString(), it.getString(5), it.getString(6)) } }
    }

    fun review(id: Long, status: String, adminId: Long, note: String?, now: Instant): Boolean = db.tx { c ->
        c.update(
            "update leaderboard_reviews set status = ?, reviewed_at = ?, reviewer = ?, note = ? where account_id = ?",
            status, now, adminId.toString(), note, id,
        ) == 1
    }

    fun recordStats(day: Int, at: Instant, worths: List<Long>, cash: Long, debt: Long) {
        val sorted = worths.sorted()
        db.tx { c ->
            c.update(
                """insert into economy_stats (game_day, at, players, total_worth, total_cash, total_debt, in_debt, millionaires, median_worth)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (game_day) do update set at = excluded.at, players = excluded.players,
                   total_worth = excluded.total_worth, total_cash = excluded.total_cash, total_debt = excluded.total_debt,
                   in_debt = excluded.in_debt, millionaires = excluded.millionaires, median_worth = excluded.median_worth""",
                day, at, worths.size, worths.sum(), cash, debt, worths.count { it < 0 }, worths.count { it > 100_000_000L },
                if (sorted.isEmpty()) 0L else sorted[sorted.size / 2],
            )
        }
    }

    fun stats(limit: Int): List<StatRow> = db.read { c ->
        c.query("select * from (select * from economy_stats order by game_day desc limit ?) s order by game_day", limit) { rs ->
            rs.list {
                StatRow(it.getInt("game_day"), it.instant("at")!!.toString(), it.getInt("players"), it.getLong("total_worth"), it.getLong("total_cash"),
                    it.getLong("total_debt"), it.getInt("in_debt"), it.getInt("millionaires"), it.getLong("median_worth"))
            }
        }
    }

    fun snapshots(): List<SnapshotRow> = db.read { c ->
        c.query("select id, taken_at, sessions_completed, last_close from world_snapshots order by id desc") { rs ->
            rs.list { SnapshotRow(it.getLong(1), it.instant("taken_at")!!.toString(), it.getInt(3), it.instant("last_close")?.toString()) }
        }
    }

    fun requestRollback(snapshotId: Long, adminId: Long): Boolean = db.tx { c ->
        val exists = c.query("select 1 from world_snapshots where id = ?", snapshotId) { it.next() }
        if (exists) c.update("insert into world_rollbacks (snapshot_id, requested_by) values (?, ?)", snapshotId, adminId)
        exists
    }

    /**
     * Applies a requested world rollback (call before the market starts): later
     * snapshots, player inputs and derived data are deleted, so the world restores the
     * chosen snapshot and re-simulates from there without the discarded inputs.
     */
    fun applyPendingRollback(now: Instant): Boolean {
        val pending = db.read { c ->
            c.query("select id, snapshot_id from world_rollbacks where applied_at is null order by id desc limit 1") { rs ->
                rs.singleOrNull { it.getLong(1) to it.getLong(2) }
            }
        } ?: return false
        val (requestId, snapshotId) = pending
        val snap = db.read { c ->
            c.query("select data, last_close from world_snapshots where id = ?", snapshotId) { rs -> rs.singleOrNull { it.getBytes(1) to it.instant("last_close") } }
        }
        if (snap == null) {
            log.error("Rollback {}: snapshot {} no longer exists; skipped", requestId, snapshotId)
            db.tx { c -> c.update("update world_rollbacks set applied_at = ? where id = ?", now, requestId) }
            return false
        }
        val market = Market.readSnapshot(DataInputStream(ByteArrayInputStream(snap.first)))
        val seq = market.lastInputSeq
        val day = market.day
        val after = snap.second ?: Instant.EPOCH
        db.tx { c ->
            c.update("delete from world_snapshots where id > ?", snapshotId)
            c.update("delete from engine_inputs where seq > ?", seq)
            for (t in listOf("candles_5s", "candles_1m", "candles_1d")) c.update("delete from $t where time >= ?", after)
            c.update("delete from fills where game_day >= ?", day)
            c.update("delete from orders where (group_id > ? and id < ?) or (id >= ? and updated_at > ?)", seq, Market.LIQUIDATION_ID_BASE, Market.LIQUIDATION_ID_BASE, after)
            c.update("delete from news where day >= ?", day)
            c.update("delete from corporate_actions where day >= ?", day)
            c.update("delete from economy_events where at > ?", after)
            c.update("delete from account_badges where event_key is not null and awarded_at > ?", after)
            c.update("delete from economy_stats where game_day >= ?", day)
            c.update("update world_rollbacks set applied_at = ? where id = ?", now, requestId)
        }
        log.warn("World rolled back to snapshot {} (day {}, input {}): later inputs discarded", snapshotId, day, seq)
        return true
    }
}
