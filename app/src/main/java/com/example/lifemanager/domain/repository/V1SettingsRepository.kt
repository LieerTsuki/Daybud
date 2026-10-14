package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.V1AppSettings
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.flow.Flow

/** Caller must hold maintenance admission; implementation verifies expected in its transaction. */
interface V1SettingsRepository {
    fun observe():Flow<V1AppSettings>
    suspend fun update(expected:DataGeneration,transform:(V1AppSettings)->V1AppSettings):V1AppSettings
}
