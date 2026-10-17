package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.model.HabitForest
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface HabitRepository {
    fun observeHabits(): Flow<List<Habit>>
    fun observeRecords(start: LocalDate, end: LocalDate): Flow<List<HabitRecord>>
    suspend fun saveHabit(habit: Habit): Long
    suspend fun deleteHabit(id: Long)
    suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean
    fun observeForest(): Flow<HabitForest> = kotlinx.coroutines.flow.flowOf(HabitForest())
    suspend fun waterTree(treeId: Long) { error("林场暂不可用") }
    suspend fun plantTree(habitId: Long) { error("林场暂不可用") }
}
