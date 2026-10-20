package com.example.lifemanager.di

import android.content.Context
import com.example.lifemanager.data.backup.*
import com.example.lifemanager.data.diary.*
import com.example.lifemanager.data.diary.ai.*
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.DiaryRepository
import com.example.lifemanager.domain.diary.ai.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.diary.AndroidDiaryMediaAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiaryModule {
    @Provides @Singleton fun storage(@ApplicationContext context:Context)=FileDiaryStorage(File(context.filesDir,"diary-media"))
    @Provides @Singleton fun diary(database:LifeManagerDatabase,maintenance:MaintenanceCoordinator,clock:Clock,storage:FileDiaryStorage):DiaryRepository =
        RoomDiaryRepository(database,maintenance,clock,storage)
    @Provides @Singleton fun media(@ApplicationContext context:Context,storage:FileDiaryStorage)=AndroidDiaryMediaAccess(context,storage)
    @Provides @Singleton fun aiStore(@ApplicationContext context:Context)=AiPrivateStore(context)
    @Provides @Singleton fun aiSource(repository:DiaryRepository,maintenance:MaintenanceCoordinator,generations:DataGenerationRepository,database:LifeManagerDatabase):DiaryAiDataSource =
        RoomDiaryAiDataSource(repository,maintenance,generations,database)
    @Provides @Singleton fun aiEngine(source:DiaryAiDataSource,store:AiPrivateStore,clock:Clock)=DiaryAiEngine(source,store,ChatCompletionsClient(),clock)
    @Provides @Singleton fun archive(@ApplicationContext context:Context,storage:FileDiaryStorage,codec:VersionedBackupCodec):BackupArchive =
        V4ArchiveFiles(ContentResolverBackupStreams(context.contentResolver),storage,codec)
}
