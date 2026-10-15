package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.work.*
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.schedulers
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.LifeManagerApp
import com.example.lifemanager.domain.plan.PlanReminderState
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Activation boundaries use the shipped manifest, real WorkManager persistence and v6 Room.
 * No test needs a legacy table to exist or authorizes interpreting a legacy ID as a plan ID.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class E6ReminderActivationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `application exposes plan receiver dependencies to the default receiver`() {
        assertTrue(PlanReminderReceiver.Dependencies::class.java.isAssignableFrom(LifeManagerApp::class.java),
            "Framework-created PlanReminderReceiver must obtain the application's injected services")
    }

    @Test fun `application exposes unified reconciliation to the reflection worker`() {
        assertTrue(UnifiedReminderReconciliationWorker.Dependencies::class.java.isAssignableFrom(LifeManagerApp::class.java),
            "The reflection-created worker must resolve the application's unified singleton")
    }

    @Test fun `shipped manifest activates private plan receiver and retains subscription and boot`() {
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers.orEmpty().associateBy { it.name }
        for (type in listOf(PlanReminderReceiver::class.java, SubscriptionReminderReceiver::class.java, BootReceiver::class.java)) {
            val receiver = assertNotNull(receivers[type.name], "Missing framework receiver ${type.simpleName}")
            assertTrue(receiver.enabled, "${type.simpleName} must be enabled")
            assertFalse(receiver.exported, "${type.simpleName} must remain private")
        }
    }

    @Test fun `every supported system event enqueues only a unified future reset reason`() = withManager { manager ->
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        actions.forEachIndexed { index, action ->
            BootReceiver().onReceive(context, Intent(action).putExtra("old_id", 19L))
            val requests = work(manager, "unified_reminder_reconciliation_immediate")
            assertEquals(index + 1, requests.size, "System events must persist a fresh unified pass")
            requests.forEach { request ->
                val spec = assertNotNull(manager.workDatabase.workSpecDao().getWorkSpec(request.id.toString()))
                assertEquals(UnifiedReminderReconciliationWorker::class.java.name, spec.workerClassName)
                assertEquals(mapOf("reset_system" to true), spec.input.keyValueMap,
                    "Only the future-reset reason may survive the system broadcast")
            }
            assertTrue(work(manager, "reminder_reconciliation_immediate").isEmpty())
        }
    }

    @Test fun `unrelated broadcasts cannot schedule reminder work`() = withManager { manager ->
        BootReceiver().onReceive(context, Intent("com.example.lifemanager.UNRELATED"))
        BootReceiver().onReceive(context, Intent())
        assertTrue(work(manager, "unified_reminder_reconciliation_immediate").isEmpty())
        assertTrue(work(manager, "reminder_reconciliation_immediate").isEmpty())
    }

    @Test fun `activating unified daily work cancels a periodic request persisted before upgrade`() = withManager { manager ->
        manager.enqueueUniquePeriodicWork("todo_reminder_reconciliation", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderReconciliationWorker>(1, TimeUnit.DAYS).build()).result.get(5, TimeUnit.SECONDS)
        val old = work(manager, "todo_reminder_reconciliation").single()
        repeat(2) { UnifiedReminderReconciliationWorker.schedule(context) }
        assertEquals(WorkInfo.State.CANCELLED,
            manager.getWorkInfoById(old.id).get(5, TimeUnit.SECONDS)!!.state,
            "An installed old periodic worker must not keep waking against removed tables")
        val current = work(manager, "unified_reminder_reconciliation_periodic").single()
        val spec = assertNotNull(manager.workDatabase.workSpecDao().getWorkSpec(current.id.toString()))
        assertEquals(UnifiedReminderReconciliationWorker::class.java.name, spec.workerClassName)
        assertEquals(TimeUnit.DAYS.toMillis(1), spec.intervalDuration)
        assertTrue(spec.input.keyValueMap.isEmpty())
    }

    @Test fun `persisted legacy worker drains successfully without resolving legacy data`() = runBlocking {
        val worker = ReminderReconciliationWorker(context, parameters(workDataOf("old_id" to 7L, "old_generation" to 3L)))
        assertEquals(ListenableWorker.Result.success(), worker.doWork(),
            "An obsolete queued class must ignore old payloads and finish without Hilt or a database")
    }

    @Test fun `framework two argument legacy worker drains with no Hilt or database`() = runBlocking {
        val constructor = ReminderReconciliationWorker::class.java.getConstructor(Context::class.java, WorkerParameters::class.java)
        val worker = constructor.newInstance(context, parameters())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun `todo tombstone never resolves old settings or repository for a colliding plan ID`() = runTest {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save(); f.reconcile(id)
            val token = f.token(id)
            assertTrue(f.notifier.show(token, "Current plan card"))
            var dependencyResolutions = 0
            val unavailable = unavailableContext { dependencyResolutions++ }
            val receiver = TodoReminderReceiver()
            receiver.onReceive(unavailable, Intent(context, TodoReminderReceiver::class.java)
                .setData(Uri.parse("lifemanager://todo-reminder/$id"))
                .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, id)
                .putExtra(TodoReminderReceiver.EXTRA_DUE_AT, token.triggerAt.plusSeconds(900).toEpochMilli())
                .putExtra(ReminderGeneration.EXTRA, 0L))
            runCurrent()
            drain(f)
            assertEquals(0, dependencyResolutions, "A tombstone must not resolve any legacy services")
            assertEquals(token, f.token(id)); assertEquals(listOf(token), f.notifier.tokens())
            assertEquals(1, f.alarms.scheduledAlarms.size)
        } finally { f.close() }
    }

    @Test fun `schedule tombstone never resolves old settings or repository for a colliding plan ID`() = runTest {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save(); f.reconcile(id)
            val token = f.token(id)
            assertTrue(f.notifier.show(token, "Current plan card"))
            var dependencyResolutions = 0
            val unavailable = unavailableContext { dependencyResolutions++ }
            val receiver = ScheduleReminderReceiver()
            receiver.onReceive(unavailable, Intent(context, ScheduleReminderReceiver::class.java)
                .setData(Uri.parse("lifemanager://schedule-reminder/$id"))
                .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, id)
                .putExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, token.triggerAt.plusSeconds(900).toEpochMilli())
                .putExtra(ReminderGeneration.EXTRA, 0L))
            runCurrent()
            drain(f)
            assertEquals(0, dependencyResolutions, "A tombstone must not resolve any legacy services")
            assertEquals(token, f.token(id)); assertEquals(listOf(token), f.notifier.tokens())
            assertEquals(1, f.alarms.scheduledAlarms.size)
        } finally { f.close() }
    }

    // These controls keep activation from bypassing the established strict plan protocol.
    @Test fun `future reset on current Room skips an elapsed plan trigger without catchup`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save(); f.reconcile(id)
            val expired = f.token(id)
            f.clock.at = expired.triggerAt.plusSeconds(1)
            f.reconciler().reconcile(true)
            assertEquals(PlanReminderState(expired, false, false), f.store.get(id))
            assertEquals(PlanDeliveryOutcome.IGNORED, f.coordinator.run(expired.generation) { f.delivery.deliverCurrent(expired) })
            assertTrue(f.alarms.scheduledAlarms.isEmpty()); assertTrue(f.notifier.tokens().isEmpty())
            assertTrue(assertNotNull(f.repo.get(id)).reminderEnabled)
        } finally { f.close() }
    }

    @Test fun `current Room plan switch disables repair while retaining the business reminder preference`() = runBlocking {
        val f = UnifiedReminderTestFixture()
        try {
            val id = f.save(); f.reconcile(id)
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=0")
            f.reconciler().reconcile(true)
            assertNull(f.store.get(id)); assertTrue(f.alarms.scheduledAlarms.isEmpty())
            assertTrue(assertNotNull(f.repo.get(id)).reminderEnabled)
        } finally { f.close() }
    }

    private suspend fun TestScope.drain(f: UnifiedReminderTestFixture) {
        // Finish actual admitted Room work before asserting or closing its database. A no-op
        // tombstone admits nothing, so this completes immediately without touching business SQL.
        val drained = async { f.coordinator.withSession {} }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!drained.isCompleted && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        assertTrue(drained.isCompleted, "Any admitted receiver operation must release its permit")
        drained.await()
    }

    private fun withManager(block: (WorkManagerImpl) -> Unit) {
        val executor = Executor { it.run() }
        val configuration = Configuration.Builder().setTaskExecutor(executor).setExecutor(executor).build()
        val tasks = WorkManagerTaskExecutor(executor)
        val manager = WorkManagerImpl(context, configuration, tasks,
            WorkDatabase.create(context, tasks.serialTaskExecutor, configuration.clock, true), schedulersCreator = schedulers())
        WorkManagerImpl.setDelegate(manager)
        try { block(manager) } finally { manager.close(); WorkManagerImpl.setDelegate(null) }
    }

    private fun work(manager: WorkManagerImpl, name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    private fun unavailableContext(onResolution: () -> Unit) = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context { onResolution(); error("Legacy application services unavailable") }
        override fun getDatabasePath(name: String): java.io.File { onResolution(); error("Legacy database unavailable") }
    }

    private fun parameters(data: Data = Data.EMPTY): WorkerParameters {
        val executor = Executor { it.run() }
        return WorkerParameters(UUID.randomUUID(), data, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
            }, object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data) =
                    androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused progress" }
            }, object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo) =
                    androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused foreground" }
            })
    }
}
