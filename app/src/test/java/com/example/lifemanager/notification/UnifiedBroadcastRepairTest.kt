package com.example.lifemanager.notification

import android.app.Application
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UnifiedBroadcastRepairTest {
    @Test fun `cancel before arrival persistence requests future reset after admission unwinds`() = runTest {
        val f = UnifiedReminderTestFixture(); val entered = CompletableDeferred<Unit>(); var finishes = 0; var requests = 0
        try {
            val id = f.save(); f.reconcile(id); val token = f.token(id)
            val runner = ReminderBroadcastRunner(f.coordinator, StandardTestDispatcher(testScheduler))
            val job = runner.launchWithCancellationRepair({ finishes++ }, {
                assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
                requests++
            }, DataGeneration(0)) { entered.complete(Unit); awaitCancellation() }
            pump { entered.isCompleted }; job.cancelAndJoin()
            assertTrue(job.isCancelled); assertEquals(1, finishes); assertEquals(1, requests)
            assertEquals(PlanReminderState(token, false, true), f.store.get(id))
            f.clock.at = token.triggerAt.plusSeconds(1)
            f.reconciler().reconcile(true) // E binds cancellation callbacks to this policy, not ordinary preserve
            assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertEquals(PlanReminderState(token, false), f.store.get(id))
            f.coordinator.withSession {}
        } finally { f.close() }
    }
    @Test fun `repair callback failure cannot replace cancellation or skip finish`() = runTest {
        val f = UnifiedReminderTestFixture(); val entered = CompletableDeferred<Unit>(); var finishes = 0; var requests = 0
        try {
            val runner = ReminderBroadcastRunner(f.coordinator, StandardTestDispatcher(testScheduler))
            val job = runner.launchWithCancellationRepair({ finishes++ }, { requests++; error("synthetic enqueue failure") }, DataGeneration(0)) {
                entered.complete(Unit); awaitCancellation()
            }
            pump { entered.isCompleted }; job.cancelAndJoin()
            assertTrue(job.isCancelled); assertEquals(1, finishes); assertEquals(1, requests)
            f.coordinator.withSession {}
        } finally { f.close() }
    }
    @Test fun `operational state transitions reject stale identity and wrong expected state`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save(); f.reconcile(id); val token = f.token(id)
            val wrong = token.copy(generation = DataGeneration(1))
            assertFalse(f.store.markArrived(wrong)); assertFalse(f.store.markAwaiting(token))
            assertTrue(f.store.markArrived(token)); assertFalse(f.store.markArrived(token))
            assertEquals(PlanReminderState(token, false), f.store.get(id))
            assertFalse(f.store.markAwaiting(wrong)); assertTrue(f.store.markAwaiting(token))
            assertTrue(f.store.markDelivered(token)); assertFalse(f.store.markAwaiting(token)); assertFalse(f.store.markArrived(token))
            assertEquals(PlanReminderState(token, true), f.store.get(id))
        } finally { f.close() }
    }
    @Test fun `installation CAS failure cancels system alarm and retains unconfirmed Room evidence`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save()
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_install BEFORE UPDATE ON plan_reminder_state WHEN NEW.delivered=2 BEGIN SELECT RAISE(IGNORE); END")
            assertFailsWith<IllegalStateException> { f.reconcile(id) }
            assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertEquals(false, f.store.get(id)?.awaitingAlarm)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_install")
            f.reconciler().reconcile()
            assertEquals(true, f.store.get(id)?.awaitingAlarm); assertEquals(1, f.alarms.scheduledAlarms.size)
        } finally { f.close() }
    }
    private fun TestScope.pump(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        assertTrue(condition())
    }
}
