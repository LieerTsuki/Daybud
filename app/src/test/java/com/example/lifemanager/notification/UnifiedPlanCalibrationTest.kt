package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.plan.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [UnifiedAlarmService::class, UnifiedNotificationService::class])
class UnifiedPlanCalibrationTest {
    @Test fun `fresh pass schedules from actual target Room facts without business writes`() = fixture { f ->
        val id = f.save()
        f.reconciler().reconcile()
        assertEquals(id, f.token(id).planId)
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), alarmData(f))
        assertEquals(1, f.repo.observeAll().first().size)
    }
    @Test fun `ordinary calibration preserves still qualified delayed pending without past reinstall`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.clock.at = Instant.parse("2026-01-01T09:46:00Z")
        calibrate(f, id, false)
        assertEquals(PlanReminderState(token, false, true), f.store.get(id))
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), alarmData(f))
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(token) })
    }
    @Test fun `system reset never catches up past trigger and recurring series moves to future`() = fixture { f ->
        val id = f.save(repeating(f)); f.reconcile(id)
        f.clock.at = Instant.parse("2026-01-01T09:46:00Z")
        calibrate(f, id, true)
        assertEquals("lifemanager://plan-reminder/v1/1/0/20455/1767347100000", alarmData(f).single())
        assertEquals(0, f.notifications.activeNotifications.size)
    }
    @Test fun `future pending is reinstalled after platform reset`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.context.getSystemService(AlarmManager::class.java).cancel(assertNotNull(f.alarms.scheduledAlarms.single().operation))
        calibrate(f, id, true)
        assertEquals(token, f.token(id))
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), alarmData(f))
    }
    @Test fun `calibration retains decided card even after nonrepeating inventory row is gone`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(token) })
        assertEquals(PlanReminderState(token, true), f.store.get(id))
        f.store.forget(id) // emulate an older/lost technical row; system inventory still owns its card
        calibrate(f, id, false)
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), f.notifications.activeNotifications.map { it.tag })
    }
    @Test fun `reset keeps latest current card A while reinstalling pending B`() = fixture { f ->
        val id = f.save(repeating(f)); f.reconcile(id); val a = f.token(id)
        f.clock.at = a.triggerAt
        f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(a) }
        val b = f.token(id)
        calibrate(f, id, true)
        assertEquals(b, f.token(id))
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), f.notifications.activeNotifications.map { it.tag })
    }
    @Test fun `obsolete technical identity and orphan card are cancelled from system inventory`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        assertTrue(f.notifier.show(token, "旧卡片"))
        f.repo.delete(id, DataGeneration(0))
        f.reconciler().reconcile()
        assertNull(f.store.get(id)); assertTrue(f.alarms.scheduledAlarms.isEmpty())
        assertTrue(f.notifications.activeNotifications.isEmpty())
    }
    @Test fun `new generation replaces stale identity and removes old card`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        assertTrue(f.notifier.show(token, "旧卡片"))
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=1")
        f.reconciler().reconcile()
        assertEquals(DataGeneration(1), f.token(id).generation)
        assertTrue(f.notifications.activeNotifications.isEmpty())
    }
    @Test fun `read failure happens before any old alarm cleanup or fresh install`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        legacyAlarm(f, 99)
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=2")
        assertFails { f.reconciler().reconcile() }
        assertEquals(PlanReminderState(token, false, true), f.store.get(id))
        assertEquals(2, f.alarms.scheduledAlarms.size)
    }
    @Test fun `legacy cleanup failure prevents fresh unified install and retries current facts`() = fixture { f ->
        val id = f.save(); legacyAlarm(f, 99)
        UnifiedAlarmService.failLegacyCancel = true
        assertFails { f.reconciler().reconcile() }
        assertNull(f.store.get(id)); assertEquals(listOf("lifemanager://todo-reminder/99"), alarmData(f))
        UnifiedAlarmService.failLegacyCancel = false
        f.reconciler().reconcile()
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20454/1767260700000"), alarmData(f))
    }
    @Test fun `global off removes plan alarm and card without changing single item preference`() = fixture { f ->
        val id = f.save(); f.reconcile(id); f.notifier.show(f.token(id), "卡片")
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
        f.reconciler().reconcile()
        assertNull(f.store.get(id)); assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertTrue(f.notifications.activeNotifications.isEmpty())
        assertTrue(assertNotNull(f.repo.get(id)).reminderEnabled)
    }
    @Test fun `partial plan install failure still calibrates another plan and real subscription then reports`() = fixture { f ->
        f.clock.at = Instant.parse("2026-01-01T08:00:00Z")
        val first = f.save(); val second = f.save(f.plan("另一计划")); subscribe(f)
        UnifiedAlarmService.failPlanSet = setOf(first)
        assertFails { f.reconciler().reconcile() }
        assertEquals(second, f.token(second).planId)
        assertTrue(alarmData(f).contains("lifemanager://subscription-reminder/1/3/2026-01-04"))
        UnifiedAlarmService.failPlanSet = emptySet()
        f.reconciler().reconcile()
        assertEquals(3, f.alarms.scheduledAlarms.size)
        assertEquals(2, f.repo.observeAll().first().size)
        assertEquals(1, f.subscriptions.getSubscriptions().size)
    }
    @Test fun `subscription calibration reads v6 flag instead of resolving legacy settings entry point`() = fixture { f ->
        f.clock.at = Instant.parse("2026-01-01T08:00:00Z"); subscribe(f)
        f.subscriptionCalibration.reconcile(DataGeneration(0), false)
        assertEquals(listOf("lifemanager://subscription-reminder/1/3/2026-01-04"), alarmData(f))
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=0")
        f.subscriptionCalibration.reconcile(DataGeneration(0), false)
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `notification inventory owns only canonical tag and matching four Long extras`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.notifier.show(token, "合法卡片")
        f.notifications.createNotificationChannel(NotificationChannel("other", "other", NotificationManager.IMPORTANCE_DEFAULT))
        val foreign = Notification.Builder(f.context, "other").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        f.notifications.notify("foreign", 0, foreign)
        val malformed = Notification.Builder(f.context, PlanNotificationHelper.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(Bundle().apply { putInt(PlanReminderIntents.EXTRA_ID, 123) }).build()
        f.notifications.notify("lifemanager://plan-reminder/v1/123/0/20454/1767260700000", 0, malformed)
        assertEquals(listOf(token), f.notifier.tokens())
        f.notifier.clearStale(DataGeneration(1), emptySet())
        assertEquals(2, f.notifications.activeNotifications.size)
    }
    @Test fun `maintenance clears captured card A and alarm B even after technical rows are cleared`() = fixture { f ->
        val id = f.save(repeating(f)); f.reconcile(id); val a = f.token(id)
        f.clock.at = a.triggerAt; f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(a) }
        val b = f.token(id)
        val capture = ReminderIdentities(emptyList(), emptyList(), emptyList(), listOf(b), listOf(a))
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            f.generations.commit(session.generation) { f.db.openHelper.writableDatabase.execSQL("DELETE FROM plan_reminder_state") }
            effects(f).clearPrevious(capture)
        } }
        assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertTrue(f.notifications.activeNotifications.isEmpty())
    }
    @Test fun `maintenance exact token cleanup preserves same ID newer generation alarm card and row`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val old = f.token(id)
        val capture = ReminderIdentities(emptyList(), emptyList(), emptyList(), listOf(old), listOf(old))
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            f.generations.commit(session.generation) { f.db.openHelper.writableDatabase.execSQL("DELETE FROM plan_reminder_state") }
            PlanOperationCoordinator.run { f.scheduler.reconcile(id, DataGeneration(1)) }
            f.notifier.show(old, "旧卡片"); f.notifier.show(f.token(id), "新卡片")
            effects(f).clearPrevious(capture)
        } }
        assertEquals(DataGeneration(1), f.token(id).generation)
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/1/20454/1767260700000"), alarmData(f))
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/1/20454/1767260700000"), f.notifications.activeNotifications.map { it.tag })
    }
    @Test fun `maintenance platform failure leaves old inventory for captured token retry`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        val capture = ReminderIdentities(emptyList(), emptyList(), emptyList(), listOf(token), emptyList())
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            UnifiedAlarmService.failPlanCancel = setOf(id)
            assertFails { effects(f).clearPrevious(capture) }
            assertEquals(PlanReminderState(token, false), f.store.get(id))
            UnifiedAlarmService.failPlanCancel = emptySet()
            effects(f).clearPrevious(capture)
        } }
        assertNull(f.store.get(id)); assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `clock rollback and file reopen never rearm a previously decided one shot`() = fixture(onFile = true) { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(token) })
        f.notifier.cancelCaptured(token) // user dismissed the card; it cannot serve as a durable receipt
        f.reopen(); f.clock.at = Instant.parse("2026-01-01T09:00:00Z")
        f.reconciler().reconcile(true)
        assertTrue(f.alarms.scheduledAlarms.isEmpty(), "time rollback must not recreate the decided token")
        assertEquals(PlanReminderState(token, true), f.store.get(id))
    }
    @Test fun `authoritative snapshot holds plan lock until orphan decisions finish`() = fixture { f ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val writerAdmitted = CompletableDeferred<Unit>()
            val actual = f.sources
            val paused = object : PlanReminderSources by actual {
                override suspend fun configuration(): PlanReminderConfiguration {
                    val config = actual.configuration(); entered.complete(Unit); release.await(); return config
                }
            }
            val pass = async { f.reconciler(paused).reconcile() }
            withTimeout(5_000) { entered.await() }
            f.onGenerationRead = { writerAdmitted.complete(Unit) }
            val writer = async { f.cases.save(f.plan(), emptyList(), DataGeneration(0)) {} }
            try {
                withTimeout(5_000) { writerAdmitted.await() }; yield()
                assertFalse(writer.isCompleted, "writer must wait for the authoritative read/cleanup module lock")
                assertTrue(f.repo.observeAll().first().isEmpty())
            } finally { release.complete(Unit); f.onGenerationRead = null }
            withTimeout(5_000) { pass.await() }
            val saved = withTimeout(5_000) { writer.await() }
            assertEquals(PlanReminderUpdate.Applied, saved.reminders)
            assertEquals(saved.receipt.planId, f.token(saved.receipt.planId).planId)
            assertEquals(1, f.alarms.scheduledAlarms.size)
        }
    }
    @Test fun `committed maintenance failure retains device inventory for fresh post reopen calibration`() = fixture(onFile = true) { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        val capture = ReminderIdentities(emptyList(), emptyList(), emptyList(), listOf(token), emptyList())
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            f.generations.commit(session.generation) { f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET title='恢复后的合成记录' WHERE id=1") }
            UnifiedAlarmService.failPlanCancel = setOf(id)
            assertFails { effects(f).clearPrevious(capture) }
        } }
        f.reopen()
        assertEquals(DataGeneration(1), f.generations.current())
        assertEquals("恢复后的合成记录", f.repo.get(id)?.title)
        assertEquals(PlanReminderState(token, false), f.store.get(id))
        UnifiedAlarmService.failPlanCancel = emptySet()
        f.reconciler().reconcile(true) // only current Room; no replay of the precommit DTO
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/1/20454/1767260700000"), alarmData(f))
        assertEquals(DataGeneration(1), f.token(id).generation)
    }
    @Test fun `ordinary repair advances known failed installation rather than keeping a nonexistent alarm`() = fixture { f ->
        val id = f.save(repeating(f)); UnifiedAlarmService.failPlanSet = setOf(id)
        assertFails { f.reconcile(id) }
        UnifiedAlarmService.failPlanSet = emptySet()
        f.clock.at = Instant.parse("2026-01-01T09:46:00Z")
        f.reconciler().reconcile(false)
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20455/1767347100000"), alarmData(f))
    }
    @Test fun `consumed broadcast with NOT_SENT is repaired to future rather than falsely preserved pending`() = fixture { f ->
        val id = f.save(repeating(f)); f.reconcile(id); val token = f.token(id)
        f.context.getSystemService(AlarmManager::class.java).cancel(assertNotNull(f.alarms.scheduledAlarms.single().operation))
        f.clock.at = token.triggerAt; UnifiedNotificationService.rejectPlan = true
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(token) })
        UnifiedNotificationService.rejectPlan = false; shadowOf(f.notifications).setNotificationsEnabled(true)
        f.clock.at = Instant.parse("2026-01-01T09:46:00Z")
        f.reconciler().reconcile(false)
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20455/1767347100000"), alarmData(f))
    }
    @Test fun `subscription pass does not bypass strict legacy ownership cleanup`() = fixture { f ->
        f.notifications.createNotificationChannel(NotificationChannel(NotificationHelper.CHANNEL_ID, "legacy", NotificationManager.IMPORTANCE_DEFAULT))
        val foreign = Notification.Builder(f.context, NotificationHelper.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(Bundle().apply { putString(ReminderNotifications.MODULE, "unknown") }).build()
        f.notifications.notify(null, 4, foreign); f.notifications.notify("foreign", 5, foreign)
        f.reconciler().reconcile()
        assertEquals(2, f.notifications.activeNotifications.size)
        assertEquals(setOf(null, "foreign"), f.notifications.activeNotifications.map { it.tag }.toSet())
    }

    @Test fun `failed reinstall invalidates previously accepted alarm before ordinary late repair`() = fixture { f ->
        val id = f.save(repeating(f)); f.reconcile(id)
        UnifiedAlarmService.failPlanSet = setOf(id)
        assertFails { calibrate(f, id, false) }
        UnifiedAlarmService.failPlanSet = emptySet(); f.clock.at = Instant.parse("2026-01-01T09:46:00Z")
        f.reconciler().reconcile()
        assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20455/1767347100000"), alarmData(f))
    }
    @Test fun `matching early broadcast is consumed and explicitly requests future repair`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        f.context.getSystemService(AlarmManager::class.java).cancel(assertNotNull(f.alarms.scheduledAlarms.single().operation))
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(token) })
        f.reconciler().reconcile()
        assertEquals(token, f.token(id)); assertEquals(1, f.alarms.scheduledAlarms.size)
    }
    @Test fun `exhausted finite recurrence retains pending chronological barrier across rollback`() = fixture(onFile = true) { f ->
        val id = f.save(repeating(f).copy(repeatEndDate = LocalDate.parse("2026-01-02"))); f.reconcile(id)
        val a = f.token(id); f.clock.at = a.triggerAt
        f.coordinator.run(DataGeneration(0)) { f.delivery.deliverCurrent(a) }
        val b = f.token(id)
        f.clock.at = Instant.parse("2026-01-03T09:00:00Z"); f.reconciler().reconcile(true)
        f.notifier.cancelCaptured(a); f.reopen(); f.clock.at = Instant.parse("2026-01-01T09:00:00Z")
        f.reconciler().reconcile(true)
        assertEquals(b, f.token(id)); assertEquals(listOf("lifemanager://plan-reminder/v1/1/0/20455/1767347100000"), alarmData(f))
    }

    @Test fun `disabled reset and maintenance subscription cleanup preserve malformed canonical cards`() = fixture { f ->
        val id = subscribe(f)
        f.notifications.createNotificationChannel(NotificationChannel(SubscriptionNotificationHelper.CHANNEL_ID, "sub", NotificationManager.IMPORTANCE_DEFAULT))
        val malformed = Notification.Builder(f.context, SubscriptionNotificationHelper.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(Bundle().apply { putString(ReminderNotifications.MODULE, "subscription"); putInt(ReminderGeneration.EXTRA, 0) }).build()
        f.notifications.notify("subscription/$id/3", 0, malformed)
        f.reconciler().reconcile(true)
        assertEquals(1, f.notifications.activeNotifications.size, "reset cannot bypass ownership via cancelAll")
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=0")
        f.reconciler().reconcile()
        assertEquals(1, f.notifications.activeNotifications.size, "disable cannot bypass ownership via cancelAll")
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            effects(f).clearPrevious(ReminderIdentities(emptyList(), emptyList(), listOf(id)))
        } }
        assertEquals(1, f.notifications.activeNotifications.size, "maintenance cannot bypass ownership via cancelAll")
    }
    @Test fun `plan bulk cleanup uses same actual zero ID ownership predicate as inventory`() = fixture { f ->
        val id = f.save(); f.reconcile(id); val token = f.token(id)
        assertTrue(f.notifier.show(token, "owned"))
        val notification = f.notifications.activeNotifications.single().notification
        f.notifications.notify("lifemanager://plan-reminder/v1/1/0/20454/1767260700000", 9, notification)
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
        f.reconciler().reconcile()
        assertEquals(listOf(9), f.notifications.activeNotifications.map { it.id })
    }

    private fun effects(f: UnifiedReminderTestFixture) = UnifiedMaintenanceReminderEffects(f.context, f.scheduler, f.notifier,
        LegacyPlanReminderCleaner(f.context), SubscriptionReminderScheduler(f.context), f.reconciler(), {})
    private suspend fun calibrate(f: UnifiedReminderTestFixture, id: Long, reset: Boolean) =
        f.coordinator.run(DataGeneration(0)) { PlanOperationCoordinator.run { f.scheduler.calibrate(id, DataGeneration(0), reset) } }
    private fun repeating(f: UnifiedReminderTestFixture) = f.plan().copy(timeMode = PlanTimeMode.TIMED, dueAt = null,
        startAt = Instant.parse("2026-01-01T10:00:00Z"), endAt = Instant.parse("2026-01-01T11:00:00Z"), repeatRule = ScheduleRepeatRule.DAILY)
    private suspend fun subscribe(f: UnifiedReminderTestFixture) = f.subscriptions.saveSubscription(Subscription(
        appName = "合成订阅", amountMinor = 100, billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.parse("2026-01-04"),
        startDate = LocalDate.parse("2025-01-04"), createdAt = f.clock.instant(), updatedAt = f.clock.instant()), setOf(3))
    private fun legacyAlarm(f: UnifiedReminderTestFixture, id: Long) {
        rememberLegacyAlarm(f.context, id)
        val operation = PendingIntent.getBroadcast(f.context, ReminderKey.forTodo(id), Intent(f.context, TodoReminderReceiver::class.java)
            .setData("lifemanager://todo-reminder/$id".toUri()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        f.context.getSystemService(AlarmManager::class.java).setExact(AlarmManager.RTC_WAKEUP, 1767260700000, operation)
    }
    private fun alarmData(f: UnifiedReminderTestFixture) = f.alarms.scheduledAlarms.map { shadowOf(it.operation).savedIntent.dataString }.sortedBy { it }
    private fun fixture(onFile: Boolean = false, block: suspend (UnifiedReminderTestFixture) -> Unit): Unit = runBlocking {
        UnifiedAlarmService.failLegacyCancel = false; UnifiedAlarmService.failNoDataOnly = false; UnifiedAlarmService.cancelled = false
        UnifiedAlarmService.failPlanSet = emptySet(); UnifiedAlarmService.failPlanCancel = emptySet()
        UnifiedNotificationService.rejectPlan = false
        val f = UnifiedReminderTestFixture(onFile = onFile)
        try { block(f) } finally { f.close() }
    }
}
