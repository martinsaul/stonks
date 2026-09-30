package stonks.app.economy

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import stonks.app.accounts.AccountStore
import stonks.app.accounts.Badge
import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.update
import stonks.app.trading.PortfolioDto
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@Serializable
data class MillionaireDto(
    val rank: Int, val name: String, val netWorth: Long, val shame: Int, val eternalShame: Boolean, val plan: String,
)

@Serializable
data class MillionairesResponse(
    val entries: List<MillionaireDto>,
    /** Your review status if you qualify: PENDING, APPROVED or REJECTED. */
    val yourStatus: String? = null,
    val pendingReviews: Int,
)

@Serializable
data class SeasonEntryDto(
    val rank: Int?,
    val name: String,
    val returnPct: Double?,
    val netWorth: Long?,
    val trades: Int,
    val activeDays: Int,
    /** Why the entry isn't ranked (null when ranked). */
    val unranked: String? = null,
)

@Serializable
data class SeasonResponse(
    val season: String,
    val startsAt: String,
    val endsAt: String,
    val finalized: Boolean,
    val minTrades: Int,
    val minActiveDays: Int,
    val entries: List<SeasonEntryDto>,
    val you: SeasonEntryDto? = null,
    val seasons: List<String>,
)

/**
 * Leaderboards (docs/DESIGN.md, "Leaderboards"), refreshed every minute from live
 * portfolios and the database:
 * - Millionaires: net worth over $1M, listed after manual review, ranked by fewest
 *   outstanding badges of shame (Eternal Shame last), then net worth.
 * - Monthly seasons (UTC calendar months): return % since joining the season, net of
 *   weekly claims; a reset or bankruptcy disqualifies. Top finishers get badges.
 */
