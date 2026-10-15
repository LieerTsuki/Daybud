package com.example.lifemanager.di

import android.content.Context
import com.example.lifemanager.data.backup.V2BackupReminderCaptureImpl
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.plan.RoomPlanReminderSources
import com.example.lifemanager.data.plan.RoomPlanReminderStore
import com.example.lifemanager.domain.backup.BackupPlanNotificationSource
import com.example.lifemanager.domain.backup.BackupReminderCapture
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceReminderEffects
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.notification.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

/** One reminder graph over the production v6 database and the application's admission service. */
@Module
@InstallIn(SingletonComponent::class)
object V1ReminderModule {
    @Provides @Singleton
    fun provideRoomSources(database: LifeManagerDatabase) = RoomPlanReminderSources(database.planDao())

    @Provides fun provideSources(source: RoomPlanReminderSources): PlanReminderSources = source
    @Provides fun providePreferences(source: RoomPlanReminderSources): PlanReminderPreferences = source

    @Provides @Singleton
    fun provideRoomStore(database: LifeManagerDatabase) = RoomPlanReminderStore(database.planReminderStateDao())

    @Provides fun provideStore(store: RoomPlanReminderStore): PlanReminderStore = store

    @Provides @Singleton
    fun providePlanNotifications(@ApplicationContext context: Context) = PlanNotificationHelper(context)

    @Provides fun provideNotifier(helper: PlanNotificationHelper): PlanReminderNotifier = helper
    @Provides fun provideNotificationInventory(helper: PlanNotificationHelper): PlanNotificationInventory = helper
    @Provides fun provideBackupNotificationSource(helper: PlanNotificationHelper): BackupPlanNotificationSource = helper

    @Provides @Singleton
    fun providePlanScheduler(@ApplicationContext context: Context, plans: PlanRepository,
        generations: DataGenerationRepository, preferences: PlanReminderPreferences, store: PlanReminderStore,
        notifier: PlanReminderNotifier, clock: Clock) =
        PlanReminderScheduler(context, plans, generations, preferences, store, notifier, clock)

    @Provides fun provideCalibration(scheduler: PlanReminderScheduler): PlanReminderCalibration = scheduler
    @Provides fun provideScheduler(scheduler: PlanReminderScheduler): PlanReminderSchedulerContract = scheduler
    @Provides fun provideEffects(scheduler: PlanReminderScheduler): PlanReminderEffects = scheduler

    @Provides @Singleton
    fun provideDelivery(plans: PlanRepository, generations: DataGenerationRepository,
        preferences: PlanReminderPreferences, store: PlanReminderStore, notifier: PlanReminderNotifier,
        scheduler: PlanReminderSchedulerContract, clock: Clock) =
        PlanReminderDelivery(plans, generations, preferences, store, notifier, scheduler, clock)

    @Provides @Singleton
    fun provideLegacyCleaner(@ApplicationContext context: Context) = LegacyPlanReminderCleaner(context)

    @Provides @Singleton
    fun provideSubscriptionState(generations: DataGenerationRepository, sources: PlanReminderSources) =
        SubscriptionReminderStateReader { generations.current() to sources.configuration().subscriptionReminders }

    @Provides @Singleton
    fun provideSubscriptionScheduler(@ApplicationContext context: Context, reader: SubscriptionReminderStateReader,
        clock: Clock): SubscriptionReminderScheduler = SubscriptionReminderScheduler.forUnified(context, reader).apply {
        nowProvider = clock::instant
        zoneProvider = ZoneId::systemDefault
    }

    @Provides fun provideSubscriptionContract(scheduler: SubscriptionReminderScheduler): SubscriptionReminderSchedulerContract = scheduler

    @Provides @Singleton
    fun provideSubscriptionCalibration(@ApplicationContext context: Context, repository: SubscriptionRepository,
        generations: DataGenerationRepository, sources: PlanReminderSources, scheduler: SubscriptionReminderSchedulerContract) =
        UnifiedSubscriptionCalibration(context, repository, generations, sources, scheduler)

    @Provides fun provideSubscriptionCalibrationContract(calibration: UnifiedSubscriptionCalibration): SubscriptionCalibration = calibration

    @Provides @Singleton
    fun provideUnifiedReconciler(maintenance: MaintenanceCoordinator, plans: PlanRepository,
        sources: PlanReminderSources, store: PlanReminderStore, legacy: LegacyPlanReminderCleaner,
        scheduler: PlanReminderCalibration, notifications: PlanNotificationInventory, subscriptions: SubscriptionCalibration) =
        UnifiedReminderReconciler(maintenance, plans, sources, store, legacy, scheduler, notifications, subscriptions)

    @Provides fun provideReconciliation(reconciler: UnifiedReminderReconciler): UnifiedReminderReconciliation = reconciler

    @Provides @Singleton
    fun provideBackupReminderCapture(database: LifeManagerDatabase, notifications: BackupPlanNotificationSource) =
        V2BackupReminderCaptureImpl(database, database.backupSnapshotDao(), notifications)

    @Provides fun provideBackupCapture(capture: V2BackupReminderCaptureImpl): BackupReminderCapture = capture

    @Provides @Singleton
    fun provideMaintenanceEffects(@ApplicationContext context: Context, plans: PlanReminderCalibration,
        notifications: PlanNotificationInventory, legacy: LegacyPlanReminderCleaner,
        subscriptions: SubscriptionReminderSchedulerContract, reconciliation: UnifiedReminderReconciliation) =
        UnifiedMaintenanceReminderEffects(context, plans, notifications, legacy, subscriptions, reconciliation) {
            UnifiedReminderReconciliationWorker.enqueueImmediate(context, resetSystem = true)
        }

    @Provides fun provideMaintenanceContract(effects: UnifiedMaintenanceReminderEffects): MaintenanceReminderEffects = effects
}
