package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule.*
import org.junit.Test
import kotlin.test.*

class PlanReminderRulesTest {
    private val now = instant("2026-01-01T09:00:00Z")
    @Test fun `instant deadline reminder uses deadline rather than inactive start`() {
        val plan = timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = instant("2026-01-01T12:00:00Z"))
        assertEquals(instant("2026-01-01T11:45:00Z"), PlanReminderRules.next(plan, now)?.triggerAt)
    }
    @Test fun `date deadline reminder uses plan local nine minus lead`() {
        val plan = timedPlan(zone = "Asia/Tokyo").copy(timeMode = PlanTimeMode.DEADLINE, dueDate = day("2026-01-02"), reminderMinutes = 30)
        assertEquals(instant("2026-01-01T23:30:00Z"), PlanReminderRules.next(plan, now)?.triggerAt)
        assertEquals(PlanOccurrenceTime.DeadlineDate(day("2026-01-02")), PlanReminderRules.next(plan, now)?.occurrence?.time)
    }
    @Test fun `explicit all day reminder uses nine at start not stale instant`() {
        val plan = allDayPlan(zone = "America/New_York").copy(reminderMinutes = 0)
        assertEquals(instant("2026-01-01T14:00:00Z"), PlanReminderRules.next(plan, now)?.triggerAt)
    }
    @Test fun `all day daily reminder follows DST offset at local nine`() {
        val plan = allDayPlan(zone = "America/New_York").copy(allDayStartDate = day("2026-03-07"), allDayEndDate = day("2026-03-07"), repeatRule = DAILY, reminderMinutes = 0)
        assertEquals(instant("2026-03-08T13:00:00Z"), PlanReminderRules.next(plan, instant("2026-03-07T15:00:00Z"))?.triggerAt)
    }
    @Test fun `undated retained parameters never authorize reminder`() {
        assertNull(PlanReminderRules.next(timedPlan().copy(timeMode = PlanTimeMode.NONE, repeatRule = DAILY), now))
    }
    @Test fun `global item permission and nonnull minutes are all required`() {
        assertNull(PlanReminderRules.next(timedPlan(), now, planRemindersEnabled = false))
        assertNull(PlanReminderRules.next(timedPlan(), now, notificationsAllowed = false))
        assertNull(PlanReminderRules.next(timedPlan().copy(reminderEnabled = false), now))
        assertNull(PlanReminderRules.next(timedPlan().copy(reminderMinutes = null), now))
    }
    @Test fun `completed series schedules nothing until undo`() {
        val plan = timedPlan().copy(repeatRule = DAILY, isCompleted = true)
        assertNull(PlanReminderRules.next(plan, now))
        assertEquals(day("2026-01-01"), PlanReminderRules.next(PlanRules.undoCompletion(plan, now), now)?.occurrence?.occurrenceDate)
    }
    @Test fun `future occurrence with missed trigger skips to next occurrence`() {
        val plan = timedPlan().copy(repeatRule = DAILY)
        assertEquals(instant("2026-01-02T09:45:00Z"), PlanReminderRules.next(plan, instant("2026-01-01T09:50:00Z"))?.triggerAt)
    }
    @Test fun `next day event can trigger before current day ends`() {
        val plan = timedPlan().copy(startAt = instant("2026-01-02T00:05:00Z"), endAt = instant("2026-01-02T00:30:00Z"))
        assertEquals(instant("2026-01-01T23:50:00Z"), PlanReminderRules.next(plan, instant("2026-01-01T23:49:00Z"))?.triggerAt)
    }
    @Test fun `trigger exactly now is not resent`() {
        assertNull(PlanReminderRules.next(timedPlan(), instant("2026-01-01T09:45:00Z")))
    }
    @Test fun `multi day lead seeks future occurrence from trigger time`() {
        val result = PlanReminderRules.next(timedPlan().copy(repeatRule = DAILY, reminderMinutes = 2_880), now, maxCandidates = 3)
        assertEquals(day("2026-01-03"), result?.occurrence?.occurrenceDate)
        assertEquals(instant("2026-01-01T10:00:00Z"), result?.triggerAt)
    }
    @Test fun `undo never reanchors series or catches up missed reminders`() {
        val plan = PlanRules.undoCompletion(timedPlan().copy(repeatRule = DAILY, repeatInterval = 3, isCompleted = true), now)
        assertEquals(instant("2026-01-04T09:45:00Z"), PlanReminderRules.next(plan, instant("2026-01-02T09:00:00Z"))?.triggerAt)
    }
    @Test fun `cancelled occurrence is skipped before qualification`() {
        assertEquals(day("2026-01-02"), PlanReminderRules.next(timedPlan().copy(repeatRule = DAILY), now, listOf(PlanException(1, day("2026-01-01"), true)))?.occurrence?.occurrenceDate)
    }
    @Test fun `repeat end date stops future reminders without catchup`() {
        assertNull(PlanReminderRules.next(timedPlan().copy(repeatRule = DAILY, repeatEndDate = day("2026-01-01")), instant("2026-01-01T09:50:00Z")))
    }
    @Test fun `sparse six year recurrence is found beyond old two year horizon`() {
        assertEquals(instant("2032-01-01T09:45:00Z"), PlanReminderRules.next(timedPlan().copy(repeatRule = YEARLY, repeatInterval = 6), instant("2026-01-02T00:00:00Z"))?.triggerAt)
    }
    @Test fun `far future single deadline is not limited to two years`() {
        assertEquals(instant("2032-01-01T09:45:00Z"), PlanReminderRules.next(timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = instant("2032-01-01T10:00:00Z")), now)?.triggerAt)
    }
    @Test fun `historical blank title and fixed offset stay computable`() {
        assertEquals(instant("2026-01-01T09:45:00Z"), PlanReminderRules.next(timedPlan(zone = "+08:00").copy(title = " "), now)?.triggerAt)
    }
    @Test fun `cancelled candidates count toward work budget and do not report no reminder`() {
        val exceptions = listOf(PlanException(1, day("2026-01-01"), true), PlanException(1, day("2026-01-02"), true))
        assertFailsWith<PlanExpansionLimitException> { PlanReminderRules.next(timedPlan().copy(repeatRule = DAILY), now, exceptions, maxCandidates = 2) }
    }
    @Test fun `monthly reminders share original month end anchor`() {
        val plan = timedPlan().copy(startAt = instant("2026-01-31T10:00:00Z"), endAt = instant("2026-01-31T11:00:00Z"), repeatRule = MONTHLY)
        assertEquals(instant("2026-03-31T09:45:00Z"), PlanReminderRules.next(plan, instant("2026-03-01T00:00:00Z"))?.triggerAt)
    }
    @Test fun `invalid plan fails rather than masquerading as disabled reminder`() {
        assertFailsWith<IllegalArgumentException> { PlanReminderRules.next(timedPlan().copy(repeatInterval = 0), now) }
    }
    @Test fun `large lead minutes are widened before arithmetic`() {
        val plan = timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = instant("2032-01-01T10:00:00Z"), reminderMinutes = Int.MAX_VALUE)
        assertNull(PlanReminderRules.next(plan, now))
    }
    @Test fun `time arithmetic exhaustion is reported not a false no reminder`() {
        assertFailsWith<java.time.DateTimeException> { PlanReminderRules.next(timedPlan().copy(repeatRule = DAILY), java.time.Instant.MAX) }
    }
}
