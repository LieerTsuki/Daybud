package com.example.lifemanager.notification

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.*
import androidx.work.impl.StartStopToken
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.impl.schedulers
import androidx.work.impl.close
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UnifiedWorkerRoutingTest {
    @Test fun `worker reads current Room each pass and system reset never catches up`() = fixture { f ->
        val id = f.save(f.plan().copy(timeMode = PlanTimeMode.TIMED, dueAt = null,
            startAt = java.time.Instant.parse("2026-01-01T10:00:00Z"), endAt = java.time.Instant.parse("2026-01-01T11:00:00Z"),
            repeatRule = com.example.lifemanager.domain.model.ScheduleRepeatRule.DAILY))
        assertEquals(ListenableWorker.Result.success(), worker(f).doWork())
        val a = f.token(id)
        f.clock.at = a.triggerAt.plusSeconds(60)
        assertEquals(ListenableWorker.Result.success(), worker(f, reset = true).doWork())
        assertEquals(java.time.Instant.parse("2026-01-02T09:45:00Z"), f.token(id).triggerAt)
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
        assertEquals(ListenableWorker.Result.success(), worker(f).doWork())
        assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertTrue(f.repo.get(id)!!.reminderEnabled)
    }
    @Test fun `actual Room corruption and frozen maintenance produce retry without side effects`() = fixture { f ->
        f.save()
        f.coordinator.withSession { assertEquals(ListenableWorker.Result.retry(), worker(f).doWork()) }
        f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=3")
        assertEquals(ListenableWorker.Result.retry(), worker(f).doWork())
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `worker cancellation propagates and releases global and plan permits`() = fixture { f ->
        val actual = f.sources
        val cancelled = object : PlanReminderSources by actual {
            override suspend fun configuration(): PlanReminderConfiguration { throw CancellationException("synthetic cancellation") }
        }
        assertFailsWith<CancellationException> {
            UnifiedReminderReconciliationWorker(f.context, parameters()) { f.reconciler(cancelled) }.doWork()
        }
        f.coordinator.withSession {}
        assertEquals(ListenableWorker.Result.success(), worker(f).doWork())
    }
    @Test fun `reflection two argument worker without application binding retries safely`() = fixture { f ->
        val constructor = UnifiedReminderReconciliationWorker::class.java.getConstructor(Context::class.java, WorkerParameters::class.java)
        val instance = constructor.newInstance(f.context, parameters())
        assertEquals(ListenableWorker.Result.retry(), instance.doWork())
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `reset flag malformed type or rejected payload cannot authorize calibration`() = fixture { f ->
        f.save()
        for (data in listOf(workDataOf("reset_system" to "true"), workDataOf("old_id" to 17L))) {
            assertEquals(ListenableWorker.Result.retry(), UnifiedReminderReconciliationWorker(f.context, parameters(data)) { f.reconciler() }.doWork())
        }
        assertTrue(f.alarms.scheduledAlarms.isEmpty())
    }
    @Test fun `maintenance drains full pass through subscription and cancellation releases admission`() = fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val subscriptions = object : SubscriptionCalibration {
            override suspend fun reconcile(expected: DataGeneration, resetSystem: Boolean) {
                entered.complete(Unit); release.await(); f.subscriptionCalibration.reconcile(expected, resetSystem)
            }
        }
        val reconciliation = UnifiedReminderReconciler(f.coordinator, f.repo, f.sources, f.store,
            LegacyPlanReminderCleaner(f.context), f.scheduler, f.notifier, subscriptions)
        coroutineScope {
            val pass = launch { reconciliation.reconcile() }
            withTimeout(5_000) { entered.await() }
            val maintenance = async { f.coordinator.withSession { assertEquals(MaintenanceState.READY, f.coordinator.state.value) } }
            withTimeout(5_000) { f.coordinator.state.first { it == MaintenanceState.DRAINING } }
            assertFalse(maintenance.isCompleted)
            pass.cancelAndJoin()
            withTimeout(5_000) { maintenance.await() }
        }
        assertEquals(ListenableWorker.Result.success(), worker(f).doWork())
    }
    @Test fun `event during actual running Worker executes a second fresh Room pass with reason only`() = runTest {
        val f = UnifiedReminderTestFixture(); val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reads = 0
        val actual = f.sources
        val paused = object : PlanReminderSources by actual {
            override suspend fun configuration(): PlanReminderConfiguration {
                val config = actual.configuration()
                if (++reads == 1) { entered.complete(Unit); release.await() }
                return config
            }
        }
        val manager = manager(f.context, dispatcher, object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == UnifiedReminderReconciliationWorker::class.java.name)
                    UnifiedReminderReconciliationWorker(appContext, workerParameters) { f.reconciler(paused) } else null
        })
        try {
            val id = f.save()
            UnifiedReminderReconciliationWorker.enqueueImmediate(f.context)
            val a = work(manager).single(); start(manager, a.id.toString())
            pump("A reads real target Room") { entered.isCompleted }
            assertEquals(WorkInfo.State.RUNNING, manager.workDatabase.workSpecDao().getState(a.id.toString()))
            UnifiedReminderReconciliationWorker.enqueueImmediate(f.context, true)
            val b = work(manager).single { it.id != a.id }
            assertEquals(WorkInfo.State.BLOCKED, b.state)
            assertEquals(listOf(a.id.toString()), manager.workDatabase.dependencyDao().getPrerequisites(b.id.toString()))
            assertEquals(mapOf("reset_system" to true), manager.workDatabase.workSpecDao().getWorkSpec(b.id.toString())!!.input.keyValueMap)
            assertEquals(WorkInfo.State.RUNNING, manager.workDatabase.workSpecDao().getState(a.id.toString()))
            release.complete(Unit)
            pump("A completes") { manager.workDatabase.workSpecDao().getState(a.id.toString()) == WorkInfo.State.SUCCEEDED }
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
            start(manager, b.id.toString())
            pump("B executes fresh switched-off Room facts") { manager.workDatabase.workSpecDao().getState(b.id.toString()) == WorkInfo.State.SUCCEEDED }
            assertTrue(f.alarms.scheduledAlarms.none { shadowOf(it.operation).savedIntent.component?.className == PlanReminderReceiver::class.java.name })
            assertTrue(f.repo.get(id)!!.reminderEnabled)
        } finally {
            release.complete(Unit)
            runCurrent(); shadowOf(Looper.getMainLooper()).idle()
            manager.close(); WorkManagerImpl.setDelegate(null); f.close()
        }
    }
    @Test fun `failed and cancelled chain cannot poison subsequent immediate calibration`() {
        val f = UnifiedReminderTestFixture()
        val manager = manager(f.context, EmptyCoroutineContext, object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        })
        try {
            for (terminal in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
                UnifiedReminderReconciliationWorker.enqueueImmediate(f.context)
                val a = work(manager).single { !it.state.isFinished }
                manager.workDatabase.workSpecDao().setState(terminal, a.id.toString())
                UnifiedReminderReconciliationWorker.enqueueImmediate(f.context, true)
                val b = work(manager).single { !it.state.isFinished }
                assertNotEquals(a.id, b.id); assertEquals(WorkInfo.State.ENQUEUED, b.state)
                assertTrue(manager.workDatabase.dependencyDao().getPrerequisites(b.id.toString()).isEmpty())
                manager.workDatabase.workSpecDao().setState(WorkInfo.State.SUCCEEDED, b.id.toString())
            }
        } finally { manager.close(); WorkManagerImpl.setDelegate(null); f.close() }
    }
    @Test fun `periodic scheduling is isolated idempotent and has no business payload`() {
        val f = UnifiedReminderTestFixture()
        val manager = manager(f.context, EmptyCoroutineContext, object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        })
        try {
            repeat(2) { UnifiedReminderReconciliationWorker.schedule(f.context) }
            val request = manager.getWorkInfosForUniqueWork("unified_reminder_reconciliation_periodic").get(5, TimeUnit.SECONDS).single()
            val spec = manager.workDatabase.workSpecDao().getWorkSpec(request.id.toString())!!
            assertEquals(UnifiedReminderReconciliationWorker::class.java.name, spec.workerClassName)
            assertEquals(TimeUnit.DAYS.toMillis(1), spec.intervalDuration); assertTrue(spec.input.keyValueMap.isEmpty())
            assertTrue(manager.getWorkInfosForUniqueWork("todo_reminder_reconciliation").get(5, TimeUnit.SECONDS).isEmpty())
        } finally { manager.close(); WorkManagerImpl.setDelegate(null); f.close() }
    }

    private fun worker(f: UnifiedReminderTestFixture, reset: Boolean = false) =
        UnifiedReminderReconciliationWorker(f.context, parameters(workDataOf("reset_system" to reset))) { f.reconciler() }
    private fun fixture(block: suspend (UnifiedReminderTestFixture) -> Unit) = runBlocking {
        val f = UnifiedReminderTestFixture(); try { block(f) } finally { f.close() }
    }
    private fun manager(context: Context, coroutineContext: kotlin.coroutines.CoroutineContext, factory: WorkerFactory): WorkManagerImpl {
        val executor = Executor { it.run() }
        val configuration = Configuration.Builder().setTaskExecutor(executor).setExecutor(executor)
            .setWorkerCoroutineContext(coroutineContext).setWorkerFactory(factory).build()
        val tasks = WorkManagerTaskExecutor(executor)
        return WorkManagerImpl(context, configuration, tasks,
            WorkDatabase.create(context, tasks.serialTaskExecutor, configuration.clock, true), schedulersCreator = schedulers())
            .also { WorkManagerImpl.setDelegate(it) }
    }
    private fun work(manager: WorkManagerImpl) = manager.getWorkInfosForUniqueWork("unified_reminder_reconciliation_immediate").get(5, TimeUnit.SECONDS)
    private fun start(manager: WorkManagerImpl, id: String) {
        val spec = assertNotNull(manager.workDatabase.workSpecDao().getWorkSpec(id))
        assertTrue(manager.processor.startWork(StartStopToken(WorkGenerationalId(id, spec.generation))))
    }
    private fun TestScope.pump(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do { shadowOf(Looper.getMainLooper()).idle(); runCurrent(); if (condition()) return; Thread.sleep(10) } while (System.nanoTime() < deadline)
        assertTrue(condition(), message)
    }
    private fun parameters(data: Data = Data.EMPTY): WorkerParameters {
        val executor = Executor { it.run() }
        return WorkerParameters(UUID.randomUUID(), data, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
            }, object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data) = androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused" }
            }, object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo) = androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused" }
            })
    }
}
