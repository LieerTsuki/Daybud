package com.example.lifemanager.di

import com.example.lifemanager.data.backup.AndroidBackupFileStore
import com.example.lifemanager.data.backup.V2BackupMutationRepositoryImpl
import com.example.lifemanager.data.backup.V2BackupRepositoryImpl
import com.example.lifemanager.data.backup.VersionedBackupJsonCodec
import com.example.lifemanager.data.backup.V4BackupRepository
import com.example.lifemanager.data.backup.V4BackupMutationRepository
import com.example.lifemanager.data.diary.FileDiaryStorage
import com.example.lifemanager.domain.diary.DiaryRepository
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.plan.PlanRepositoryImpl
import com.example.lifemanager.data.plan.RoomV1SettingsRepository
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderEffects
import com.example.lifemanager.domain.plan.PlanRepository
import com.example.lifemanager.domain.plan.PlanUseCases
import com.example.lifemanager.domain.repository.V1SettingsRepository
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton
    abstract fun bindHabitRepository(impl: HabitRepositoryImpl): HabitRepository

    @Binds @Singleton
    abstract fun bindSubscriptionRepository(impl: SubscriptionRepositoryImpl): SubscriptionRepository

    @Binds @Singleton
    abstract fun bindDataGenerationRepository(impl: DataGenerationRepositoryImpl): DataGenerationRepository

    @Binds @Singleton
    abstract fun bindBackupFileStore(impl: AndroidBackupFileStore): BackupFileStore
}

/** Every business, preference and backup adapter shares DatabaseModule's single database. */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton
    fun providePlanRepository(database: LifeManagerDatabase, clock: Clock): PlanRepository =
        PlanRepositoryImpl(database, database.planDao(), clock)

    @Provides @Singleton
    fun provideSettingsRepository(database: LifeManagerDatabase): V1SettingsRepository =
        RoomV1SettingsRepository(database)

    @Provides @Singleton
    fun providePlanUseCases(repository: PlanRepository, maintenance: MaintenanceCoordinator,
        reminders: PlanReminderEffects): PlanUseCases = PlanUseCases(repository, maintenance, reminders)

    @Provides @Singleton
    fun provideBackupRepository(database: LifeManagerDatabase,diary:DiaryRepository): V2BackupRepository =
        V4BackupRepository(database,V2BackupRepositoryImpl(database, database.backupSnapshotDao()),diary)

    @Provides @Singleton
    fun provideBackupMutations(database: LifeManagerDatabase,diary:DiaryRepository,storage:FileDiaryStorage,
        aiStore:com.example.lifemanager.data.diary.ai.AiPrivateStore): V2BackupMutationRepository =
        V4BackupMutationRepository(database,V2BackupMutationRepositoryImpl(database, database.backupSnapshotDao()),diary,storage,aiStore::pauseAutomatic)

    @Provides @Singleton
    fun provideBackupCodec(): VersionedBackupCodec = VersionedBackupJsonCodec()

    @Provides @Singleton
    fun provideBackupFileUseCases(repository: V2BackupRepository, codec: VersionedBackupCodec,
        files: BackupFileStore, maintenance: MaintenanceCoordinator,
        @IoDispatcher dispatcher: CoroutineDispatcher,archive:BackupArchive): V2BackupFileUseCases =
        V2BackupFileUseCases(repository, codec, files, maintenance, dispatcher,archive=archive)

    @Provides @Singleton
    fun provideBackupWorkflow(snapshots: V2BackupRepository, mutations: V2BackupMutationRepository,
        files: V2BackupFileUseCases, generations: DataGenerationRepository,
        coordinator: MaintenanceCoordinator, capture: BackupReminderCapture,
        reminders: MaintenanceReminderEffects, @IoDispatcher dispatcher: CoroutineDispatcher): V2BackupMaintenanceWorkflow =
        V2BackupMaintenanceWorkflow(snapshots, mutations, files, generations, coordinator,
            capture, reminders, dispatcher)

    @Provides @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
