package com.example.lifemanager.notification

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlanReminderProtocolTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 1, 1)
    private val trigger = Instant.parse("2026-01-01T09:45:00Z")
    private val token = PlanReminderToken(17, DataGeneration(4), day, trigger)
    private val plan = Plan(id = 17, title = "当前标题", timeZone = "UTC", timeMode = PlanTimeMode.DEADLINE,
        dueAt = Instant.parse("2026-01-01T10:00:00Z"), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test fun `alarm contract carries only canonical identity and strict Long metadata`() {
        val alarm = PlanReminderIntents.alarm(context, token)
        assertEquals("com.example.lifemanager.action.PLAN_REMINDER_V1", alarm.action)
        assertEquals("lifemanager://plan-reminder/v1/17/4/20454/1767260700000", alarm.dataString)
        assertEquals(17L, alarm.extras!!.get(PlanReminderIntents.EXTRA_ID))
        assertEquals(4L, alarm.extras!!.get(ReminderGeneration.EXTRA))
        assertEquals(20454L, alarm.extras!!.get(PlanReminderIntents.EXTRA_DAY))
        assertEquals(1767260700000L, alarm.extras!!.get(PlanReminderIntents.EXTRA_TRIGGER))
        assertEquals(token, PlanReminderIntents.read(context, alarm))
        assertEquals(4, alarm.extras!!.keySet().size)
    }
    @Test fun `forged missing wrong type and mismatched metadata cannot authorize protocol`() {
        val valid = PlanReminderIntents.alarm(context, token)
        for (key in listOf(PlanReminderIntents.EXTRA_ID, ReminderGeneration.EXTRA, PlanReminderIntents.EXTRA_DAY, PlanReminderIntents.EXTRA_TRIGGER)) {
            assertNull(PlanReminderIntents.read(context, Intent(valid).apply { removeExtra(key) }))
            assertNull(PlanReminderIntents.read(context, Intent(valid).putExtra(key, 17)))
            assertNull(PlanReminderIntents.read(context, Intent(valid).putExtra(key, "17")))
            assertNull(PlanReminderIntents.read(context, Intent(valid).putExtra(key, 5L)))
        }
        assertNull(PlanReminderIntents.read(context, Intent(valid).setAction("other")))
        assertNull(PlanReminderIntents.read(context, Intent(valid).setComponent(null)))
        assertNull(PlanReminderIntents.read(context, Intent(valid).setPackage("other.package")))
        assertNull(PlanReminderIntents.read(context, Intent(valid).setData(Uri.parse("lifemanager://plan-reminder/v1/017/4/20454/1767260700000"))))
        assertEquals(token, PlanReminderIntents.read(context, Intent(valid).putExtra("forged_title", "untrusted")))
    }
    @Test fun `deadline qualification includes trigger but expires at deadline`() {
        assertTrue(PlanReminderEligibility.matches(plan, emptyList(), token, trigger))
        assertTrue(PlanReminderEligibility.matches(plan, emptyList(), token, trigger.plusSeconds(899)))
        assertFalse(PlanReminderEligibility.matches(plan, emptyList(), token, trigger.minusMillis(1)))
        assertFalse(PlanReminderEligibility.matches(plan, emptyList(), token, plan.dueAt!!))
    }
    @Test fun `current mode completion switches and exact occurrence override old payload`() {
        for (changed in listOf(plan.copy(isCompleted = true), plan.copy(reminderEnabled = false),
            plan.copy(reminderMinutes = null), plan.copy(timeMode = PlanTimeMode.NONE),
            plan.copy(dueAt = plan.dueAt!!.plusSeconds(3600)))) {
            assertFalse(PlanReminderEligibility.matches(changed, emptyList(), token, trigger))
        }
        assertFalse(PlanReminderEligibility.matches(plan, emptyList(), token.copy(planId = 18), trigger))
        assertFalse(PlanReminderEligibility.matches(plan, emptyList(), token.copy(occurrenceDate = day.plusDays(1)), trigger))
        assertFalse(PlanReminderEligibility.matches(plan, emptyList(), token.copy(triggerAt = trigger.plusMillis(1)), trigger.plusMillis(1)))
    }
    @Test fun `date only preserves local day and no fake midnight deadline`() {
        val dateOnly = plan.copy(dueAt = null, dueDate = day, timeZone = "Asia/Shanghai")
        val dateToken = token.copy(triggerAt = Instant.parse("2026-01-01T00:45:00Z"))
        assertTrue(PlanReminderEligibility.matches(dateOnly, emptyList(), dateToken, dateToken.triggerAt))
        assertTrue(PlanReminderEligibility.matches(dateOnly, emptyList(), dateToken, Instant.parse("2026-01-01T15:59:59Z")))
        assertFalse(PlanReminderEligibility.matches(dateOnly, emptyList(), dateToken, Instant.parse("2026-01-01T16:00:00Z")))
    }
    @Test fun `recurrence original anchor cancellation and interval expiry are shared`() {
        val recurring = plan.copy(timeMode = PlanTimeMode.TIMED, startAt = plan.dueAt,
            endAt = plan.dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY, repeatInterval = 2)
        val later = token.copy(occurrenceDate = day.plusDays(2), triggerAt = Instant.parse("2026-01-03T09:45:00Z"))
        assertTrue(PlanReminderEligibility.matches(recurring, emptyList(), later, later.triggerAt))
        assertFalse(PlanReminderEligibility.matches(recurring, listOf(PlanException(17, later.occurrenceDate, true)), later, later.triggerAt))
        assertFalse(PlanReminderEligibility.matches(recurring, emptyList(), token.copy(occurrenceDate = day.plusDays(1),
            triggerAt = Instant.parse("2026-01-02T09:45:00Z")), Instant.parse("2026-01-02T09:45:00Z")))
        assertFalse(PlanReminderEligibility.matches(recurring, emptyList(), later, Instant.parse("2026-01-03T11:00:00Z")))
    }
    @Test fun `all day inclusive end DST and historical offset share scheduling rules`() {
        val allDay = plan.copy(timeMode = PlanTimeMode.ALL_DAY, allDayStartDate = LocalDate.of(2026, 3, 8),
            allDayEndDate = LocalDate.of(2026, 3, 9), timeZone = "America/New_York")
        val dstToken = token.copy(occurrenceDate = LocalDate.of(2026, 3, 8), triggerAt = Instant.parse("2026-03-08T12:45:00Z"))
        assertTrue(PlanReminderEligibility.matches(allDay, emptyList(), dstToken, dstToken.triggerAt))
        assertTrue(PlanReminderEligibility.matches(allDay, emptyList(), dstToken, Instant.parse("2026-03-10T03:59:59Z")))
        assertFalse(PlanReminderEligibility.matches(allDay, emptyList(), dstToken, Instant.parse("2026-03-10T04:00:00Z")))
        val offset = plan.copy(title = " ", timeZone = "+08:00", dueAt = null, dueDate = day)
        assertTrue(PlanReminderEligibility.matches(offset, emptyList(), token.copy(triggerAt = Instant.parse("2026-01-01T00:45:00Z")), Instant.parse("2026-01-01T01:00:00Z")))
    }
    @Test fun `corrupt current facts do not become silently ineligible`() {
        assertFailsWith<IllegalArgumentException> { PlanReminderEligibility.matches(plan.copy(timeZone = "bad/zone"), emptyList(), token, trigger) }
    }
}
