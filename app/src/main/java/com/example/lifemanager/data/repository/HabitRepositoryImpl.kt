package com.example.lifemanager.data.repository

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.data.local.entity.HabitTreeEntity
import com.example.lifemanager.data.local.entity.HabitWateringEntity
import com.example.lifemanager.domain.model.HabitForest
import com.example.lifemanager.domain.model.ForestTree
import com.example.lifemanager.domain.model.TreeSpecies
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.domain.usecase.HabitRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi

class HabitRepositoryImpl @Inject constructor(private val database: LifeManagerDatabase,
    private val clock: Clock = Clock.systemDefaultZone()) : HabitRepository {
    private val dao = database.habitDao()
    private val forest = database.habitForestDao()
    override fun observeHabits(): Flow<List<Habit>> = dao.observeAll().map { it.map(HabitEntity::toDomain) }
    override fun observeRecords(start: LocalDate, end: LocalDate): Flow<List<HabitRecord>> = dao.recordsBetween(start.toEpochDay(), end.toEpochDay()).map { it.map(HabitRecordEntity::toDomain) }
    override suspend fun saveHabit(habit: Habit): Long = database.withTransaction {
        val now = Instant.now(clock)
        // Update the parent in place and create its seed in the same transaction.
        val inserted = dao.upsert(habit.toEntity(now))
        val id = if (habit.id == 0L) inserted else habit.id
        val species = TreeSpecies.fromHabit(habit.copy(id = id)).name
        val trees = forest.trees(id)
        if (trees.isEmpty()) forest.insertTree(HabitTreeEntity(habitId = id, species = species, plantedAt = now.toEpochMilli()))
        else for (tree in trees) if (forest.waterCount(tree.id) < 20) forest.updateSpecies(tree.id, species)
        id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeForest(): Flow<HabitForest> = flow {
        // Eligibility depends on the civil date even when Room has not changed overnight.
        while (true) {
            emit(LocalDate.now(clock).toEpochDay())
            delay(60_000)
        }
    }.distinctUntilChanged().flatMapLatest { today -> forest.observeRows(today) }.map { rows ->
        HabitForest(
            trees = rows.map { ForestTree(it.id, it.habitId, TreeSpecies.valueOf(it.species), Instant.ofEpochMilli(it.plantedAt), it.wateringCount) },
            availableDrops = rows.associate { it.habitId to it.availableDrops },
        )
    }

    override suspend fun waterTree(treeId: Long): Unit = database.withTransaction {
        val tree = requireNotNull(forest.tree(treeId)) { "树木已不存在，请刷新林场" }
        requireNotNull(dao.getById(tree.habitId)) { "任务已被删除" }
        require(forest.waterCount(treeId) < 20) { "这棵树已长大，可以种下一棵" }
        val record = requireNotNull(forest.unusedRecord(tree.habitId, LocalDate.now(clock).toEpochDay())) { "该任务暂无可用水滴，完成打卡后再来浇水" }
        // recordId is a primary key, so even racing transactions cannot consume it twice.
        forest.insertWatering(HabitWateringEntity(record, treeId))
    }

    override suspend fun plantTree(habitId: Long): Unit = database.withTransaction {
        val habit = requireNotNull(dao.getById(habitId)) { "任务已被删除" }.toDomain()
        require(forest.trees(habitId).all { forest.waterCount(it.id) >= 20 }) { "先把现有树木养大，再种下一棵" }
        forest.insertTree(HabitTreeEntity(habitId = habitId, species = TreeSpecies.fromHabit(habit).name,
            plantedAt = Instant.now(clock).toEpochMilli()))
        Unit
    }
    override suspend fun deleteHabit(id: Long) = dao.deleteHabit(id)
    override suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean = database.withTransaction {
        val epochDay = date.toEpochDay()
        // Legacy records remain removable even after the start date/frequency changes.
        if (dao.recordForDay(habitId, epochDay) == null) {
            val habit = requireNotNull(dao.getById(habitId)) { "习惯不存在或已被删除" }.toDomain()
            require(HabitRules.isExpectedOn(habit, date)) { "此日期早于开始日期或不是指定打卡日" }
        }
        dao.toggleRecord(habitId, epochDay, Instant.now().toEpochMilli())
    }
}
private fun HabitEntity.toDomain() = Habit(id, name, iconKey, color, frequencyType, frequencyValue, customDaysOfWeek.orEmpty().split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(), LocalDate.ofEpochDay(startDate), note, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt))
private fun HabitRecordEntity.toDomain() = HabitRecord(id, habitId, LocalDate.ofEpochDay(date), Instant.ofEpochMilli(createdAt))
private fun Habit.toEntity(now: Instant) = HabitEntity(id, name.trim(), iconKey, color, frequencyType, frequencyValue, customDaysOfWeek.map(DayOfWeek::getValue).sorted().joinToString(",").ifEmpty { null }, startDate.toEpochDay(), note?.trim()?.ifEmpty { null }, if (id == 0L) now.toEpochMilli() else createdAt.toEpochMilli(), now.toEpochMilli())
