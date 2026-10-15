package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.V1AppSettings
import com.example.lifemanager.domain.repository.V1SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Caller owns original admission and its module lock. Read fresh V1 facts without reacquiring either. */
@Singleton
class ReminderSchedulingState @Inject constructor(
    private val generations: DataGenerationRepository,
    private val settings: V1SettingsRepository,
) {
    suspend fun read(): Pair<DataGeneration, V1AppSettings> = generations.current() to settings.observe().first()

    companion object {
        fun fromApplication(context: Context): ReminderSchedulingState =
            EntryPointAccessors.fromApplication(context.applicationContext, ReminderSafetyEntryPoint::class.java)
                .reminderSchedulingState()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderSafetyEntryPoint {
    fun reminderSchedulingState(): ReminderSchedulingState
    fun settingsRepository(): V1SettingsRepository
    fun maintenanceCoordinator(): MaintenanceCoordinator
    fun reminderBroadcastRunner(): ReminderBroadcastRunner
}

internal fun reminderSettings(context: Context): V1SettingsRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, ReminderSafetyEntryPoint::class.java)
        .settingsRepository()
