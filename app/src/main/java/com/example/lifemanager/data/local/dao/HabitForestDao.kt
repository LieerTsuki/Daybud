package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.lifemanager.data.local.entity.HabitTreeEntity
import com.example.lifemanager.data.local.entity.HabitWateringEntity
import kotlinx.coroutines.flow.Flow

data class HabitForestRow(val id: Long, val habitId: Long, val species: String, val plantedAt: Long,
    val wateringCount: Int, val availableDrops: Int)

@Dao
interface HabitForestDao {
    @Query("""SELECT t.*, (SELECT COUNT(*) FROM habit_waterings w WHERE w.treeId=t.id) AS wateringCount,
        (SELECT COUNT(*) FROM habit_records r WHERE r.habitId=t.habitId AND r.date<=:today
          AND NOT EXISTS(SELECT 1 FROM habit_waterings w WHERE w.recordId=r.id)) AS availableDrops
        FROM habit_trees t ORDER BY t.plantedAt,t.id""")
    fun observeRows(today: Long): Flow<List<HabitForestRow>>

    @Query("SELECT * FROM habit_trees WHERE id=:id") suspend fun tree(id: Long): HabitTreeEntity?
    @Query("SELECT * FROM habit_trees WHERE habitId=:habitId ORDER BY plantedAt,id") suspend fun trees(habitId: Long): List<HabitTreeEntity>
    @Query("SELECT COUNT(*) FROM habit_waterings WHERE treeId=:treeId") suspend fun waterCount(treeId: Long): Int
    @Query("""SELECT r.id FROM habit_records r WHERE r.habitId=:habitId AND r.date<=:today
        AND NOT EXISTS(SELECT 1 FROM habit_waterings w WHERE w.recordId=r.id)
        ORDER BY r.date,r.id LIMIT 1""")
    suspend fun unusedRecord(habitId: Long, today: Long): Long?
    @Query("UPDATE habit_trees SET species=:species WHERE id=:treeId") suspend fun updateSpecies(treeId: Long, species: String)
    @Insert suspend fun insertTree(tree: HabitTreeEntity): Long
    @Insert suspend fun insertWatering(watering: HabitWateringEntity)
}
