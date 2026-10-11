package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule.DAILY
import org.junit.Test
import kotlin.test.*

class PlanConflictsTest {
    private val range = window("2026-01-01", "2026-01-04")
    @Test fun `timed overlap conflicts but touching endpoint does not`() {
        val overlap = timedPlan(2).copy(startAt = instant("2026-01-01T10:30:00Z"), endAt = instant("2026-01-01T11:30:00Z"))
        val touching = timedPlan(3).copy(startAt = instant("2026-01-01T11:00:00Z"), endAt = instant("2026-01-01T12:00:00Z"))
        assertEquals(listOf(2L), PlanConflicts.find(timedPlan(), listOf(overlap, touching), range).map { it.id })
    }
    @Test fun `closed all day range blocks timed occurrence on last day`() {
        val other = timedPlan(2).copy(startAt = instant("2026-01-02T23:00:00Z"), endAt = instant("2026-01-03T00:00:00Z"))
        assertEquals(listOf(2L), PlanConflicts.find(allDayPlan(), listOf(other), range).map { it.id })
    }
    @Test fun `all day at next midnight does not conflict with previous range`() {
        val other = allDayPlan(2).copy(allDayStartDate = day("2026-01-03"), allDayEndDate = day("2026-01-03"))
        assertTrue(PlanConflicts.find(allDayPlan(), listOf(other), range).isEmpty())
    }
    @Test fun `different local dates can overlap across plan timezones`() {
        val tokyo = allDayPlan(zone = "Asia/Tokyo").copy(allDayEndDate = day("2026-01-01"))
        val la = timedPlan(2, "America/Los_Angeles").copy(startAt = instant("2025-12-31T16:00:00Z"), endAt = instant("2025-12-31T17:00:00Z"))
        assertEquals(listOf(2L), PlanConflicts.find(tokyo, listOf(la), window("2025-12-31", "2026-01-02")).map { it.id })
    }
    @Test fun `deadline and retained undated timing do not block intervals`() {
        val deadline = timedPlan(2).copy(timeMode = PlanTimeMode.DEADLINE, dueAt = timedPlan().startAt)
        val todo = timedPlan(3).copy(timeMode = PlanTimeMode.NONE, repeatRule = DAILY)
        assertTrue(PlanConflicts.find(timedPlan(), listOf(deadline, todo), range).isEmpty())
        assertTrue(PlanConflicts.find(deadline, listOf(timedPlan()), range).isEmpty())
    }
    @Test fun `completed candidates and existing series never block`() {
        assertTrue(PlanConflicts.find(timedPlan().copy(isCompleted = true), listOf(timedPlan(2)), range).isEmpty())
        assertTrue(PlanConflicts.find(timedPlan(), listOf(timedPlan(2).copy(repeatRule = DAILY, isCompleted = true)), range).isEmpty())
    }
    @Test fun `exclude persisted self but not two unsaved zero identities`() {
        assertTrue(PlanConflicts.find(timedPlan(), listOf(timedPlan()), range).isEmpty())
        assertEquals(1, PlanConflicts.find(timedPlan(0), listOf(timedPlan(0)), range).size)
    }
    @Test fun `conflicts include recurrence beyond initial anchor`() {
        val other = timedPlan(2).copy(startAt = instant("2026-01-03T10:00:00Z"), endAt = instant("2026-01-03T11:00:00Z"))
        assertEquals(listOf(2L), PlanConflicts.find(timedPlan().copy(repeatRule = DAILY), listOf(other), range).map { it.id })
    }
    @Test fun `cancelled occurrences on either side remove overlap`() {
        val candidate = timedPlan().copy(repeatRule = DAILY)
        val other = timedPlan(2).copy(repeatRule = DAILY)
        val oneDay = window("2026-01-01", "2026-01-02")
        for (id in listOf(1L, 2L)) assertTrue(PlanConflicts.find(candidate, listOf(other), oneDay, listOf(PlanException(id, day("2026-01-01"), true))).isEmpty())
    }
    @Test fun `bad source is reported rather than accepted as no conflicts`() {
        assertFailsWith<IllegalArgumentException> { PlanConflicts.find(timedPlan(), listOf(timedPlan(2).copy(timeZone = "bad")), range) }
    }
    @Test fun `conflict work budget is shared across all compared plans`() {
        assertFailsWith<PlanExpansionLimitException> { PlanConflicts.find(timedPlan(), listOf(timedPlan(2)), range, maxCandidates = 1) }
    }
}