class Leaderboards(
    private val db: Db,
    private val accounts: AccountStore,
    private val portfolios: () -> Collection<PortfolioDto>,
    private val clock: Clock,
    val minTrades: Int = 10,
    val minActiveDays: Int = 3,
) {
    private val log = LoggerFactory.getLogger(Leaderboards::class.java)

    private data class Row(
        val accountId: Long, val name: String, val start: Long, val deposits: Long, val disqualified: Boolean,
        val trades: Int, val activeDays: Int, val finalWorth: Long?, val finalReturn: Double?, val finalRank: Int?,
    )

    private data class Ranked(val entries: List<Pair<Long, SeasonEntryDto>>)

    @Volatile private var millionaires: List<Pair<Long, MillionaireDto>> = emptyList()
    @Volatile private var pending = 0
    @Volatile private var currentSeason: Pair<String, Ranked>? = null

    /** Recomputes everything; call periodically (and in tests). */
    @Synchronized
    fun refresh() {
        val now = clock.instant()
        val ports = portfolios().associateBy { it.accountId }
        val season = seasonId(now)
        ensureSeason(season)
        finalizeEnded(now, ports)
        join(season, ports, now)
        currentSeason = season to rank(season, ports, live = true)
        refreshMillionaires(ports)
    }

    fun millionaires(accountId: Long): MillionairesResponse {
        val status = db.read { c ->
            c.query("select status from leaderboard_reviews where account_id = ?", accountId) { rs -> if (rs.next()) rs.getString(1) else null }
        }
        return MillionairesResponse(millionaires.take(100).map { it.second }, status, pending)
    }

    fun season(id: String?, accountId: Long): SeasonResponse? {
        val now = clock.instant()
        val sid = id ?: seasonId(now)
        if (!SEASON.matches(sid)) return null
        val meta = db.read { c ->
            c.query("select starts_at, ends_at, finalized_at from seasons where id = ?", sid) { rs ->
                if (rs.next()) Triple(rs.instant("starts_at")!!, rs.instant("ends_at")!!, rs.instant("finalized_at")) else null
            }
        } ?: return null
        val ranked = currentSeason?.takeIf { it.first == sid && meta.third == null }?.second
            ?: rank(sid, portfolios().associateBy { it.accountId }, live = meta.third == null)
        val seasons = db.read { c -> c.query("select id from seasons order by id desc limit 24") { rs -> rs.list { it.getString(1) } } }
        return SeasonResponse(
            sid, meta.first.toString(), meta.second.toString(), meta.third != null, minTrades, minActiveDays,
            ranked.entries.filter { it.second.rank != null }.take(100).map { it.second },
            ranked.entries.firstOrNull { it.first == accountId }?.second,
            seasons,
        )
    }

    /** Finished seasons for a player's profile: (season, rank, return %). */
    fun history(accountId: Long): List<Triple<String, Int?, Double?>> = db.read { c ->
        c.query(
            "select season, final_rank, final_return from season_entries e join seasons s on s.id = e.season where account_id = ? and s.finalized_at is not null order by season desc",
            accountId,
        ) { rs -> rs.list { Triple(it.getString(1), it.getObject(2) as Int?, it.getObject(3) as Double?) } }
    }

    // ---------------------------------------------------------------------------------

    private fun ensureSeason(id: String) {
        val ym = YearMonth.parse(id)
        val start = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        db.tx { c -> c.update("insert into seasons (id, starts_at, ends_at) values (?, ?, ?) on conflict do nothing", id, start, end) }
    }

    private fun join(season: String, ports: Map<Long, PortfolioDto>, now: Instant) {
        if (ports.isEmpty()) return
        val have = db.read { c ->
            c.query("select account_id from season_entries where season = ?", season) { rs -> rs.list { it.getLong(1) } }.toHashSet()
        }
        val missing = ports.values.filter { it.accountId !in have }
        if (missing.isEmpty()) return
        db.tx { c ->
            c.prepareStatement(
                "insert into season_entries (season, account_id, start_worth, joined_at) values (?, ?, ?, ?) on conflict do nothing",
            ).use { ps ->
                for (p in missing) {
                    ps.setString(1, season); ps.setLong(2, p.accountId); ps.setLong(3, p.standing.netWorth)
                    ps.setTimestamp(4, java.sql.Timestamp.from(now))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun rows(season: String): List<Row> = db.read { c ->
        c.query(
            """select e.account_id, a.display_name, e.start_worth,
                      coalesce((select sum(x.amount) from economy_events x where x.account_id = e.account_id and x.kind = 'CLAIMED'
                                and x.at >= e.joined_at and x.at < s.ends_at), 0),
                      exists (select 1 from economy_events x where x.account_id = e.account_id and x.kind in ('RESET', 'BANKRUPT')
                              and x.at >= e.joined_at and x.at < s.ends_at),
                      f.trades, f.days, e.final_worth, e.final_return, e.final_rank
               from season_entries e
               join seasons s on s.id = e.season
               join accounts a on a.id = e.account_id and a.banned_at is null
               left join lateral (
                   select count(*) as trades, count(distinct game_day) as days from fills
                   where account_id = e.account_id and not liquidation and at >= e.joined_at and at < s.ends_at
               ) f on true
               where e.season = ?""",
            season,
        ) { rs ->
            rs.list {
                Row(
                    it.getLong(1), it.getString(2), it.getLong(3), it.getLong(4), it.getBoolean(5), it.getInt(6), it.getInt(7),
                    it.getObject(8) as Long?, it.getObject(9) as Double?, it.getObject(10) as Int?,
                )
            }
        }
    }

    private fun rank(season: String, ports: Map<Long, PortfolioDto>, live: Boolean): Ranked {
        val rows = rows(season)
        if (!live) {
            val out = rows.map { r ->
                r.accountId to SeasonEntryDto(r.finalRank, r.name, r.finalReturn, r.finalWorth, r.trades, r.activeDays,
                    if (r.finalRank == null) reason(r, r.start + r.deposits) else null)
            }.sortedWith(compareBy(nullsLast()) { it.second.rank })
            return Ranked(out)
        }
        val scored = rows.map { r ->
            val worth = ports[r.accountId]?.standing?.netWorth
            val base = r.start + r.deposits
            val why = reason(r, base) ?: if (worth == null) "No portfolio" else null
            val ret = if (worth != null && base > 0) (worth - base) * 100.0 / base else null
            Triple(r, ret, why)
        }
        var rank = 0
        val ranked = scored.filter { it.third == null && it.second != null }.sortedByDescending { it.second }.map { (r, ret, _) ->
            r.accountId to SeasonEntryDto(++rank, r.name, ret, ports[r.accountId]?.standing?.netWorth, r.trades, r.activeDays)
        }
        val unranked = scored.filter { it.third != null || it.second == null }.map { (r, ret, why) ->
            r.accountId to SeasonEntryDto(null, r.name, ret, ports[r.accountId]?.standing?.netWorth, r.trades, r.activeDays, why ?: "Not ranked")
        }
        return Ranked(ranked + unranked)
    }

    private fun reason(r: Row, base: Long): String? = when {
        r.disqualified -> "Reset or went bankrupt this season"
        base <= 0 -> "Started the season in debt"
        r.trades < minTrades -> "Needs $minTrades trades (has ${r.trades})"
        r.activeDays < minActiveDays -> "Needs $minActiveDays active trading days (has ${r.activeDays})"
        else -> null
    }

    /** Freezes finished seasons and awards badges to the top ten. */
    private fun finalizeEnded(now: Instant, ports: Map<Long, PortfolioDto>) {
        val ended = db.read { c ->
            c.query("select id from seasons where finalized_at is null and ends_at <= ? order by id", now) { rs -> rs.list { it.getString(1) } }
        }
        for (season in ended) {
            val ranked = rank(season, ports, live = true).entries
            db.tx { c ->
                for ((id, e) in ranked) {
                    c.update(
                        "update season_entries set final_worth = ?, final_return = ?, final_rank = ? where season = ? and account_id = ?",
                        e.netWorth, e.returnPct, e.rank, season, id,
                    )
                    val badge = when (e.rank) {
                        1 -> Badge.SEASON_CHAMPION
                        2, 3 -> Badge.SEASON_PODIUM
                        in 4..10 -> Badge.SEASON_TOP10
                        else -> null
                    }
                    if (badge != null) accounts.awardBadgeOnce(c, id, badge, "Season $season, rank ${e.rank}", "season:$season:$id")
                }
                c.update("update seasons set finalized_at = ? where id = ?", now, season)
            }
            log.info("Season {} finalized: {} ranked players", season, ranked.count { it.second.rank != null })
        }
    }

    private fun refreshMillionaires(ports: Map<Long, PortfolioDto>) {
        val rich = ports.values.filter { it.standing.netWorth > MILLION }
        if (rich.isNotEmpty()) db.tx { c ->
            c.prepareStatement("insert into leaderboard_reviews (account_id) values (?) on conflict do nothing").use { ps ->
                for (p in rich) { ps.setLong(1, p.accountId); ps.addBatch() }
                ps.executeBatch()
            }
        }
        val statuses = db.read { c ->
            c.query("select r.account_id, r.status from leaderboard_reviews r join accounts a on a.id = r.account_id where a.banned_at is null") { rs -> rs.list { it.getLong(1) to it.getString(2) } }.toMap()
        }
        pending = statuses.values.count { it == "PENDING" }
        val approved = rich.filter { statuses[it.accountId] == "APPROVED" }
        val names = accounts.displayNames(approved.map { it.accountId })
        var rank = 0
        millionaires = approved
            .sortedWith(compareBy<PortfolioDto>({ it.standing.eternalShame }, { it.standing.shame }).thenByDescending { it.standing.netWorth })
            .map { p ->
                p.accountId to MillionaireDto(++rank, names[p.accountId] ?: "Trader${p.accountId}", p.standing.netWorth,
                    p.standing.shame, p.standing.eternalShame, p.plan)
            }
    }

    companion object {
        const val MILLION = 1_000_000_00L
        private val SEASON = Regex("^[0-9]{4}-[0-9]{2}$")
        fun seasonId(at: Instant): String = YearMonth.from(at.atZone(ZoneOffset.UTC)).toString()
    }
}
