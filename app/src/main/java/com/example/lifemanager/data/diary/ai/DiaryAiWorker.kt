package com.example.lifemanager.data.diary.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Kept under its historical name so already queued work cannot reactivate cloud sending. */
class DiaryAiWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        private const val NAME = "diary_ai_private_periodic"
        fun retire(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
