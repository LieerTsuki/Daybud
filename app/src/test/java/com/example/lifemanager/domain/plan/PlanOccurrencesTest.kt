package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule.*
import java.time.DayOfWeek.*
import java.time.Instant
import org.junit.Test
import kotlin.test.*

class PlanOccurrencesTest {
    @Test fun `undated plans with retained timing never enter calendar`() {
        assertTrue(PlanOccurrences.inWindow(timedPlan().copy(timeMode = PlanTimeMode.NONE, repeatRule = DAILY), window("2026-01-01", "2026-01-04")).isEmpty())
    }
    @Test fun `instant deadline remains a point without fabricated interval`() {
        val plan = timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = instant("2026-01-01T10:00:00Z"))
        assertEquals(listOf(PlanOccurrence(1, day("2026-01-01"), PlanOccurrenceTime.DeadlineInstant(instant("2026-01-01T10:00:00Z")))), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-02")))
    }
    @Test fun `point query includes start but excludes end`() {
        val plan = timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = instant("2026-01-01T10:00:00Z"))
        assertEquals(1, PlanOccurrences.inWindow(plan, PlanWindow(plan.dueAt!!, instant("2026-01-01T11:00:00Z"))).size)
        assertTrue(PlanOccurrences.inWindow(plan, PlanWindow(instant("2026-01-01T09:00:00Z"), plan.dueAt)).isEmpty())
    }
    @Test fun `date deadline retains local date across display zones`() {
        val plan = timedPlan(zone = "Asia/Tokyo").copy(timeMode = PlanTimeMode.DEADLINE, dueDate = day("2026-01-02"))
        assertEquals(listOf(PlanOccurrence(1, day("2026-01-02"), PlanOccurrenceTime.DeadlineDate(day("2026-01-02")))), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-02", "America/Los_Angeles")))
    }
    @Test fun `cross midnight interval intersects following day`() {
        val plan = timedPlan().copy(startAt = instant("2026-01-01T23:00:00Z"), endAt = instant("2026-01-02T01:00:00Z"))
        assertEquals(listOf(PlanOccurrence(1, day("2026-01-01"), PlanOccurrenceTime.Timed(plan.startAt!!, plan.endAt!!))), PlanOccurrences.inWindow(plan, window("2026-01-02", "2026-01-03")))
    }
    @Test fun `interval ending at midnight does not occupy next day`() {
        val plan = timedPlan().copy(endAt = instant("2026-01-02T00:00:00Z"))
        assertTrue(PlanOccurrences.inWindow(plan, window("2026-01-02", "2026-01-03")).isEmpty())
    }
    @Test fun `multi day daily intervals retain all starts overlapping the window`() {
        val plan = timedPlan().copy(startAt = instant("2026-01-01T23:00:00Z"), endAt = instant("2026-01-03T01:00:00Z"), repeatRule = DAILY)
        assertEquals(listOf(
            PlanOccurrenceTime.Timed(instant("2026-01-01T23:00:00Z"), instant("2026-01-03T01:00:00Z")),
            PlanOccurrenceTime.Timed(instant("2026-01-02T23:00:00Z"), instant("2026-01-04T01:00:00Z")),
            PlanOccurrenceTime.Timed(instant("2026-01-03T23:00:00Z"), instant("2026-01-05T01:00:00Z")),
        ), PlanOccurrences.inWindow(plan, window("2026-01-03", "2026-01-04")).map { it.time })
    }
    @Test fun `all day closed end remains visible on last day only`() {
        assertEquals(listOf(PlanOccurrence(1, day("2026-01-01"), PlanOccurrenceTime.AllDay(day("2026-01-01"), day("2026-01-02")))), PlanOccurrences.inWindow(allDayPlan(), window("2026-01-02", "2026-01-03")))
        assertTrue(PlanOccurrences.inWindow(allDayPlan(), window("2026-01-03", "2026-01-04")).isEmpty())
    }
    @Test fun `all day intersection uses plan timezone not viewer midnight`() {
        assertEquals(1, PlanOccurrences.inWindow(allDayPlan(zone = "Asia/Tokyo"), PlanWindow(instant("2025-12-31T15:00:00Z"), instant("2025-12-31T16:00:00Z"))).size)
        assertTrue(PlanOccurrences.inWindow(allDayPlan(zone = "Asia/Tokyo"), PlanWindow(instant("2025-12-31T14:00:00Z"), instant("2025-12-31T15:00:00Z"))).isEmpty())
    }
    @Test fun `completed single plan uses actual date only when requested`() {
        val plan = timedPlan().copy(isCompleted = true, completedAt = instant("2026-01-03T10:00:00Z"))
        assertTrue(PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-02")).isEmpty())
        assertEquals(1, PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-02"), includeCompleted = true).size)
        assertTrue(PlanOccurrences.inWindow(plan, window("2026-01-03", "2026-01-04"), includeCompleted = true).isEmpty())
    }
    @Test fun `completed repeating series creates exactly one completion marker`() {
        val plan = timedPlan().copy(repeatRule = DAILY, isCompleted = true, completedAt = instant("2026-01-03T10:00:00Z"))
        assertTrue(PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-10")).isEmpty())
        assertEquals(listOf(PlanOccurrence(1, day("2026-01-03"), PlanOccurrenceTime.Completion(plan.completedAt!!))), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-10"), includeCompleted = true))
    }
    @Test fun `legacy completed series without completion timestamp fabricates nothing`() {
        assertTrue(PlanOccurrences.inWindow(timedPlan().copy(repeatRule = DAILY, isCompleted = true), window("2026-01-01", "2026-01-04"), includeCompleted = true).isEmpty())
    }
    @Test fun `completion marker uses actual instant in viewer window`() {
        val plan = timedPlan(zone = "Asia/Tokyo").copy(repeatRule = DAILY, isCompleted = true, completedAt = instant("2026-01-02T00:00:00Z"))
        assertEquals(1, PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-02", "America/Los_Angeles"), includeCompleted = true).size)
    }
    @Test fun `daily intervals remain anchored and end date includes last start`() {
        val plan = timedPlan().copy(repeatRule = DAILY, repeatInterval = 2, repeatEndDate = day("2026-01-05"))
        assertEquals(listOf(day("2026-01-01"), day("2026-01-03"), day("2026-01-05")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-10")).map { it.occurrenceDate })
    }
    @Test fun `only matching cancelled exception filters a repeated start`() {
        val plan = timedPlan().copy(repeatRule = DAILY)
        val exceptions = listOf(PlanException(1, day("2026-01-01"), true), PlanException(2, day("2026-01-02"), true), PlanException(1, day("2026-01-03"), false))
        assertEquals(listOf(day("2026-01-02"), day("2026-01-03")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-04"), exceptions).map { it.occurrenceDate })
    }
    @Test fun `weekly weekdays retain legacy anchor seven day blocks`() {
        val plan = timedPlan().copy(repeatRule = WEEKLY, repeatInterval = 2, repeatDaysOfWeek = setOf(MONDAY, THURSDAY))
        assertEquals(listOf(day("2026-01-01"), day("2026-01-05"), day("2026-01-15"), day("2026-01-19")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-22")).map { it.occurrenceDate })
    }
    @Test fun `weekly empty weekdays defaults to anchor weekday`() {
        assertEquals(listOf(day("2026-01-01"), day("2026-01-15")), PlanOccurrences.inWindow(timedPlan().copy(repeatRule = WEEKLY, repeatInterval = 2), window("2026-01-01", "2026-01-22")).map { it.occurrenceDate })
    }
    @Test fun `custom without weekdays repeats by day interval`() {
        assertEquals(listOf(day("2026-01-02"), day("2026-01-05")), PlanOccurrences.inWindow(timedPlan().copy(startAt = instant("2026-01-02T10:00:00Z"), endAt = instant("2026-01-02T11:00:00Z"), repeatRule = CUSTOM, repeatInterval = 3), window("2026-01-01", "2026-01-07")).map { it.occurrenceDate })
    }
    @Test fun `custom with weekdays uses weekly interval and skips before anchor`() {
        assertEquals(listOf(day("2026-01-05"), day("2026-01-19")), PlanOccurrences.inWindow(timedPlan().copy(repeatRule = CUSTOM, repeatInterval = 2, repeatDaysOfWeek = setOf(MONDAY)), window("2025-12-25", "2026-01-22")).map { it.occurrenceDate })
    }
    @Test fun `monthly month end clamps from original anchor without drift`() {
        val plan = allDayPlan().copy(allDayStartDate = day("2026-01-31"), allDayEndDate = day("2026-01-31"), repeatRule = MONTHLY)
        assertEquals(listOf(day("2026-01-31"), day("2026-02-28"), day("2026-03-31")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-04-01")).map { it.occurrenceDate })
    }
    @Test fun `monthly interval skips months without resetting the anchor`() {
        val plan = allDayPlan().copy(allDayStartDate = day("2026-01-31"), allDayEndDate = day("2026-01-31"), repeatRule = MONTHLY, repeatInterval = 2)
        assertEquals(listOf(day("2026-01-31"), day("2026-03-31"), day("2026-05-31")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-06-01")).map { it.occurrenceDate })
    }
    @Test fun `yearly leap anchor returns to leap day without drift`() {
        val plan = allDayPlan().copy(allDayStartDate = day("2024-02-29"), allDayEndDate = day("2024-02-29"), repeatRule = YEARLY)
        assertEquals(listOf(day("2024-02-29"), day("2025-02-28"), day("2026-02-28"), day("2027-02-28"), day("2028-02-29")), PlanOccurrences.inWindow(plan, window("2024-01-01", "2029-01-01")).map { it.occurrenceDate })
    }
    @Test fun `DST spring gap shifts wall time but preserves elapsed duration`() {
        val plan = timedPlan(zone = "America/New_York").copy(startAt = instant("2026-03-07T07:30:00Z"), endAt = instant("2026-03-07T08:30:00Z"), repeatRule = DAILY)
        assertEquals(listOf(PlanOccurrenceTime.Timed(instant("2026-03-08T07:30:00Z"), instant("2026-03-08T08:30:00Z"))), PlanOccurrences.inWindow(plan, window("2026-03-08", "2026-03-09", "America/New_York")).map { it.time })
    }
    @Test fun `all day recurrence preserves date span across DST`() {
        val plan = allDayPlan(zone = "America/New_York").copy(allDayStartDate = day("2026-03-07"), allDayEndDate = day("2026-03-08"), repeatRule = DAILY)
        assertEquals(listOf(PlanOccurrenceTime.AllDay(day("2026-03-08"), day("2026-03-09")), PlanOccurrenceTime.AllDay(day("2026-03-09"), day("2026-03-10"))), PlanOccurrences.inWindow(plan, window("2026-03-09", "2026-03-10", "America/New_York")).map { it.time })
    }
    @Test fun `original ambiguous start retains later offset instant`() {
        val plan = timedPlan(zone = "America/New_York").copy(startAt = instant("2026-11-01T06:30:00Z"), endAt = instant("2026-11-01T07:30:00Z"), repeatRule = DAILY)
        assertEquals(listOf(PlanOccurrenceTime.Timed(plan.startAt!!, plan.endAt!!)), PlanOccurrences.inWindow(plan, window("2026-11-01", "2026-11-02", "America/New_York")).map { it.time })
    }
    @Test fun `future overlap uses earlier offset consistently`() {
        val plan = timedPlan(zone = "America/New_York").copy(startAt = instant("2026-10-31T05:30:00Z"), endAt = instant("2026-10-31T06:30:00Z"), repeatRule = DAILY)
        assertEquals(listOf(PlanOccurrenceTime.Timed(instant("2026-11-01T05:30:00Z"), instant("2026-11-01T06:30:00Z"))), PlanOccurrences.inWindow(plan, window("2026-11-01", "2026-11-02", "America/New_York")).map { it.time })
    }
    @Test fun `undo completion retains original cadence and cancelled starts`() {
        val plan = PlanRules.undoCompletion(timedPlan().copy(repeatRule = DAILY, repeatInterval = 2, isCompleted = true), Instant.EPOCH)
        assertEquals(listOf(day("2026-01-05")), PlanOccurrences.inWindow(plan, window("2026-01-02", "2026-01-06"), listOf(PlanException(1, day("2026-01-03"), true))).map { it.occurrenceDate })
    }
    @Test fun `inactive repeat end and exceptions do not suppress single event`() {
        assertEquals(1, PlanOccurrences.inWindow(timedPlan().copy(repeatEndDate = day("2020-01-01")), window("2026-01-01", "2026-01-02"), listOf(PlanException(1, day("2026-01-01"), true))).size)
    }
    @Test fun `invalid source never masquerades as empty calendar`() {
        assertFailsWith<IllegalArgumentException> { PlanOccurrences.inWindow(timedPlan().copy(timeZone = "bad/zone"), window("2026-01-01", "2026-01-02")) }
        assertFailsWith<IllegalArgumentException> { PlanOccurrences.inWindow(timedPlan().copy(endAt = timedPlan().startAt), window("2026-01-01", "2026-01-02")) }
    }
    @Test fun `window rejects empty or reversed range`() {
        assertFailsWith<IllegalArgumentException> { PlanWindow(Instant.EPOCH, Instant.EPOCH) }
        assertFailsWith<IllegalArgumentException> { PlanWindow(instant("2026-01-02T00:00:00Z"), Instant.EPOCH) }
    }
    @Test fun `ancient daily anchor jumps to bounded modern window`() {
        val plan = allDayPlan().copy(allDayStartDate = day("0001-01-01"), allDayEndDate = day("0001-01-01"), repeatRule = DAILY)
        assertEquals(listOf(day("2026-01-01"), day("2026-01-02")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-01-03"), maxCandidates = 4).map { it.occurrenceDate })
    }
    @Test fun `ancient monthly anchor also seeks directly near requested month`() {
        val plan = allDayPlan().copy(allDayStartDate = day("0001-01-31"), allDayEndDate = day("0001-01-31"), repeatRule = MONTHLY)
        assertEquals(listOf(day("2026-01-31")), PlanOccurrences.inWindow(plan, window("2026-01-01", "2026-02-01"), maxCandidates = 2).map { it.occurrenceDate })
    }
    @Test fun `single timed interval preserves both signed millisecond extremes`() {
        val first = Instant.ofEpochMilli(Long.MIN_VALUE)
        val last = Instant.ofEpochMilli(Long.MAX_VALUE)
        assertEquals(listOf(PlanOccurrenceTime.Timed(first, last)), PlanOccurrences.inWindow(timedPlan().copy(startAt = first, endAt = last), window("2026-01-01", "2026-01-02")).map { it.time })
    }
    @Test fun `extreme millisecond deadline still matches point without narrowing`() {
        val due = Instant.ofEpochMilli(Long.MAX_VALUE)
        assertEquals(listOf(PlanOccurrenceTime.DeadlineInstant(due)), PlanOccurrences.inWindow(timedPlan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = due), PlanWindow(due.minusSeconds(1), due.plusSeconds(1))).map { it.time })
    }
    @Test fun `expansion budget throws instead of silently truncating`() {
        assertFailsWith<PlanExpansionLimitException> { PlanOccurrences.inWindow(timedPlan().copy(repeatRule = DAILY), window("2026-01-01", "2026-01-10"), maxCandidates = 2) }
        assertFailsWith<IllegalArgumentException> { PlanOccurrences.inWindow(timedPlan(), window("2026-01-01", "2026-01-02"), maxCandidates = 0) }
    }
    @Test fun `all public calculators reject budgets above hard ceiling`() {
        val plan = timedPlan()
        val range = window("2026-01-01", "2026-01-02")
        assertFailsWith<IllegalArgumentException> { PlanOccurrences.inWindow(plan, range, maxCandidates = 10_001) }
        assertFailsWith<IllegalArgumentException> { PlanConflicts.find(plan, emptyList(), range, maxCandidates = 10_001) }
        assertFailsWith<IllegalArgumentException> { PlanReminderRules.next(plan, instant("2026-01-01T09:00:00Z"), maxCandidates = 10_001) }
    }
}
