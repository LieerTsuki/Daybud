package com.example.lifemanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.example.lifemanager.notification.*
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.backup.BackupFileStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LifeManagerApp : Application(), PlanReminderReceiver.Dependencies, UnifiedReminderReconciliationWorker.Dependencies {
    @Inject lateinit var backupFiles: BackupFileStore
    @Inject override lateinit var runner: ReminderBroadcastRunner
    @Inject override lateinit var delivery: PlanReminderDelivery
    @Inject override lateinit var unifiedReminderReconciliation: UnifiedReminderReconciliation
    @Inject lateinit var planNotifications: PlanNotificationHelper
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun requestReconciliation() =
        UnifiedReminderReconciliationWorker.enqueueImmediate(this, resetSystem = true)

    override fun onCreate() {
        super.onCreate()
        // Hilt has injected the same singleton graph used by receivers, workers and maintenance.
        planNotifications.createChannel()
        SubscriptionNotificationHelper.createChannel(this)
        UnifiedReminderReconciliationWorker.schedule(this)
        requestReconciliation()
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            // A failed cleanup is retried before the next import; unrelated CRUD remains available.
            runCatching { backupFiles.initializePrivateStorage() }
        }
    }
}
