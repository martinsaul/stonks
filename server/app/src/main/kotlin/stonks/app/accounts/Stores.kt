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

data class Account(val id: Long, val email: String, val canonical: String, val createdAt: Instant)

enum class Badge(val title: String, val description: String) {
    NICE_TRY("Nice Try", "Attempted to register an alias of this account."),
}

data class BadgeCount(val badge: Badge, val count: Int, val lastAwardedAt: Instant)

class AccountStore(private val db: Db) {
    fun byCanonical(c: Connection, canonical: String): Account? =
        c.query("select * from accounts where email_canonical = ?", canonical) { it.singleOrNull(::map) }

    fun byId(id: Long): Account? = db.read { c -> c.query("select * from accounts where id = ?", id) { it.singleOrNull(::map) } }

    fun create(c: Connection, email: String, canonical: String): Account =
        c.query("insert into accounts (email, email_canonical) values (?, ?) returning *", email, canonical) { it.next(); map(it) }

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
        ) { rs -> rs.list { BadgeCount(Badge.valueOf(it.getString("badge")), it.getInt("n"), it.instant("last")!!) } }
    }

    private fun map(rs: java.sql.ResultSet) =
        Account(rs.getLong("id"), rs.getString("email"), rs.getString("email_canonical"), rs.instant("created_at")!!)
}

/** Append-only event log. */
class EventLog {
    fun append(c: Connection, type: String, accountId: Long?, payload: JsonObject = JsonObject(emptyMap())) {
        c.update("insert into events (type, account_id, payload) values (?, ?, ?::jsonb)", type, accountId, payload.toString())
    }
}
