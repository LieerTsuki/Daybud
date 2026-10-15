package com.example.lifemanager.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** WorkManager may reflect this persisted class after upgrade. Drain without resolving any data. */
class ReminderReconciliationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}
