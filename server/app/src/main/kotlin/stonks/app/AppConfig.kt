package stonks.app

import org.slf4j.LoggerFactory

/** Runtime configuration, read from environment variables (see README). */
data class AppConfig(
    val port: Int = 8080,
    val dbUrl: String = "jdbc:postgresql://localhost:5432/stonks",
    val dbUser: String = "stonks",
    val dbPassword: String = "stonks",
    /** World seed; only used when a world is first created. Null = random. */
    val worldSeed: Long? = null,
    /** Game days of simulated history generated at world creation. */
    val backfillSessions: Int = 500,
    /** Secret mixed into OTP hashes. Must be set in production. */
    val otpPepper: String = DEV_PEPPER,
    /**
     * Development conveniences: the OTP code is returned in the API response and
     * CORS allows localhost dev servers. Never enable in production.
     */
    val devMode: Boolean = false,
    /** Honour X-Forwarded-For (only behind a reverse proxy you control). */
    val trustProxy: Boolean = false,
    val corsOrigins: List<String> = emptyList(),
    /**
     * TEMPORARY: every sign-in code is this value (until real email delivery exists).
     * Anyone who knows it can sign in as any address. Set STONKS_FIXED_OTP to an empty
     * string to use random codes.
     */
    val fixedOtp: String? = "111111",
    /** Development only: shifts the server clock, e.g. to try the market outside hours. */
    val devTimeShiftHours: Long = 0,
    /** Overrides the built-in email provider allowlist when non-empty. */
    val allowedEmailDomains: Set<String> = emptySet(),
    val limits: Limits = Limits(),
) {
    data class Limits(
        /** Per-session request budget: burst capacity and tokens refilled per second. */
        val sessionBurst: Double = 40.0,
        val sessionPerSecond: Double = 10.0,
        /** Per-account budget across all of its sessions. */
        val accountBurst: Double = 80.0,
        val accountPerSecond: Double = 20.0,
        /** Pre-authentication budget per client IP (protects signature verification). */
        val ipBurst: Double = 100.0,
        val ipPerSecond: Double = 50.0,
        /** Concurrent in-flight requests per account. */
        val inFlightPerAccount: Int = 8,
        val maxSessionsPerAccount: Int = 5,
        val maxSocketsPerAccount: Int = 3,
        /** Sign-in emails per address per 15 minutes (beyond this: silently not sent). */
        val otpEmailsPerAddress: Int = 5,
        /** Code requests per IP per hour. */
        val otpPerIpHour: Int = 10,
        /** OTP verification attempts per IP per hour. */
        val verifyPerIpHour: Int = 30,
    )

    companion object {
        const val DEV_PEPPER = "dev-only-pepper-change-me"
        private val log = LoggerFactory.getLogger(AppConfig::class.java)

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun str(name: String) = env["STONKS_$name"]?.takeIf { it.isNotBlank() }
            fun list(name: String) = str(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val c = AppConfig(
                port = str("PORT")?.toInt() ?: 8080,
                dbUrl = str("DB_URL") ?: "jdbc:postgresql://localhost:5432/stonks",
                dbUser = str("DB_USER") ?: "stonks",
                dbPassword = str("DB_PASSWORD") ?: "stonks",
                worldSeed = str("WORLD_SEED")?.toLong(),
                backfillSessions = str("BACKFILL_SESSIONS")?.toInt() ?: 500,
                otpPepper = str("OTP_PEPPER") ?: DEV_PEPPER,
                devMode = str("DEV_MODE")?.toBoolean() ?: false,
                trustProxy = str("TRUST_PROXY")?.toBoolean() ?: false,
                corsOrigins = list("CORS_ORIGINS"),
                devTimeShiftHours = str("DEV_TIME_SHIFT_HOURS")?.toLong() ?: 0,
                fixedOtp = env["STONKS_FIXED_OTP"].let { if (it == null) "111111" else it.trim().ifEmpty { null } },
                allowedEmailDomains = list("ALLOWED_EMAIL_DOMAINS").map { it.lowercase() }.toSet(),
            )
            if (c.otpPepper == DEV_PEPPER && !c.devMode) {
                log.warn("STONKS_OTP_PEPPER is not set; using the development default. Set it in production.")
            }
            if (c.devMode) log.warn("STONKS_DEV_MODE is on: OTP codes are returned in API responses.")
            c.fixedOtp?.let {
                require(Regex("^[0-9]{6}$").matches(it)) { "STONKS_FIXED_OTP must be 6 digits (or empty to disable)" }
                log.warn("STONKS_FIXED_OTP is set: every sign-in code is {}. Anyone can sign in as any address. Temporary only.", it)
            }
            require(c.devTimeShiftHours == 0L || c.devMode) { "STONKS_DEV_TIME_SHIFT_HOURS requires STONKS_DEV_MODE" }
            return c
        }
    }
}
