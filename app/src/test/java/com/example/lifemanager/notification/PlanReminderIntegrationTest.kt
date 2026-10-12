package com.example.lifemanager.notification

import android.app.*
import android.content.*
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.core.content.ContextCompat
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.data.plan.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [PlanAlarmService::class, PlanNotificationService::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlanReminderIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val generation = DataGeneration(0)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private fun draft() = Plan(title = "Room 当前计划", timeZone = "UTC", timeMode = PlanTimeMode.DEADLINE,
        dueAt = Instant.parse("2026-01-01T10:00:00Z"), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test fun `save schedules literal current Room trigger with full immutable identity`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), emptyList(), generation) {}.receipt
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(1767260700000L, alarm.triggerAtTime)
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals("lifemanager://plan-reminder/v1/${receipt.planId}/0/20454/1767260700000", intent.dataString)
        val token = assertNotNull(PlanReminderIntents.read(context, intent))
        assertEquals(PlanReminderState(token, false, true), f.store.get(receipt.planId))
        assertTrue(shadowOf(alarm.operation).isImmutable)
        assertEquals(1, f.db.plans().allEntities().size)
    }
    @Test fun `conversion keeps identity while undated plan never creates alarm`() = withDatabase { f ->
        val receipt = f.cases.save(draft().copy(timeMode = PlanTimeMode.NONE), listOf("学习"), generation) {}.receipt
        assertTrue(alarms.scheduledAlarms.isEmpty())
        val row = assertNotNull(f.repo.get(receipt.planId))
        f.cases.save(row.copy(timeMode = PlanTimeMode.DEADLINE), row.tagNames, generation) {}
        assertEquals(receipt.planId, f.token().planId)
        assertEquals(listOf("学习"), f.repo.get(receipt.planId)!!.tagNames)
    }
    @Test fun `complete undo clears alarm and notification without reanchoring`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val original = f.token()
        assertTrue(f.notifier.show(original, "已显示"))
        f.cases.setCompleted(id, true, generation) {}
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertEquals(0, notifications.size())
        f.cases.setCompleted(id, false, generation) {}
        assertEquals(original, f.token())
    }
    @Test fun `editing time revokes exact old pending intent and all old notifications`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val old = f.token()
        f.notifier.show(old, "旧标题")
        val row = f.repo.get(id)!!
        f.cases.save(row.copy(dueAt = row.dueAt!!.plusSeconds(3600), title = "最新标题"), emptyList(), generation) {}
        assertEquals(1, alarms.scheduledAlarms.size); assertEquals(0, notifications.size())
        assertEquals(Instant.parse("2026-01-01T10:45:00Z"), f.token().triggerAt)
        f.clock.at = old.triggerAt
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(old))
        assertEquals(0, notifications.size())
    }
    @Test fun `delete clears system identities without resurrecting the row`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val token = f.token(); f.notifier.show(token, "旧卡片")
        f.cases.delete(id, generation) {}
        assertNull(f.repo.get(id)); assertNull(f.store.get(id))
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertEquals(0, notifications.size())
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
    }
    @Test fun `global and individual switches never rewrite saved configuration`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
        f.reconcile(id)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=1")
        val row = f.repo.get(id)!!
        f.cases.save(row.copy(reminderEnabled = false), emptyList(), generation) {}
        f.reconcile(id)
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertFalse(f.repo.get(id)!!.reminderEnabled)
        assertEquals(15, f.repo.get(id)!!.reminderMinutes)
        f.cases.save(row.copy(timeMode = PlanTimeMode.NONE), emptyList(), generation) {}
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `future recurrence exception and delivery share C1 exact occurrence`() = withDatabase { f ->
        val id = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}.receipt.planId
        f.cases.saveException(PlanException(id, LocalDate.of(2026, 1, 2), true), generation) {}
        val current = f.token(); f.clock.at = current.triggerAt
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.deliver(current))
        assertEquals(Instant.parse("2026-01-03T09:45:00Z"), f.token().triggerAt)
        assertEquals(1, notifications.size())
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(current))
        assertEquals(1, PlanNotificationService.posts)
    }
    @Test fun `delivery uses current Room title and emits collision safe click identity`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val token = f.token()
        f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET title='最新 Room 标题' WHERE id=?", arrayOf(id))
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.deliver(token))
        val notification = notifications.allNotifications.single()
        assertEquals("最新 Room 标题", notification.extras.getCharSequence("android.text"))
        assertEquals(PlanReminderIdentity.data(token), shadowOf(notification.contentIntent).savedIntent.dataString)
        assertEquals(listOf(PlanReminderState(token, true)), f.store.all())
    }
    @Test fun `request hash collisions do not replace other alarms or notifications`() = withDatabase { f ->
        val first = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        f.db.openHelper.writableDatabase.execSQL("INSERT INTO plans SELECT 4294967296,title,description,priority,color,location,participants,timeMode,dueAt,dueDate,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,isCompleted,completedAt,repeatRule,repeatInterval,repeatDaysOfWeek,repeatEndDate,reminderEnabled,reminderMinutes,parentId,sortOrder,createdAt,updatedAt FROM plans WHERE id=?", arrayOf(first))
        f.reconcile(4294967296L)
        assertEquals(2, alarms.scheduledAlarms.size)
        val tokens = alarms.scheduledAlarms.map { PlanReminderIntents.read(context, shadowOf(it.operation).savedIntent)!! }
        assertEquals(setOf(1L, 4294967296L), tokens.map { it.planId }.toSet())
        assertEquals(1, tokens.map(PlanReminderIdentity::requestCode).toSet().size)
        for (token in tokens) f.notifier.show(token, "不同计划")
        assertEquals(2, notifications.size())
        f.cases.setCompleted(first, true, generation) {}
        assertEquals(1, alarms.scheduledAlarms.size); assertEquals(1, notifications.size())
    }
    @Test fun `current Room exceptions completion disabled and expiry reject old pending`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val token = f.token()
        f.clock.at = token.triggerAt.minusMillis(1)
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.deliver(token)); assertNotNull(f.store.get(id))
        f.reconcile(id)
        f.clock.at = draft().dueAt!!
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token)); assertEquals(0, notifications.size())
    }
    @Test fun `cancelled repeating occurrence never trusts persisted pending token`() = withDatabase { f ->
        val id = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}.receipt.planId
        val token = f.token()
        f.repo.saveException(PlanException(id, token.occurrenceDate, true), generation)
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
        assertEquals(0, notifications.size())
        assertEquals(Instant.parse("2026-01-02T09:45:00Z"), f.token().triggerAt)
    }
    @Test fun `Room mark matches full identity and pending state only`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token()
        assertFalse(f.store.markDelivered(token.copy(triggerAt = token.triggerAt.plusMillis(1))))
        assertTrue(f.store.markDelivered(token)); assertFalse(f.store.markDelivered(token))
        assertEquals(PlanReminderState(token, true), f.store.all().single())
    }
    @Test fun `corrupt inventory is failure rather than missing or authorized`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        f.db.openHelper.writableDatabase.execSQL("UPDATE plan_reminder_state SET delivered=3")
        assertFailsWith<IllegalStateException> { f.store.get(id) }
        f.db.openHelper.writableDatabase.execSQL("UPDATE plan_reminder_state SET delivered=0,generation='not-a-number'")
        assertFailsWith<IllegalStateException> { f.store.all() }
    }
    @Test fun `inventory survives real database reopen and duplicate never posts again`() = withDatabase(onFile = true) { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token()
        f.reopen(); assertEquals(PlanReminderState(token, false, true), f.store.get(token.planId))
        f.clock.at = token.triggerAt
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.deliver(token))
        f.reopen()
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
        assertEquals(1, PlanNotificationService.posts); assertEquals(1, notifications.size())
    }
    @Test fun `advanced inventory reopen completion locates prior notification and next alarm`() = withDatabase(onFile = true) { f ->
        val id = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}.receipt.planId
        val first = f.token(); f.clock.at = first.triggerAt; f.deliver(first)
        assertEquals(Instant.parse("2026-01-02T09:45:00Z"), f.token().triggerAt)
        f.reopen(); f.cases.setCompleted(id, true, generation) {}
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertEquals(0, notifications.size())
    }
    @Test fun `advanced inventory reopen deletion still clears prior notification`() = withDatabase(onFile = true) { f ->
        val id = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}.receipt.planId
        val first = f.token(); f.clock.at = first.triggerAt; f.deliver(first)
        f.reopen(); f.cases.delete(id, generation) {}
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertEquals(0, notifications.size()); assertNull(f.repo.get(id))
    }
    @Test fun `alarm failure is saved but failed and retry cleans without reinserting`() = withDatabase { f ->
        PlanAlarmService.fail = true
        val result = f.cases.save(draft(), emptyList(), generation) {}
        assertIs<PlanReminderUpdate.Failed>(result.reminders)
        assertNotNull(f.store.get(result.receipt.planId)); assertEquals(1, f.db.plans().allEntities().size)
        PlanAlarmService.fail = false
        assertEquals(PlanReminderUpdate.Applied, f.cases.retryReminders(result.receipt).reminders)
        assertEquals(1, alarms.scheduledAlarms.size); assertEquals(1, f.db.plans().allEntities().size)
    }
    @Test fun `notification failure records consumed arrival and duplicate cannot replay payload`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        PlanNotificationService.fail = true
        assertFailsWith<IllegalStateException> { f.deliver(token) }
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
        PlanNotificationService.fail = false
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
        f.reconcile(token.planId)
        assertEquals(0, notifications.size()); assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `decision transaction failure retains consumed identity without replaying duplicate`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_mark BEFORE UPDATE ON plan_reminder_state WHEN NEW.delivered=1 BEGIN SELECT RAISE(ABORT,'synthetic mark failure'); END")
        assertFailsWith<android.database.sqlite.SQLiteException> { f.deliver(token) }
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(1, notifications.size())
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mark")
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
        assertEquals(1, notifications.size()); assertEquals(1, PlanNotificationService.posts)
    }
    @Test fun `advance inventory failure keeps decided token and just shown notification`() = withDatabase { f ->
        f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advance BEFORE INSERT ON plan_reminder_state BEGIN SELECT RAISE(ABORT,'synthetic advance failure'); END")
        assertFailsWith<android.database.sqlite.SQLiteException> { f.deliver(token) }
        assertEquals(PlanReminderState(token, true), f.store.get(token.planId)); assertEquals(1, notifications.size())
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token)); assertEquals(1, PlanNotificationService.posts)
    }
    @Test fun `corrupt current facts do not clear known identity or emit notification`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET timeZone='bad/zone'")
        assertFailsWith<Exception> { f.deliver(token) }
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
    }
    @Test fun `exact permission revocation falls back to real inexact alarm`() = withDatabase { f ->
        PlanAlarmService.denyExact = true
        f.cases.save(draft(), emptyList(), generation) {}
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(1767260700000L, alarms.scheduledAlarms.single().triggerAtTime)
        assertEquals(1, PlanAlarmService.exactAttempts)
    }

    @Test fun `delivery CAS miss never advances or reports success`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_mark BEFORE UPDATE ON plan_reminder_state WHEN NEW.delivered=1 BEGIN SELECT RAISE(IGNORE); END")
        assertFailsWith<IllegalStateException> { f.deliver(token) }
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(1, notifications.size())
    }
    @Test fun `notify permission race returns not sent without consuming pending decision`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt; PlanNotificationService.deny = true
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.deliver(token))
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
    }
    @Test fun `advance alarm service failure retains new cleanable identity and shown notification`() = withDatabase { f ->
        f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt; PlanAlarmService.fail = true
        assertFailsWith<IllegalStateException> { f.deliver(token) }
        assertEquals(Instant.parse("2026-01-02T09:45:00Z"), f.store.get(token.planId)!!.token.triggerAt)
        assertEquals(1, notifications.size()); assertEquals(1, PlanNotificationService.posts)
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token))
        PlanAlarmService.fail = false; f.reconcile(token.planId)
        assertEquals(1, alarms.scheduledAlarms.size)
    }
    @Test fun `receiver rejects stale generation and finishes without duplicate payload replay`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) { f.generations.commit(generation) {} } }
            awaitReceiver(beginReceiver(receiver(f), PlanReminderIntents.alarm(context, token)))
            assertEquals(0, notifications.size()); assertEquals(0, f.retries)
        }
    }
    @Test fun `receiver performs real delivery once and finishes both broadcasts`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            repeat(2) { awaitReceiver(beginReceiver(receiver(f), PlanReminderIntents.alarm(context, token).putExtra("forged_title", "不可信标题"))) }
            assertEquals(1, notifications.size()); assertEquals(1, PlanNotificationService.posts)
            assertEquals("Room 当前计划", notifications.allNotifications.single().extras.getCharSequence("android.text"))
        }
    }
    @Test fun `receiver maintenance busy queues fresh calibration only and finishes`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            f.coordinator.withSession {
                awaitReceiver(beginReceiver(receiver(f), PlanReminderIntents.alarm(context, token)))
                assertEquals(1, f.retries); assertEquals(0, notifications.size())
            }
            assertEquals(0, notifications.size()); assertEquals(PlanReminderState(token, false, true), f.store.get(token.planId))
        }
    }
    @Test fun `receiver ordinary read failure requests fresh calibration and does not consume`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET timeZone='bad/zone'")
            awaitReceiver(beginReceiver(receiver(f), PlanReminderIntents.alarm(context, token)))
            assertEquals(1, f.retries); assertEquals(0, notifications.size())
            assertEquals(PlanReminderState(token, false), f.store.get(token.planId))
        }
    }
    @Test fun `receiver not sent requests calibration without a second mutation`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt; PlanNotificationService.deny = true
            awaitReceiver(beginReceiver(receiver(f), PlanReminderIntents.alarm(context, token)))
            assertEquals(1, f.retries); assertEquals(1, f.db.plans().allEntities().size)
            assertEquals(PlanReminderState(token, false), f.store.get(token.planId))
        }
    }
    @Test fun `receiver malformed metadata is rejected before dependency access`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            val target = PlanReminderReceiver { error("Malformed protocol must never access dependencies") }
            awaitReceiver(beginReceiver(target, PlanReminderIntents.alarm(context, token).putExtra(PlanReminderIntents.EXTRA_ID, token.planId.toInt())))
            assertEquals(0, notifications.size()); assertEquals(PlanReminderState(token, false, true), f.store.get(token.planId))
        }
    }
    @Test fun `receiver provider failure finishes acquired async result`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            var calls = 0
            val target = PlanReminderReceiver { calls++; error("synthetic dependency failure") }
            awaitReceiver(beginReceiver(target, PlanReminderIntents.alarm(context, f.token())))
            assertEquals(1, calls); assertEquals(0, notifications.size())
        }
    }

    @Test fun `cancelled delivery does not consume and releases both permits`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        val entered = CompletableDeferred<Unit>()
        val repository = object : PlanRepository by f.repo {
            override suspend fun get(id: Long): Plan? {
                f.repo.get(id); entered.complete(Unit); awaitCancellation()
            }
        }
        coroutineScope {
            val job = launch { f.coordinator.run(generation) { f.deliveryFor(repository).deliverCurrent(token) } }
            withTimeout(5_000) { entered.await() }; job.cancelAndJoin()
        }
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
        f.cases.setCompleted(token.planId, true, generation) {}
        f.coordinator.withSession { assertEquals(MaintenanceState.READY, f.coordinator.state.value) }
        assertTrue(f.repo.get(token.planId)!!.isCompleted); assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `receiver timeout finishes without consuming pending identity`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            val entered = CompletableDeferred<Unit>()
            val repository = object : PlanRepository by f.repo {
                override suspend fun get(id: Long): Plan? {
                    f.repo.get(id); entered.complete(Unit); awaitCancellation()
                }
            }
            val pending = beginReceiver(receiver(f, f.deliveryFor(repository)), PlanReminderIntents.alarm(context, token))
            awaitUntil { entered.isCompleted }
            advanceTimeBy(8_001); awaitReceiver(pending)
            assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
            assertEquals(1, f.retries); f.coordinator.withSession {}
        }
    }
    @Test fun `delivery and completion share module lock and maintenance drains both`() = withDatabase { f ->
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        val token = f.token(); f.clock.at = token.triggerAt
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writerAdmitted = CompletableDeferred<Unit>()
        val repository = object : PlanRepository by f.repo {
            override suspend fun get(id: Long): Plan? = f.repo.get(id).also { entered.complete(Unit); release.await() }
        }
        coroutineScope {
            val delivery = async { f.coordinator.run(generation) { f.deliveryFor(repository).deliverCurrent(token) } }
            withTimeout(5_000) { entered.await() }
            f.onGenerationRead = { writerAdmitted.complete(Unit) }
            val completing = async { f.cases.setCompleted(id, true, generation) {} }
            withTimeout(5_000) { writerAdmitted.await() }; f.onGenerationRead = null
            val maintenance = async { f.coordinator.withSession { assertTrue(f.repo.get(id)!!.isCompleted); assertEquals(0, notifications.size()) } }
            withTimeout(5_000) { f.coordinator.state.first { it == MaintenanceState.DRAINING } }
            assertFalse(f.repo.get(id)!!.isCompleted); assertEquals(0, notifications.size()); assertFalse(completing.isCompleted)
            release.complete(Unit)
            assertEquals(PlanDeliveryOutcome.DELIVERED, withTimeout(5_000) { delivery.await() })
            withTimeout(5_000) { completing.await(); maintenance.await() }
        }
        assertEquals(1, PlanNotificationService.posts); assertEquals(0, notifications.size()); assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `final Room switch reread prevents posting after preflight`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt
        var reads = 0
        val preferences = PlanReminderPreferences {
            f.preferences.enabled().also {
                reads++
                if (reads == 1) f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
            }
        }
        val delivery = PlanReminderDelivery(f.repo, f.generations, preferences, f.store, f.notifier, f.scheduler, f.clock)
        assertEquals(PlanDeliveryOutcome.IGNORED, f.coordinator.run(generation) { delivery.deliverCurrent(token) })
        assertEquals(0, notifications.size()); assertEquals(0, PlanNotificationService.posts)
        assertNull(f.store.get(token.planId)); assertTrue(alarms.scheduledAlarms.isEmpty()); assertTrue(f.repo.get(token.planId)!!.reminderEnabled)
    }
    @Test fun `next recurrence replaces prior card without removing latest delivery`() = withDatabase { f ->
        f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}
        repeat(2) { val token = f.token(); f.clock.at = token.triggerAt; assertEquals(PlanDeliveryOutcome.DELIVERED, f.deliver(token)) }
        assertEquals(2, PlanNotificationService.posts); assertEquals(1, notifications.size())
        assertEquals("lifemanager://plan-reminder/v1/1/0/20455/1767347100000", context.getSystemService(NotificationManager::class.java).activeNotifications.single().tag)
        assertEquals(Instant.parse("2026-01-03T09:45:00Z"), f.token().triggerAt)
    }
    @Test @Config(sdk = [31]) fun `API31 denied exact permission schedules future inexact alarm`() = withDatabase { f ->
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        f.cases.save(draft(), emptyList(), generation) {}
        assertEquals(0, PlanAlarmService.exactAttempts); assertEquals(1767260700000L, alarms.scheduledAlarms.single().triggerAtTime)
        assertNotNull(f.store.get(1)); assertEquals(1, f.db.plans().allEntities().size)
    }
    @Test @Config(sdk = [33]) fun `API33 notification permission gates alarm without losing business settings`() = withDatabase { f ->
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertTrue(f.repo.get(id)!!.reminderEnabled)
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        f.reconcile(id)
        val token = f.token(); f.clock.at = token.triggerAt
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token)); assertEquals(0, notifications.size())
        assertTrue(f.repo.get(id)!!.reminderEnabled); assertNull(f.store.get(id))
    }
    @Test fun `disabled channel prevents scheduling and notification`() = withDatabase { f ->
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PlanNotificationHelper.CHANNEL, "合成禁用频道", NotificationManager.IMPORTANCE_NONE))
        val id = f.cases.save(draft(), emptyList(), generation) {}.receipt.planId
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertTrue(f.repo.get(id)!!.reminderEnabled)
        val token = PlanReminderToken(id, generation, LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T09:45:00Z"))
        assertFalse(f.notifier.show(token, "不会发送")); assertEquals(0, notifications.size())
    }
    @Test fun `cleanup ignores unrelated channel malformed tag and wrong typed metadata`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.notifier.show(token, "合法卡片")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("unrelated", "其它", NotificationManager.IMPORTANCE_DEFAULT))
        val valid = notifications.allNotifications.single()
        manager.notify("unrelated-card", 7, Notification.Builder(context, "unrelated").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        manager.notify("not-canonical", 8, valid)
        val forged = Notification.Builder(context, PlanNotificationHelper.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(PlanReminderIntents.alarm(context, token).putExtra(PlanReminderIntents.EXTRA_ID, token.planId.toInt()).extras).build()
        manager.notify(PlanReminderIdentity.data(token), 9, forged)
        f.cases.setCompleted(token.planId, true, generation) {}
        assertEquals(setOf(7, 8, 9), manager.activeNotifications.map { it.id }.toSet())
    }
    @Test fun `receiver absent application dependencies safely finishes`() = runTest {
        withDatabase { f ->
            f.cases.save(draft(), emptyList(), generation) {}
            val token = f.token(); f.clock.at = token.triggerAt
            awaitReceiver(beginReceiver(PlanReminderReceiver(), PlanReminderIntents.alarm(context, token)))
            assertEquals(0, notifications.size()); assertEquals(PlanReminderState(token, false, true), f.store.get(token.planId))
        }
    }
    @Test fun `elapsed candidate after Room inventory write fails without catchup and retry uses next future`() = withDatabase { f ->
        var crossTrigger = true
        val store = object : PlanReminderStore by f.store {
            override suspend fun remember(token: PlanReminderToken) {
                f.store.remember(token)
                if (crossTrigger) { crossTrigger = false; f.clock.at = token.triggerAt.plusSeconds(1) }
            }
        }
        val scheduler = PlanReminderScheduler(context, f.repo, f.generations, f.preferences, store, f.notifier, f.clock)
        val cases = PlanUseCases(f.repo, f.coordinator, scheduler)
        val saved = cases.save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}
        assertIs<PlanReminderUpdate.Failed>(saved.reminders)
        assertTrue(alarms.scheduledAlarms.isEmpty()); assertEquals(1, f.db.plans().allEntities().size)
        assertEquals(Instant.parse("2026-01-01T09:45:00Z"), f.store.get(saved.receipt.planId)!!.token.triggerAt)
        assertEquals(PlanReminderUpdate.Applied, cases.retryReminders(saved.receipt).reminders)
        assertEquals(Instant.parse("2026-01-02T09:45:00Z"), f.token().triggerAt)
    }
    @Test fun `scheduler samples current time after suspending exception read`() = withDatabase { f ->
        val repository = object : PlanRepository by f.repo {
            override suspend fun getExceptions(id: Long) = f.repo.getExceptions(id).also { f.clock.at = Instant.parse("2026-01-01T09:50:00Z") }
        }
        val scheduler = PlanReminderScheduler(context, repository, f.generations, f.preferences, f.store, f.notifier, f.clock)
        PlanUseCases(f.repo, f.coordinator, scheduler).save(draft().copy(timeMode = PlanTimeMode.TIMED, startAt = draft().dueAt,
            endAt = draft().dueAt!!.plusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY), emptyList(), generation) {}
        assertEquals(Instant.parse("2026-01-02T09:45:00Z"), f.token().triggerAt)
    }
    @Test fun `silent permission blocking during notify leaves pending and reports not sent`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = token.triggerAt; PlanNotificationService.silentBlock = true
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.deliver(token))
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId)); assertEquals(0, notifications.size())
        PlanNotificationService.silentBlock = false; notifications.setNotificationsEnabled(true)
        assertEquals(PlanDeliveryOutcome.IGNORED, f.deliver(token)); assertEquals(0, notifications.size())
    }
    @Test fun `already installed future alarm may arrive late before occurrence expiry`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token(); f.clock.at = Instant.parse("2026-01-01T09:50:00Z")
        assertEquals(PlanDeliveryOutcome.DELIVERED, f.deliver(token)); assertEquals(1, notifications.size())
    }
    @Test fun `inventory raw cursor reads use IO without allowing main thread queries`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PlanReminderTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).build()
        try {
            val store = RoomPlanReminderStore(db.reminders())
            val token = PlanReminderToken(17, DataGeneration(4), LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T09:45:00Z"))
            store.remember(token)
            assertEquals(PlanReminderState(token, false), store.get(17))
            assertEquals(listOf(PlanReminderState(token, false)), store.all())
        } finally { db.close() }
    }
    @Test fun `observable blocking after posting cancels exact own tag and keeps other card`() = withDatabase { f ->
        f.cases.save(draft(), emptyList(), generation) {}
        val token = f.token()
        val otherId = f.cases.save(draft().copy(title = "另一条实际计划"), emptyList(), generation) {}.receipt.planId
        val other = token.copy(planId = otherId); f.notifier.show(other, "另一张卡片")
        f.clock.at = token.triggerAt; PlanNotificationService.blockAfterPost = true
        assertEquals(PlanDeliveryOutcome.NOT_SENT, f.deliver(token))
        assertEquals(PlanReminderState(token, false), f.store.get(token.planId))
        val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals("lifemanager://plan-reminder/v1/2/0/20454/1767260700000", remaining.tag)
        assertEquals(0, remaining.id); assertEquals("另一张卡片", remaining.notification.extras.getCharSequence("android.text"))
    }

    private inner class Fixture(val name: String?) {
        var db: PlanReminderTestDatabase = open()
        val clock = MutableClock()
        var onGenerationRead: (() -> Unit)? = null
        val generations = object : DataGenerationRepository {
            override fun observe() = flow { emit(current()) }
            override suspend fun current(): DataGeneration = db.withTransaction { readInteger("app_maintenance", "generation").let(::DataGeneration) }.also { onGenerationRead?.invoke() }
            override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration = db.withTransaction {
                if (current() != expected) throw StaleGenerationException()
                mutation(); check(current() == expected)
                val next = DataGeneration(Math.addExact(expected.value, 1))
                db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1", arrayOf(next.value)); next
            }
        }
        val preferences = PlanReminderPreferences { db.withTransaction { val value = readInteger("app_settings", "planReminders"); check(value in 0..1); value == 1L } }
        val coordinator = MaintenanceCoordinator(generations)
        var retries = 0
        val repo get() = PlanRepositoryImpl(db, db.plans(), clock)
        val store get() = RoomPlanReminderStore(db.reminders())
        val notifier = PlanNotificationHelper(context)
        val scheduler get() = PlanReminderScheduler(context, repo, generations, preferences, store, notifier, clock)
        val delivery get() = PlanReminderDelivery(repo, generations, preferences, store, notifier, scheduler, clock)
        fun deliveryFor(repository: PlanRepository) = PlanReminderDelivery(repository, generations, preferences, store, notifier, scheduler, clock)
        val cases get() = PlanUseCases(repo, coordinator, scheduler)
        suspend fun reconcile(id: Long) = coordinator.run(generation) { PlanOperationCoordinator.run { scheduler.reconcile(id, generation) } }
        suspend fun deliver(token: PlanReminderToken) = coordinator.run(token.generation) { delivery.deliverCurrent(token) }
        suspend fun token(): PlanReminderToken = assertNotNull(PlanReminderIntents.read(context, shadowOf(alarms.scheduledAlarms.single().operation).savedIntent))
        private fun readInteger(table: String, column: String): Long = db.metadata().read(SimpleSQLiteQuery("SELECT $column FROM $table WHERE id=1")).use {
            check(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER)
            val value = it.getLong(0); check(!it.moveToNext()); value
        }
        private fun open(): PlanReminderTestDatabase {
            if (name != null) {
                val file = context.getDatabasePath(name)
                val directory = file.parentFile!!
                check(directory.isDirectory || directory.mkdirs())
            }
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, PlanReminderTestDatabase::class.java)
                else Room.databaseBuilder(context, PlanReminderTestDatabase::class.java, name)
            return builder.addCallback(PlanV6Schema.INITIALIZE).allowMainThreadQueries().build()
        }
        fun reopen() { check(name != null); db.close(); db = open() }
    }
    private class MutableClock : Clock() {
        var at: Instant = Instant.parse("2026-01-01T09:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
        override fun instant(): Instant = at
    }
    private fun withDatabase(onFile: Boolean = false, block: suspend (Fixture) -> Unit): Unit = runBlocking {
        PlanAlarmService.fail = false; PlanAlarmService.denyExact = false; PlanAlarmService.exactAttempts = 0
        PlanNotificationService.fail = false; PlanNotificationService.deny = false; PlanNotificationService.silentBlock = false; PlanNotificationService.blockAfterPost = false; PlanNotificationService.posts = 0
        // Native SQLite also creates -wal/-shm files: keep Windows temporary paths short.
        val name = if (onFile) "p${System.nanoTime()}.db" else null
        val fixture = Fixture(name)
        try { block(fixture) } finally {
            fixture.db.close()
            // Only this test-created unique database; never production or a user's database.
            if (name != null) context.deleteDatabase(name)
        }
    }

    private fun TestScope.receiver(f: Fixture, delivery: PlanReminderDelivery = f.delivery): PlanReminderReceiver {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PlanReminderReceiver { object : PlanReminderReceiver.Dependencies {
            override val runner = ReminderBroadcastRunner(f.coordinator, dispatcher)
            override val delivery = delivery
            override fun requestReconciliation() { f.retries++ }
        } }
    }
    private fun TestScope.beginReceiver(receiver: PlanReminderReceiver, intent: Intent): ShadowBroadcastPendingResult {
        // The merged manifest declares this signature permission; Robolectric does not
        // grant it at synthetic install like Android does. No POST_NOTIFICATIONS grant here.
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(PlanReminderIntents.ACTION).apply { addDataScheme("lifemanager") }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            return shadowOf(assertNotNull(shadowOf(receiver).originalPendingResult))
        } finally { context.unregisterReceiver(receiver) }
    }
    private fun TestScope.awaitReceiver(pending: ShadowBroadcastPendingResult) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!pending.future.isDone && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue(pending.future.isDone, "Async result must finish before closing the synthetic database")
    }
    private fun TestScope.awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        assertTrue(condition(), "Expected boundary must be reached before test continues")
    }
}

@Implements(AlarmManager::class)
class PlanAlarmService : ShadowAlarmManager() {
    @Implementation override fun setExactAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        exactAttempts++
        if (fail) error("synthetic alarm failure")
        if (denyExact) throw SecurityException("synthetic permission race")
        super.setExactAndAllowWhileIdle(type, at, operation)
    }
    companion object { var fail = false; var denyExact = false; var exactAttempts = 0 }
}
@Implements(NotificationManager::class)
class PlanNotificationService : ShadowNotificationManager() {
    @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
        if (notification.channelId == PlanNotificationHelper.CHANNEL) {
            if (fail) error("synthetic notification failure")
            if (deny) throw SecurityException("synthetic permission revocation")
            // Model service-side suppression with an observable policy change, no exception.
            if (silentBlock) { setNotificationsEnabled(false); return }
            posts++
        }
        super.notify(tag, id, notification)
        if (notification.channelId == PlanNotificationHelper.CHANNEL && blockAfterPost) setNotificationsEnabled(false)
    }
    companion object { var fail = false; var deny = false; var silentBlock = false; var blockAfterPost = false; var posts = 0 }
}
