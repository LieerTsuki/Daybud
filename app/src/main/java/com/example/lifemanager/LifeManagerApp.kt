package com.example.lifemanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.example.lifemanager.notification.*
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.backup.BackupFileStore
import com.example.lifemanager.data.diary.FileDiaryStorage
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.data.diary.ai.DiaryAiWorker
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LifeManagerApp : Application(), PlanReminderReceiver.Dependencies, UnifiedReminderReconciliationWorker.Dependencies,DiaryAiWorker.Dependencies {
    @Inject lateinit var backupFiles: BackupFileStore
    @Inject override lateinit var runner: ReminderBroadcastRunner
    @Inject override lateinit var delivery: PlanReminderDelivery
    @Inject override lateinit var unifiedReminderReconciliation: UnifiedReminderReconciliation
    @Inject lateinit var planNotifications: PlanNotificationHelper
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher
    @Inject override lateinit var diaryAiEngine:DiaryAiEngine
    @Inject lateinit var diaryStorage:FileDiaryStorage
    @Inject lateinit var database:LifeManagerDatabase
    @Inject lateinit var maintenance:MaintenanceCoordinator

    override fun requestReconciliation() =
        UnifiedReminderReconciliationWorker.enqueueImmediate(this, resetSystem = true)

    override fun onCreate() {
        super.onCreate()
        // Hilt has injected the same singleton graph used by receivers, workers and maintenance.
        planNotifications.createChannel()
        SubscriptionNotificationHelper.createChannel(this)
        UnifiedReminderReconciliationWorker.schedule(this)
        DiaryAiWorker.schedule(this)
        requestReconciliation()
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            // A failed cleanup is retried before the next import; unrelated CRUD remains available.
            runCatching { backupFiles.initializePrivateStorage() }
            runCatching {
                val generation=maintenance.capture()
                maintenance.run(generation) {
                    diaryStorage.withGate { diaryStorage.garbageCollect(database.diaryDao().referencedMediaIds().toSet()) }
                }
            }
        }
    }
}
