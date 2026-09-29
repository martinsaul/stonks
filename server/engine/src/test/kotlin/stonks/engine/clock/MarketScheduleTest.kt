package stonks.engine.clock

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketScheduleTest {
    private val schedule = MarketSchedule()
    private fun et(s: String) = ZonedDateTime.parse(s + "-04:00[America/New_York]").toInstant()

    @Test
    fun `weekdays have two sessions of 5400 ticks`() {
        val sessions = schedule.sessionsOn(LocalDate.of(2026, 9, 29)) // Tuesday
        assertEquals(listOf(SessionKind.WEEKDAY_A, SessionKind.WEEKDAY_B), sessions.map { it.kind })
        assertEquals(listOf(5400, 5400), sessions.map { it.ticks })
        assertEquals(et("2026-09-29T06:00:00"), sessions[0].open)
        assertEquals(et("2026-09-29T22:00:00"), sessions[1].close)
    }

    @Test
    fun `weekends have one reduced session`() {
        val sessions = schedule.sessionsOn(LocalDate.of(2026, 10, 3)) // Saturday
        assertEquals(1, sessions.size)
        assertEquals(SessionKind.WEEKEND, sessions[0].kind)
        assertEquals(7200, sessions[0].ticks)
        assertEquals(0.5, sessions[0].kind.volatilityMultiplier)
    }

    @Test
    fun `DST change does not alter local session hours`() {
        val sunday = schedule.sessionsOn(LocalDate.of(2026, 11, 1)).single()
        assertEquals(7200, sunday.ticks)
        assertEquals(10, sunday.open.atZone(schedule.zone).hour)
    }

    @Test
    fun `session lookup and next open`() {
        assertEquals(SessionKind.WEEKDAY_A, schedule.sessionAt(et("2026-09-29T08:00:00"))?.kind)
        assertNull(schedule.sessionAt(et("2026-09-29T14:00:00"))) // midday break
        assertNull(schedule.sessionAt(et("2026-09-29T23:00:00")))
        val next = schedule.nextSessionAfter(et("2026-10-02T23:00:00")) // Friday night
        assertEquals(SessionKind.WEEKEND, next.kind)
        assertEquals(et("2026-10-03T10:00:00"), next.open)
    }

    @Test
    fun `sessionsBefore returns completed sessions oldest first`() {
        val at = et("2026-09-29T15:00:00") // during Tuesday session B
        val sessions = schedule.sessionsBefore(at, 12)
        assertEquals(12, sessions.size)
        assertTrue(sessions.zipWithNext().all { (a, b) -> a.close <= b.open })
        assertTrue(sessions.last().close <= at)
        assertEquals(SessionKind.WEEKDAY_A, sessions.last().kind)
        // A week back spans exactly one weekend: 12 game days per week.
        assertEquals(2, sessions.count { it.kind == SessionKind.WEEKEND })
    }
}
