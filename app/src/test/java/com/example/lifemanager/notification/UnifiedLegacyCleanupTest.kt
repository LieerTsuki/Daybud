package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [UnifiedAlarmService::class])
class UnifiedLegacyCleanupTest {
    @Test fun `configuration reads all four actual Room switch combinations`() = fixture { f ->
        for (plan in 0..1) for (subscription in 0..1) {
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=$plan,subscriptionReminders=$subscription")
            assertEquals(PlanReminderConfiguration(plan == 1, subscription == 1), f.sources.configuration())
            assertEquals(plan == 1, f.sources.enabled())
        }
    }
    @Test fun `bad or missing configuration is not default off`() {
        for (sql in listOf("DELETE FROM app_settings", "UPDATE app_settings SET id=2",
            "UPDATE app_settings SET planReminders=2", "UPDATE app_settings SET subscriptionReminders=1.5",
            "UPDATE app_settings SET planReminders='bad'",
            "INSERT INTO app_settings VALUES (2,'SYSTEM','YMD','CNY',1,1,0)")) fixture { f ->
            f.db.openHelper.writableDatabase.execSQL(sql)
            assertFails { f.sources.configuration() }
        }
    }
    @Test fun `legacy sources preserve signed full Long and separate equal numeric IDs`() = fixture { f ->
        val ids = (1..4).map { f.save(f.plan("计划$it")) }
        for ((source, old, index) in listOf(Triple("TODO", Long.MIN_VALUE, 0), Triple("TODO", Long.MAX_VALUE, 1),
            Triple("TODO", 1L, 2), Triple("SCHEDULE", 1L, 3))) {
            f.db.openHelper.writableDatabase.execSQL("INSERT INTO plan_legacy_refs VALUES ('$source',$old,${ids[index]})")
        }
        assertEquals(LegacyPlanReminderIds(setOf(Long.MIN_VALUE, Long.MAX_VALUE, 1), setOf(1)), f.sources.legacyIds())
    }
    @Test fun `invalid source or noninteger legacy identity fails instead of disappearing`() {
        for (values in listOf("'WRONG',1", "'TODO',x'AB'", "'SCHEDULE',1.5")) fixture { f ->
            val id = f.save()
            f.db.openHelper.writableDatabase.execSQL("INSERT INTO plan_legacy_refs VALUES ($values,$id)")
            assertFails { f.sources.legacyIds() }
        }
    }
    @Test fun `source reader uses IO without main thread Room exemption`() = runBlocking {
        val f = UnifiedReminderTestFixture(allowMain = false)
        try {
            withContext(Dispatchers.IO) { f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=0") }
            assertEquals(PlanReminderConfiguration(true, false), f.sources.configuration())
        } finally { f.close() }
    }
    @Test fun `mapped and cached legacy alarms are removed without colliding with new namespaces`() = fixture { f ->
        for (id in listOf(1L, 4294967296L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            oldAlarm(f, id, false, false); oldAlarm(f, id, false, true)
            ReminderAlarmInventory(f.context, "todo").remember(id)
        }
        oldAlarm(f, 1, true, false); oldAlarm(f, 1, true, true)
        currentAlarm(f, "lifemanager://plan-reminder/v1/17/0/20454/1767260700000", PlanReminderReceiver::class.java)
        currentAlarm(f, "lifemanager://subscription-reminder/17/3/2026-01-04", SubscriptionReminderReceiver::class.java)
        LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds(scheduleIds = setOf(1)))
        assertEquals(listOf("lifemanager://plan-reminder/v1/17/0/20454/1767260700000",
            "lifemanager://subscription-reminder/17/3/2026-01-04"), alarmData(f))
        assertTrue(ReminderAlarmInventory(f.context, "todo").ids().isEmpty())
    }
    @Test fun `occurrence hints discover a deleted old schedule without alarm cache or mapping`() = fixture { f ->
        oldAlarm(f, 987, true, true)
        ScheduleAlarmOccurrenceStore(f.context).remember(987, 1767260700000, DataGeneration(0))
        LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds())
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
        assertTrue(f.context.getSharedPreferences("schedule_alarm_occurrences", 0).all.isEmpty())
    }
    @Test fun `legacy notification cleanup keeps new subscription foreign and malformed ownership cards`() = fixture { f ->
        card(f, null, 1, NotificationHelper.CHANNEL_ID, "todo")
        card(f, null, 2, NotificationHelper.CHANNEL_ID, "schedule")
        card(f, null, 3, NotificationHelper.CHANNEL_ID, null)
        card(f, "foreign", 1, "other", null)
        card(f, "subscription/1/3", 0, SubscriptionNotificationHelper.CHANNEL_ID, "subscription")
        card(f, "new-plan", 0, PlanNotificationHelper.CHANNEL, null)
        card(f, null, 4, NotificationHelper.CHANNEL_ID, "unknown")
        LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds(setOf(1), setOf(2)))
        assertEquals(setOf("foreign", "subscription/1/3", "new-plan", null), f.notifications.activeNotifications.map { it.tag }.toSet())
        assertEquals(4, f.notifications.activeNotifications.size)
    }
    @Test fun `platform cancellation failure retains hints and a later pass retries actual alarms`() = fixture { f ->
        oldAlarm(f, 99, false, true); ReminderAlarmInventory(f.context, "todo").remember(99)
        UnifiedAlarmService.failLegacyCancel = true
        assertFails { LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds()) }
        assertEquals(setOf(99L), ReminderAlarmInventory(f.context, "todo").ids())
        assertEquals(listOf("lifemanager://todo-reminder/99"), alarmData(f))
        UnifiedAlarmService.failLegacyCancel = false
        LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds())
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `malformed relevant cache is not silent successful cleanup`() = fixture { f ->
        f.context.getSharedPreferences("reminder_alarm_ids", 0).edit().putBoolean("todo/not-long", true).commit()
        assertFails { LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds()) }
    }
    @Test fun `failure of old no data identity still attempts full URI and retains retry hint`() = fixture { f ->
        oldAlarm(f, 99, false, false); oldAlarm(f, 99, false, true)
        ReminderAlarmInventory(f.context, "todo").remember(99)
        UnifiedAlarmService.failNoDataOnly = true
        assertFails { LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds()) }
        assertEquals(listOf<String?>(null), alarmData(f))
        assertEquals(setOf(99L), ReminderAlarmInventory(f.context, "todo").ids())
        UnifiedAlarmService.failNoDataOnly = false
        LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds())
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `legacy cancellation propagates cancellation rather than treating it as ordinary retry`() = fixture { f ->
        oldAlarm(f, 99, false, true); ReminderAlarmInventory(f.context, "todo").remember(99)
        UnifiedAlarmService.cancelled = true
        assertFailsWith<CancellationException> { LegacyPlanReminderCleaner(f.context).clear(LegacyPlanReminderIds()) }
    }

    private fun fixture(block: suspend (UnifiedReminderTestFixture) -> Unit): Unit = runBlocking {
        UnifiedAlarmService.failLegacyCancel = false; UnifiedAlarmService.failNoDataOnly = false; UnifiedAlarmService.cancelled = false
        val f = UnifiedReminderTestFixture()
        try { block(f) } finally { f.close() }
    }
    private fun oldAlarm(f: UnifiedReminderTestFixture, id: Long, schedule: Boolean, data: Boolean) {
        val target = if (schedule) ScheduleReminderReceiver::class.java else TodoReminderReceiver::class.java
        val intent = Intent(f.context, target)
        if (data) intent.data = "lifemanager://${if (schedule) "schedule" else "todo"}-reminder/$id".toUri()
        val code = if (schedule) ReminderKey.forSchedule(id) else ReminderKey.forTodo(id)
        val operation = PendingIntent.getBroadcast(f.context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        f.context.getSystemService(AlarmManager::class.java).setExact(AlarmManager.RTC_WAKEUP, 1767260700000, operation)
    }
    private fun currentAlarm(f: UnifiedReminderTestFixture, uri: String, target: Class<*>) {
        val operation = PendingIntent.getBroadcast(f.context, 1, Intent(f.context, target).setData(uri.toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        f.context.getSystemService(AlarmManager::class.java).setExact(AlarmManager.RTC_WAKEUP, 1767260700000, operation)
    }
    private fun alarmData(f: UnifiedReminderTestFixture) = f.alarms.scheduledAlarms.map { shadowOf(it.operation).savedIntent.dataString }.sortedBy { it }
    private fun card(f: UnifiedReminderTestFixture, tag: String?, id: Int, channel: String, module: String?) {
        f.notifications.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_DEFAULT))
        val notification = Notification.Builder(f.context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(Bundle().apply { if (module != null) putString(ReminderNotifications.MODULE, module) }).build()
        f.notifications.notify(tag, id, notification)
    }
}
