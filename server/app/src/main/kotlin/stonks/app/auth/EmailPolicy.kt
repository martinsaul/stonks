package stonks.app.auth

/** Result of checking an address offered for sign-in. */
sealed interface EmailCheck {
    /** Acceptable; [canonical] is the uniqueness key, [hasPlusTag] marks `+tag` aliases. */
    data class Ok(val email: String, val canonical: String, val hasPlusTag: Boolean) : EmailCheck
    data class Rejected(val reason: String) : EmailCheck
}

/**
 * Email rules (docs/DESIGN.md, "Accounts & auth"): well-known providers only, and a
 * canonical form that folds aliases (`+tags`, Gmail dots) onto one account.
 */
class EmailPolicy(allowedDomains: Set<String> = emptySet()) {
    private val allowed = allowedDomains.ifEmpty { DEFAULT_DOMAINS }

    fun check(raw: String): EmailCheck {
        val email = raw.trim().lowercase()
        if (email.length > 254 || !SHAPE.matches(email)) return EmailCheck.Rejected("That doesn't look like an email address.")
        val (local, domain) = email.split('@', limit = 2)
        if (domain !in allowed) return EmailCheck.Rejected("Please use a well-known email provider (Gmail, Outlook, iCloud, …).")
        val base = local.substringBefore('+')
        if (base.isEmpty()) return EmailCheck.Rejected("That doesn't look like an email address.")
        return EmailCheck.Ok(email, canonical(base, domain), hasPlusTag = '+' in local)
    }

    private fun canonical(base: String, domain: String): String = when (domain) {
        "gmail.com", "googlemail.com" -> base.replace(".", "") + "@gmail.com"
        else -> "$base@$domain"
    }

    companion object {
        private val SHAPE = Regex("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")

        val DEFAULT_DOMAINS = setOf(
            "gmail.com", "googlemail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com",
            "yahoo.com", "ymail.com",
            "icloud.com", "me.com", "mac.com",
            "proton.me", "protonmail.com", "pm.me",
            "aol.com", "gmx.com", "gmx.net", "zoho.com", "fastmail.com", "mail.com", "yandex.com",
        )
    }
}
