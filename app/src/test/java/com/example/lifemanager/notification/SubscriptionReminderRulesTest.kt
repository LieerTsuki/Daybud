package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class SubscriptionReminderRulesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun `billing day triggers at local nine`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 0, Instant.parse("2026-10-01T00:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 10, 10), reminder.dueDate)
        assertEquals(Instant.parse("2026-10-10T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `before nine on billing day still schedules today`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 0, Instant.parse("2026-10-10T00:59:59Z"), shanghai))
        assertEquals(Instant.parse("2026-10-10T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `at nine moves to strictly future occurrence without immediately firing`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 0, Instant.parse("2026-10-10T01:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 11, 10), reminder.dueDate)
        assertEquals(Instant.parse("2026-11-10T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `past anchor seeks next cycle without scheduling historical reminders`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2020-01-31"), 0, Instant.parse("2026-10-31T02:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 11, 30), reminder.dueDate)
        assertEquals(Instant.parse("2026-11-30T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `month end recurrence returns to original anchor after February`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-01-31"), 0, Instant.parse("2026-02-28T02:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 3, 31), reminder.dueDate)
        assertEquals(Instant.parse("2026-03-31T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `weekly billing after nine advances to next week`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10", BillingCycle.WEEKLY), 0, Instant.parse("2026-10-10T02:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 10, 17), reminder.dueDate)
        assertEquals(Instant.parse("2026-10-17T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `quarterly billing preserves month end anchor`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-01-31", BillingCycle.QUARTERLY), 0, Instant.parse("2026-04-30T02:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2026, 7, 31), reminder.dueDate)
        assertEquals(Instant.parse("2026-07-31T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `yearly billing restores leap day anchor`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2024-02-29", BillingCycle.YEARLY), 0, Instant.parse("2027-02-28T02:00:00Z"), shanghai))
        assertEquals(LocalDate.of(2028, 2, 29), reminder.dueDate)
        assertEquals(Instant.parse("2028-02-29T01:00:00Z"), reminder.triggerAt)
    }

    @Test fun `inactive subscription has no reminder`() {
        assertNull(SubscriptionReminderRules.nextReminder(subscription("2026-10-10").copy(isActive = false), 0,
            Instant.parse("2026-10-01T00:00:00Z"), shanghai))
    }

    @Test fun `occurrence beyond cancellation date has no reminder`() {
        assertNull(SubscriptionReminderRules.nextReminder(subscription("2026-10-10").copy(cancelDate = LocalDate.of(2026, 10, 9)), 0,
            Instant.parse("2026-10-01T00:00:00Z"), shanghai))
    }

    @Test fun `retired and unsupported offsets have no new alarm`() {
        for (days in listOf(-1, 1, 2, 3, 7, 10)) {
            assertNull(SubscriptionReminderRules.nextReminder(subscription("2026-10-10"), days,
                Instant.parse("2026-10-01T00:00:00Z"), shanghai))
        }
    }

    @Test fun `timezone change keeps nine local while changing trigger instant`() {
        val value = subscription("2026-10-10")
        val now = Instant.parse("2026-10-01T00:00:00Z")
        assertEquals(Instant.parse("2026-10-10T01:00:00Z"),
            assertNotNull(SubscriptionReminderRules.nextReminder(value, 0, now, shanghai)).triggerAt)
        assertEquals(Instant.parse("2026-10-10T13:00:00Z"),
            assertNotNull(SubscriptionReminderRules.nextReminder(value, 0, now, ZoneId.of("America/New_York"))).triggerAt)
    }

    @Test fun `billing after daylight saving transition still uses nine local`() {
        val reminder = assertNotNull(SubscriptionReminderRules.nextReminder(subscription("2026-11-03"), 0,
            Instant.parse("2026-10-30T00:00:00Z"), ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-11-03T14:00:00Z"), reminder.triggerAt)
    }

    @Test fun `due date is accepted at nine and when delayed same day without selected offsets`() {
        for (now in listOf("2026-10-10T01:00:00Z", "2026-10-10T07:00:00Z")) {
            assertTrue(SubscriptionReminderRules.matchesCurrentReminder(subscription("2026-10-10"), emptySet(), 0,
                LocalDate.of(2026, 10, 10), Instant.parse(now), shanghai))
        }
    }

    @Test fun `deleted or cancelled subscription payload is rejected`() {
        for (value in listOf(null, subscription("2026-10-10").copy(isActive = false))) {
            assertFalse(SubscriptionReminderRules.matchesCurrentReminder(value, emptySet(), 0,
                LocalDate.of(2026, 10, 10), Instant.parse("2026-10-10T01:00:00Z"), shanghai))
        }
    }

    @Test fun `edited due date or billing cycle rejects stale payload`() {
        for (value in listOf(subscription("2026-10-11"), subscription("2026-09-11", BillingCycle.WEEKLY))) {
            assertFalse(SubscriptionReminderRules.matchesCurrentReminder(value, emptySet(), 0,
                LocalDate.of(2026, 10, 10), Instant.parse("2026-10-10T01:00:00Z"), shanghai))
        }
    }

    @Test fun `early delivery is rejected`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(subscription("2026-10-10"), emptySet(), 0,
            LocalDate.of(2026, 10, 10), Instant.parse("2026-10-10T00:59:59Z"), shanghai))
    }

    @Test fun `old cycle payload is rejected on later billing day`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(subscription("2026-10-10"), emptySet(), 0,
            LocalDate.of(2026, 10, 10), Instant.parse("2026-11-10T01:00:00Z"), shanghai))
    }

    private fun subscription(dueDate: String, cycle: BillingCycle = BillingCycle.MONTHLY) = Subscription(
        id = 42L, appName = "音乐", amountMinor = 1800L, billingCycle = cycle,
        nextBillingDate = LocalDate.parse(dueDate), startDate = LocalDate.parse(dueDate).minusMonths(1),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
}
