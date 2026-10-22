package com.example.lifemanager.data.diary.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.schedulers
import androidx.work.impl.close
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiaryAiWorkerRetirementTest {
    @Test fun oldQueuedWorkerCompletesWithoutApplicationBusinessDependencies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = DiaryAiWorker(context, parameters(Data.EMPTY))
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
    @Test fun obsoleteInputCannotReactivateCloudGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = DiaryAiWorker(context, parameters(workDataOf("old_cloud_job" to true)))
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
    @Test fun retirementCancelsOnlyHistoricalAiWorkAndPreservesReminders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executor { it.run() }
        val configuration = Configuration.Builder().setTaskExecutor(executor).setExecutor(executor)
            .setWorkerCoroutineContext(Dispatchers.Unconfined).build()
        val tasks = WorkManagerTaskExecutor(executor)
        val manager = WorkManagerImpl(context, configuration, tasks,
            WorkDatabase.create(context, tasks.serialTaskExecutor, configuration.clock, true), schedulersCreator = schedulers())
        WorkManagerImpl.setDelegate(manager)
        try {
            manager.enqueueUniquePeriodicWork("diary_ai_private_periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiaryAiWorker>(6, TimeUnit.HOURS).build()).result.get(5, TimeUnit.SECONDS)
            manager.enqueueUniquePeriodicWork("unified_reminder_reconciliation", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiaryAiWorker>(6, TimeUnit.HOURS).build()).result.get(5, TimeUnit.SECONDS)
            DiaryAiWorker.retire(context)
            val ai = manager.getWorkInfosForUniqueWork("diary_ai_private_periodic").get(5, TimeUnit.SECONDS)
            val reminders = manager.getWorkInfosForUniqueWork("unified_reminder_reconciliation").get(5, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.CANCELLED, ai.single().state)
            assertNotEquals(WorkInfo.State.CANCELLED, reminders.single().state)
        } finally { manager.close(); WorkManagerImpl.setDelegate(null) }
    }
    private fun parameters(data: Data): WorkerParameters {
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
