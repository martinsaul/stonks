package stonks.engine.clock

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class SessionKind(val volatilityMultiplier: Double) {
    WEEKDAY_A(1.0),
    WEEKDAY_B(1.0),
    WEEKEND(0.5),
}

/** One trading session. Every session is exactly one game day. */
data class Session(
    val kind: SessionKind,
    val open: Instant,
    val close: Instant,
) {
    val ticks: Int get() = (Duration.between(open, close).seconds / MarketSchedule.TICK_SECONDS).toInt()
    fun tickTime(tick: Int): Instant = open.plusSeconds(tick.toLong() * MarketSchedule.TICK_SECONDS)
}

/**
 * Regional trading calendar (see docs/DESIGN.md, "Regions & schedule").
 *
 * Weekdays: 06:00–13:30 and 14:30–22:00. Weekends: 10:00–20:00.
 */
class MarketSchedule(val zone: ZoneId = ZoneId.of("America/New_York")) {

    fun sessionsOn(date: LocalDate): List<Session> {
        fun s(kind: SessionKind, from: LocalTime, to: LocalTime) =
            Session(kind, date.atTime(from).atZone(zone).toInstant(), date.atTime(to).atZone(zone).toInstant())

        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY ->
                listOf(s(SessionKind.WEEKEND, LocalTime.of(10, 0), LocalTime.of(20, 0)))
            else -> listOf(
                s(SessionKind.WEEKDAY_A, LocalTime.of(6, 0), LocalTime.of(13, 30)),
                s(SessionKind.WEEKDAY_B, LocalTime.of(14, 30), LocalTime.of(22, 0)),
            )
        }
    }

    /** The session in progress at [at], or null when the market is closed. */
    fun sessionAt(at: Instant): Session? =
        sessionsOn(at.atZone(zone).toLocalDate()).firstOrNull { !at.isBefore(it.open) && at.isBefore(it.close) }

    /** The first session opening strictly after [at]. */
    fun nextSessionAfter(at: Instant): Session {
        var date = at.atZone(zone).toLocalDate()
        while (true) {
            sessionsOn(date).firstOrNull { it.open.isAfter(at) }?.let { return it }
            date = date.plusDays(1)
        }
    }

    /** The [count] sessions that closed at or before [at], oldest first. */
    fun sessionsBefore(at: Instant, count: Int): List<Session> {
        val result = ArrayDeque<Session>()
        var date = at.atZone(zone).toLocalDate()
        while (result.size < count) {
            for (s in sessionsOn(date).asReversed()) {
                if (!s.close.isAfter(at) && result.size < count) result.addFirst(s)
            }
            date = date.minusDays(1)
        }
        return result.toList()
    }

    companion object {
        const val TICK_SECONDS = 5L

        /** Game days per game year, used to annualize drift and volatility. */
        const val GAME_DAYS_PER_YEAR = 252.0
    }
}
