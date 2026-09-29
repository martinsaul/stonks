package stonks.engine.sim

import stonks.engine.clock.MarketSchedule
import stonks.engine.clock.Session
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Simulates market history before the world goes live, using the same engine as live
 * play (docs/DESIGN.md, "Historical data").
 */
object Backfill {
    fun run(
        market: Market,
        schedule: MarketSchedule,
        liveAt: Instant,
        sessions: Int,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        onSession: (index: Int, session: Session) -> Unit = { _, _ -> },
    ): List<Session> {
        val history = schedule.sessionsBefore(liveAt, sessions)
        val pool = if (threads > 1) Executors.newFixedThreadPool(threads) else null
        try {
            history.forEachIndexed { i, s ->
                market.runSession(s, pool)
                onSession(i, s)
            }
        } finally {
            pool?.shutdown()
        }
        return history
    }
}
