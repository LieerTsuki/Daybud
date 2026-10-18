package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubscriptionDueDayPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val due = LocalDate.of(2026, 10, 22)
    private val value = Subscription(appName = "音乐", amountMinor = 1800, billingCycle = BillingCycle.MONTHLY,
        nextBillingDate = due, startDate = LocalDate.of(2026, 9, 22), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test fun `due day alarm is at nine local and rolls to next cycle only after nine`() {
        val before = assertNotNull(SubscriptionReminderRules.nextReminder(value, 0, Instant.parse("2026-10-22T00:59:59Z"), zone))
        assertEquals(due, before.dueDate)
        assertEquals(Instant.parse("2026-10-22T01:00:00Z"), before.triggerAt)
        val after = assertNotNull(SubscriptionReminderRules.nextReminder(value, 0, Instant.parse("2026-10-22T01:00:00Z"), zone))
        assertEquals(LocalDate.of(2026, 11, 22), after.dueDate)
        assertEquals(Instant.parse("2026-11-22T01:00:00Z"), after.triggerAt)
    }

    @Test fun `due day delivery is automatic for empty and legacy preferences but old offsets cannot post`() {
        for (stored in listOf(emptySet(), setOf(1), setOf(1, 3, 7))) {
            assertTrue(SubscriptionReminderRules.matchesCurrentReminder(value, stored, 0, due,
                Instant.parse("2026-10-22T01:05:00Z"), zone))
            for (legacy in setOf(1, 3, 7)) {
                assertFalse(SubscriptionReminderRules.matchesCurrentReminder(value, stored, legacy, due,
                    due.minusDays(legacy.toLong()).atTime(9, 0).atZone(zone).toInstant(), zone))
            }
        }
    }

    @Test fun `notification says today and opens the same subscription with its data generation`() {
        val f = UnifiedReminderTestFixture()
        try {
            SubscriptionNotificationHelper.showReminder(f.context, value.copy(id = 42), 0, due, DataGeneration(9))
            val card = f.context.getSystemService(NotificationManager::class.java).activeNotifications.single()
            assertEquals("音乐 今天扣费", card.notification.extras.getCharSequence("android.text").toString())
            assertEquals("subscription/42/0", card.tag)
            val open = shadowOf(card.notification.contentIntent).savedIntent
            assertEquals(42L, open.getLongExtra(com.example.lifemanager.MainActivity.EXTRA_SUBSCRIPTION_ID, 0))
            assertEquals(DataGeneration(9), ReminderGeneration.read(open))
        } finally { f.close() }
    }

    @Test fun `missing explicit reminder offset never enters admission`() {
        val f = UnifiedReminderTestFixture()
        try {
            var entered = false
            val receiver = SubscriptionReminderReceiver(runnerProvider = { entered = true; error("malformed payload entered admission") })
            val intent = Intent().setData(ReminderKey.subscriptionData(42, 0, due).toUri())
                .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, 42L)
                .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, due.toString())
                .putExtra(ReminderGeneration.EXTRA, 0L)
            runCatching { receiver.onReceive(f.context, intent) }
            assertFalse(entered)
        } finally { f.close() }
    }

    @Test fun `startup recalibrates legacy and empty preferences without rewriting Room and removes old alarms`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            f.clock.at = Instant.parse("2026-10-18T00:00:00Z")
            f.subscriptionScheduler.zoneProvider = { zone }
            val legacyId = f.subscriptions.saveSubscription(value, setOf(1, 3, 7))
            val emptyId = f.subscriptions.saveSubscription(value.copy(appName = "新订阅"), emptySet())
            seedLegacyAlarms(f, legacyId)
            SubscriptionNotificationHelper.showReminder(f.context, value.copy(id = legacyId), 3, due, DataGeneration(0))
            assertEquals(1, f.notifications.activeNotifications.size)
            assertEquals(3, f.alarms.scheduledAlarms.size)
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertEquals(setOf("lifemanager://subscription-reminder/$legacyId/0/2026-10-22",
                "lifemanager://subscription-reminder/$emptyId/0/2026-10-22"),
                f.alarms.scheduledAlarms.map { shadowOf(assertNotNull(it.operation)).savedIntent.dataString }.toSet())
            assertTrue(f.alarms.scheduledAlarms.all { it.triggerAtTime == Instant.parse("2026-10-22T01:00:00Z").toEpochMilli() })
            assertEquals(setOf(1, 3, 7), f.subscriptions.getReminderDays(legacyId))
            assertEquals(emptySet(), f.subscriptions.getReminderDays(emptyId))
            assertEquals(listOf(legacyId, emptyId), f.subscriptions.getSubscriptions().map { it.id }.sorted())
            assertTrue(f.notifications.activeNotifications.isEmpty(), "Retired advance-reminder cards must disappear on upgrade")
        } finally { f.close() }
    }

    @Test fun `global off cancellation and deletion remove automatic reminders`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            f.clock.at = Instant.parse("2026-10-18T00:00:00Z")
            val id = f.subscriptions.saveSubscription(value, emptySet())
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertEquals(1, f.alarms.scheduledAlarms.size)
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=0")
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertTrue(f.alarms.scheduledAlarms.isEmpty())
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=1")
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertEquals(1, f.alarms.scheduledAlarms.size)
            f.subscriptions.saveSubscription(value.copy(id = id, isActive = false), emptySet())
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertTrue(f.alarms.scheduledAlarms.isEmpty())
            f.subscriptions.saveSubscription(value.copy(id = id), emptySet())
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            f.subscriptions.deleteSubscription(id)
            f.subscriptionCalibration.reconcile(DataGeneration(0), false)
            assertTrue(f.alarms.scheduledAlarms.isEmpty())
        } finally { f.close() }
    }

    private fun seedLegacyAlarms(f: UnifiedReminderTestFixture, id: Long) {
        val index = f.context.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)
        for (days in setOf(1, 3, 7)) {
            val operation = PendingIntent.getBroadcast(f.context, ReminderKey.forSubscription(id, days),
                Intent(f.context, SubscriptionReminderReceiver::class.java)
                    .setData(ReminderKey.subscriptionData(id, days, due).toUri()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            f.context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                due.minusDays(days.toLong()).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), operation)
            check(index.edit().putString("$id/$days", due.toString()).putLong("$id/$days/generation", 0).commit())
        }
    }
}
