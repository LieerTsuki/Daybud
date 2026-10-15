package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Local operation identity is not a preference or a portable backup field. */
@Dao
interface MaintenanceDao {
    @Query("SELECT generation FROM app_maintenance WHERE id=1")
    fun observeGeneration(): Flow<Long?>

    @Query("UPDATE app_maintenance SET generation=:next WHERE id=1 AND generation=:expected")
    suspend fun advanceGeneration(expected: Long, next: Long): Int
}
