package com.example.lifemanager.data.diary.ai

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class DiaryAiWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    interface Dependencies { val diaryAiEngine: DiaryAiEngine }
    override suspend fun doWork(): Result = try {
        check(inputData.keyValueMap.isEmpty())
        val engine = checkNotNull((applicationContext as? Dependencies)?.diaryAiEngine)
        engine.runAutomatic()
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.failure() }

    companion object {
        private const val NAME = "diary_ai_private_periodic"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiaryAiWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
