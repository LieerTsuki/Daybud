package com.example.lifemanager.ui.habit

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HabitForestViewModelTest {
    @Test fun forestOnlyEmissionUpdatesTaskGrowthAndMatchingDrops() = runTest { scenario { model, repo, _, _ ->
        repo.forest.value = HabitForest(listOf(tree(11, 1, 5), tree(22, 2, 0)), mapOf(1L to 2, 2L to 0))
        advanceUntilIdle()
        assertEquals(5, model.uiState.value.cards.first { it.habit.id == 1L }.tree?.wateringCount)
        assertEquals(2, model.uiState.value.forest.availableDrops[1L])
        model.waterTree(22)
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.forest.trees.first { it.id == 22L }.wateringCount)
        assertEquals(2, model.uiState.value.forest.availableDrops[1L])
    } }

    @Test fun duplicateWateringIsRejectedBeforeDispatchAndFailureKeepsGrowth() = runTest { scenario { model, repo, _, _ ->
        repo.release = CompletableDeferred()
        model.waterTree(11)
        model.waterTree(11)
        runCurrent()
        assertTrue(model.uiState.value.isWorking)
        repo.release!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.forest.trees.single { it.id == 11L }.wateringCount)
        assertEquals(1, model.uiState.value.forest.availableDrops[1L])
        repo.failure = true
        model.waterTree(11)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.forest.trees.single { it.id == 11L }.wateringCount)
        assertNotNull(model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isWorking)
    } }

    @Test fun oldForestCallbacksCannotWaterOrPlantAfterReplacement() = runTest { scenario { model, repo, store, coordinator ->
        val old = model.uiState.value.generation
        coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
        advanceUntilIdle()
        model.waterTree(11, old)
        model.plantTree(1, old)
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.forest.trees.single { it.id == 11L }.wateringCount)
        assertEquals(2, model.uiState.value.forest.trees.size)
    } }

    @Test fun maintenanceDoesNotQueueWateringAndImmatureTreePreventsReplanting() = runTest { scenario { model, _, _, coordinator ->
        coordinator.withSession {
            runCurrent()
            model.waterTree(11)
        }
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.forest.trees.single { it.id == 11L }.wateringCount)
        model.plantTree(1)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.forest.trees.count { it.habitId == 1L })
        assertFalse(model.uiState.value.isWorking)
    } }

    @Test fun matureForestCanPlantAndSpeciesSelectionPreservesHiddenColor() = runTest { scenario { model, repo, _, _ ->
        repo.forest.value = HabitForest(listOf(tree(11, 1, 20)), mapOf(1L to 2))
        advanceUntilIdle()
        model.plantTree(1)
        advanceUntilIdle()
        assertEquals(2, model.uiState.value.forest.trees.size)
        model.openEditor(repo.habits.value.first())
        model.onSpeciesChanged(TreeSpecies.CHERRY)
        model.saveHabit()
        advanceUntilIdle()
        assertEquals("tree:CHERRY", repo.habits.value.first().iconKey)
        assertEquals(0xFF123456.toInt(), repo.habits.value.first().color)
    } }

    @Test fun existingHabitWithoutATreeCanPlantItsFirstTree() = runTest { scenario { model, repo, _, _ ->
        repo.forest.value = HabitForest(emptyList(), mapOf(1L to 2))
        advanceUntilIdle()
        model.plantTree(1)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.forest.trees.count { it.habitId == 1L })
    } }

    @Test fun ordinaryEditPreservesLegacyIconAndHiddenColor() = runTest { scenario { model, repo, _, _ ->
        model.openEditor(repo.habits.value.first())
        model.onNameChanged("阅读二十分钟")
        model.saveHabit()
        advanceUntilIdle()
        assertEquals("Check", repo.habits.value.first().iconKey)
        assertEquals(0xFF123456.toInt(), repo.habits.value.first().color)
        assertEquals("阅读二十分钟", repo.habits.value.first().name)
    } }

    private suspend fun TestScope.scenario(body: suspend TestScope.(HabitViewModel, ForestRepository, TestGenerations, MaintenanceCoordinator) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(store)
        val repo = ForestRepository()
        val model = HabitViewModel(repo, dispatcher, GenerationAccess(store, coordinator))
        try { advanceUntilIdle(); body(model, repo, store, coordinator) }
        finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private class ForestRepository : HabitRepository {
        val habits = MutableStateFlow(listOf(Habit(id = 1, name = "阅读", color = 0xFF123456.toInt(), startDate = LocalDate.now()), Habit(id = 2, name = "运动", startDate = LocalDate.now())))
        val forest = MutableStateFlow(HabitForest(listOf(tree(11, 1, 0), tree(22, 2, 0)), mapOf(1L to 2, 2L to 0)))
        var release: CompletableDeferred<Unit>? = null
        var failure = false
        override fun observeHabits() = habits
        override fun observeRecords(start: LocalDate, end: LocalDate) = MutableStateFlow(emptyList<HabitRecord>())
        override fun observeForest() = forest
        override suspend fun saveHabit(habit: Habit): Long { habits.value = habits.value.map { if (it.id == habit.id) habit else it }; return habit.id }
        override suspend fun deleteHabit(id: Long) = Unit
        override suspend fun toggleRecord(habitId: Long, date: LocalDate) = true
        override suspend fun waterTree(treeId: Long) {
            release?.await()
            check(!failure) { "浇水暂时失败" }
            val tree = forest.value.trees.single { it.id == treeId }
            val drops = forest.value.availableDrops[tree.habitId] ?: 0
            check(drops > 0 && !tree.isMature)
            forest.value = forest.value.copy(trees = forest.value.trees.map { if (it.id == treeId) it.copy(wateringCount = it.wateringCount + 1) else it },
                availableDrops = forest.value.availableDrops + (tree.habitId to drops - 1))
        }
        override suspend fun plantTree(habitId: Long) {
            check(forest.value.trees.filter { it.habitId == habitId }.all { it.isMature })
            forest.value = forest.value.copy(trees = forest.value.trees + tree(33, habitId, 0))
        }
    }
    companion object {
        private fun tree(id: Long, habit: Long, count: Int) = ForestTree(id, habit, TreeSpecies.PINE, Instant.EPOCH, count)
    }
}
