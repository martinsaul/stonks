package stonks.app.accounts

import kotlinx.serialization.json.JsonObject
import stonks.app.db.Db
import stonks.app.db.instant
import stonks.app.db.list
import stonks.app.db.query
import stonks.app.db.singleOrNull
import stonks.app.db.update
import java.sql.Connection
import java.time.Instant

data class Account(val id: Long, val email: String, val canonical: String, val createdAt: Instant, val displayName: String)

/** Cosmetic badges (badges of shame live in the engine; see Standing). */
enum class Badge(val title: String, val description: String) {
    NICE_TRY("Nice Try", "Attempted to register an alias of this account."),
    MIDAS_HANDS("Midas' Hands", "Bought at the bottom, sold at the top."),
    SADIMS_HANDS("Sadim's Hands", "Bought at the top, sold at the bottom."),
    DOUBLED_UP("Doubled Up", "Doubled your starting cash."),
    TEN_BAGGER("Ten-Bagger", "Grew your starting cash tenfold."),
    HUNDRED_BAGGER("Hundred-Bagger", "Grew your starting cash a hundredfold."),
    SEASON_CHAMPION("Season Champion", "Finished a season in first place."),
    SEASON_PODIUM("Season Podium", "Finished a season in the top three."),
    SEASON_TOP10("Season Top 10", "Finished a season in the top ten."),
    ;

    companion object {
        fun of(name: String): Badge? = entries.firstOrNull { it.name == name }
    }
}

data class BadgeCount(val badge: Badge, val count: Int, val lastAwardedAt: Instant)

class AccountStore(private val db: Db) {
    fun byCanonical(c: Connection, canonical: String): Account? =
        c.query("select * from accounts where email_canonical = ?", canonical) { it.singleOrNull(::map) }

    fun byId(id: Long): Account? = db.read { c -> c.query("select * from accounts where id = ?", id) { it.singleOrNull(::map) } }

    fun create(c: Connection, email: String, canonical: String): Account {
        val id = c.query("insert into accounts (email, email_canonical, display_name) values (?, ?, md5(random()::text)) returning id", email, canonical) {
            it.next(); it.getLong(1)
        }
        c.update("update accounts set display_name = 'Trader' || id where id = ?", id)
        return c.query("select * from accounts where id = ?", id) { it.next(); map(it) }
    }

    fun byDisplayName(name: String): Account? = db.read { c ->
        c.query("select * from accounts where lower(display_name) = lower(?)", name) { it.singleOrNull(::map) }
    }

    fun displayNames(ids: Collection<Long>): Map<Long, String> = if (ids.isEmpty()) emptyMap() else db.read { c ->
        c.query("select id, display_name from accounts where id = any(?)", c.createArrayOf("bigint", ids.toTypedArray())) { rs ->
            rs.list { it.getLong(1) to it.getString(2) }.toMap()
        }
    }

    /** Sets a new display name; returns an error message or null. */
    fun rename(id: Long, name: String): String? {
        if (!NAME.matches(name)) return "Names are 3–20 letters, digits, '_' or '-'."
        if (RESERVED.matches(name)) return "That name is reserved."
        return try {
            db.tx { c -> c.update("update accounts set display_name = ? where id = ?", name, id) }
            null
        } catch (e: java.sql.SQLException) {
            if (e.sqlState == "23505") "That name is taken." else throw e
        }
    }

    /** Awards [badge] once per [key] (engine events replay). */
    fun awardBadgeOnce(c: Connection, accountId: Long, badge: Badge, detail: String?, key: String) {
        c.update(
            "insert into account_badges (account_id, badge, detail, event_key) values (?, ?, ?, ?) on conflict do nothing",
            accountId, badge.name, detail, key,
        )
    }

    fun touchLogin(c: Connection, id: Long, at: Instant) {
        c.update("update accounts set last_login_at = ? where id = ?", at, id)
    }

    fun awardBadge(c: Connection, accountId: Long, badge: Badge, detail: String? = null) {
        c.update("insert into account_badges (account_id, badge, detail) values (?, ?, ?)", accountId, badge.name, detail)
    }

    fun badges(accountId: Long): List<BadgeCount> = db.read { c ->
        c.query(
            "select badge, count(*) as n, max(awarded_at) as last from account_badges where account_id = ? group by badge order by badge",
            accountId,
        ) { rs -> rs.list { r -> Badge.of(r.getString("badge"))?.let { BadgeCount(it, r.getInt("n"), r.instant("last")!!) } }.filterNotNull() }
    }

    private fun map(rs: java.sql.ResultSet) =
        Account(rs.getLong("id"), rs.getString("email"), rs.getString("email_canonical"), rs.instant("created_at")!!, rs.getString("display_name"))

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_-]{3,20}$")
        /** Generated names look like this; players can't pick one. */
        private val RESERVED = Regex("^(?i)(trader[0-9]+|admin.*|mod|moderator|stonks.*|system)$")
    }
}

/** Append-only event log. */
class EventLog {
    fun append(c: Connection, type: String, accountId: Long?, payload: JsonObject = JsonObject(emptyMap())) {
        c.update("insert into events (type, account_id, payload) values (?, ?, ?::jsonb)", type, accountId, payload.toString())
    }
}
