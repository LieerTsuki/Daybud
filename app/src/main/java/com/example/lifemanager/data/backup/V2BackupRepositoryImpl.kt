package com.example.lifemanager.data.backup

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*

/** Transactional snapshot adapter over the application's unified store. */
class V2BackupRepositoryImpl(private val database: RoomDatabase, dao: BackupSnapshotDao,
    limits: BackupLimits = BackupLimits()) : V2BackupRepository {
    private val sql = V2BackupSql(dao, limits)
    override suspend fun snapshot(): V2BackupPayload = database.withTransaction { sql.payload() }
}
