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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LifeManagerApp : Application(), PlanReminderReceiver.Dependencies, UnifiedReminderReconciliationWorker.Dependencies,DiaryAiWorker.Dependencies {
    // No eager injected business fields: Hilt's super.onCreate runs in the private process too.
    private val business: MainProcessDependencies by lazy {
        check(isBusinessProcess()) { "当前进程不能获取业务依赖" }
        EntryPointAccessors.fromApplication(this, MainProcessDependencies::class.java)
    }
    val backupFiles get() = business.backupFiles()
    override val runner get() = business.runner()
    override val delivery get() = business.delivery()
    override val unifiedReminderReconciliation get() = business.reconciliation()
    val planNotifications get() = business.notifications()
    val ioDispatcher get() = business.ioDispatcher()
    override val diaryAiEngine get() = business.aiEngine()
    val diaryStorage get() = business.storage()
    val database get() = business.database()
    val maintenance get() = business.maintenance()

    private fun isBusinessProcess(): Boolean {
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            runCatching { java.io.File("/proc/self/cmdline").inputStream().use { stream ->
                val bytes = ByteArray(256); val length = stream.read(bytes)
                if (length <= 0) "" else bytes.copyOf(length).toString(Charsets.UTF_8).substringBefore('\u0000')
            } }.getOrDefault("")
        }
        // Unknown identity must not run media cleanup, business DB or upload workers.
        return process == packageName
    }

    override fun requestReconciliation() =
        UnifiedReminderReconciliationWorker.enqueueImmediate(this, resetSystem = true)

    override fun onCreate() {
        super.onCreate()
        if (!isBusinessProcess()) return
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainProcessDependencies {
    fun backupFiles(): BackupFileStore
    fun runner(): ReminderBroadcastRunner
    fun delivery(): PlanReminderDelivery
    fun reconciliation(): UnifiedReminderReconciliation
    fun notifications(): PlanNotificationHelper
    @IoDispatcher fun ioDispatcher(): CoroutineDispatcher
    fun aiEngine(): DiaryAiEngine
    fun storage(): FileDiaryStorage
    fun database(): LifeManagerDatabase
    fun maintenance(): MaintenanceCoordinator
}
