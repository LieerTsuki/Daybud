package com.example.lifemanager.data.plan

import androidx.room.Database
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.lifemanager.data.local.Converters
import com.example.lifemanager.data.local.entity.MaintenanceEntity
import com.example.lifemanager.data.local.entity.TagEntity

/** Storage fixture only, not the complete future v6 database or a migration harness. */
@Database(entities = [PlanEntity::class, PlanTagCrossRef::class, PlanExceptionEntity::class,
    PlanLegacyRefEntity::class, TagEntity::class, MaintenanceEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class, PlanConverters::class)
abstract class PlanRepositoryTestDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun testTags(): PlanTagTestDao
}

@Dao
interface PlanTagTestDao {
    @Query("UPDATE tags SET name=:name WHERE id=:id") suspend fun rename(id: Long, name: String): Int
}
