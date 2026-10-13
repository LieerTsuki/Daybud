package com.example.lifemanager.data.backup

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*

/** Isolated target-store adapter. Production v5 repository/Hilt stays unchanged until E. */
class V2BackupRepositoryImpl(private val database: RoomDatabase, dao: BackupSnapshotDao,
    limits: BackupLimits = BackupLimits()) : V2BackupRepository {
    private val sql = V2BackupSql(dao, limits)
    override suspend fun snapshot(): V2BackupPayload = database.withTransaction { sql.payload() }
}
