package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.lifemanager.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Transaction @Query("SELECT * FROM plans ORDER BY id")
    fun observeAll(): Flow<List<PlanWithRelations>>
    @Transaction @Query("SELECT * FROM plans WHERE id=:id")
    suspend fun find(id: Long): PlanWithRelations?
    @Query("SELECT * FROM plans WHERE id=:id") suspend fun findEntity(id: Long): PlanEntity?
    @Query("SELECT * FROM plans ORDER BY id") suspend fun allEntities(): List<PlanEntity>
    @Insert suspend fun insert(value: PlanEntity): Long
    @Update suspend fun update(value: PlanEntity): Int
    @Query("DELETE FROM plans WHERE id=:id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM tags ORDER BY name, id") fun observeTags(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags WHERE name=:name") suspend fun findTag(name: String): TagEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(value: TagEntity): Long
    @Query("DELETE FROM plan_tag_cross_ref WHERE planId=:id") suspend fun deleteLinks(id: Long)
    @Insert suspend fun insertLinks(values: List<PlanTagCrossRef>)
    @Query("SELECT * FROM plan_exceptions WHERE planId=:id ORDER BY occurrenceDate")
    suspend fun exceptions(id: Long): List<PlanExceptionEntity>
    @Insert suspend fun insertException(value: PlanExceptionEntity)
    @Update suspend fun updateException(value: PlanExceptionEntity): Int
    @Insert suspend fun insertLegacyRef(value: PlanLegacyRefEntity)
    @Query("SELECT planId FROM plan_legacy_refs WHERE source=:source AND legacyId=:legacyId")
    suspend fun findLegacy(source: LegacyPlanSource, legacyId: Long): Long?
    @RawQuery fun read(query: SupportSQLiteQuery): Cursor
}
