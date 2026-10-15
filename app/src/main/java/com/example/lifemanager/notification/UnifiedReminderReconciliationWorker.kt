package com.example.lifemanager.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class UnifiedReminderReconciliationWorker @JvmOverloads constructor(
    context: Context, parameters: WorkerParameters,
    private val provider: (Context) -> UnifiedReminderReconciliation? = { (it.applicationContext as? Dependencies)?.unifiedReminderReconciliation },
) : CoroutineWorker(context, parameters) {
    interface Dependencies { val unifiedReminderReconciliation: UnifiedReminderReconciliation }
    override suspend fun doWork(): Result = try {
        val values = inputData.keyValueMap
        check(values.keys.all { it == RESET } && (RESET !in values || values[RESET] is Boolean))
        val reconciliation = checkNotNull(provider(applicationContext)) { "Unified reminder services not activated" }
        reconciliation.reconcile(inputData.getBoolean(RESET, false))
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.retry() }
    companion object {
        private const val RESET = "reset_system"
        private const val IMMEDIATE = "unified_reminder_reconciliation_immediate"
        private const val PERIODIC = "unified_reminder_reconciliation_periodic"
        /** Reason only. Running work is not cancelled; the following pass reads current Room. */
        fun enqueueImmediate(context: Context, resetSystem: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UnifiedReminderReconciliationWorker>()
                    .setInputData(workDataOf(RESET to resetSystem)).build())
        }
        /** Retire persisted legacy queues before activating the sole daily calibration path. */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork("todo_reminder_reconciliation")
            manager.cancelUniqueWork("reminder_reconciliation_immediate")
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UnifiedReminderReconciliationWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
