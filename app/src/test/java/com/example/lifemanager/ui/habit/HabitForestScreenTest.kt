package com.example.lifemanager.ui.habit

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.ui.common.testGenerationAccess
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HabitForestScreenTest {
    @get:Rule val compose = createComposeRule()
    private var model: HabitViewModel? = null
    @After fun cleanup() { model?.viewModelScope?.cancel() }

    @Test fun editorOffersVisualSpeciesInsteadOfLegacyIconAndColorFields() {
        show()
        compose.onNodeWithContentDescription("添加习惯").performClick()
        listOf("松树", "银杏", "樱花", "枫树", "橡树", "白桦").forEach {
            compose.onNodeWithContentDescription("选择树种：$it").assertExists()
        }
        compose.onNodeWithContentDescription("选择树种：樱花").performClick()
        compose.onNodeWithText("图标名称").assertDoesNotExist()
        compose.onNodeWithText("颜色").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择树种：樱花").assertIsSelected()
    }

    @Test fun forestWateringSpendsOnlyItsTasksDropInDarkLargeText() {
        val repository = show(dark = true, largeText = true)
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithContentDescription("打卡统计与林场").performScrollToNode(hasContentDescription("浇水：阅读，第1棵树"))
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsEnabled().performClick()
        compose.waitUntil(5000) { repository.forest.value.availableDrops[1L] == 0 }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsNotEnabled()
        compose.onNodeWithContentDescription("打卡统计与林场").performScrollToNode(hasContentDescription("浇水：运动，第1棵树"))
        compose.onNodeWithContentDescription("浇水：运动，第1棵树").assertIsNotEnabled()
        compose.onNodeWithText("最近 53 周热力图").assertDoesNotExist()
    }

    private fun show(dark: Boolean = false, largeText: Boolean = false): ScreenForestRepository {
        val repository = ScreenForestRepository()
        val viewModel = HabitViewModel(repository, Dispatchers.IO, testGenerationAccess())
        model = viewModel
        compose.setContent {
            LifeManagerTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (largeText) 1.5f else 1f)) {
                    HabitScreen(viewModel)
                }
            }
        }
        compose.waitUntil(5000) { compose.waitForIdle(); viewModel.uiState.value.cards.size == 2 }
        return repository
    }

    private class ScreenForestRepository : HabitRepository {
        val habits = MutableStateFlow(listOf(Habit(id = 1, name = "阅读", startDate = LocalDate.now()), Habit(id = 2, name = "运动", startDate = LocalDate.now())))
        val forest = MutableStateFlow(HabitForest(listOf(
            ForestTree(11, 1, TreeSpecies.PINE, Instant.EPOCH, 0),
            ForestTree(22, 2, TreeSpecies.CHERRY, Instant.EPOCH, 0)), mapOf(1L to 1, 2L to 0)))
        override fun observeHabits() = habits
        override fun observeRecords(start: LocalDate, end: LocalDate) = MutableStateFlow(emptyList<HabitRecord>())
        override fun observeForest() = forest
        override suspend fun saveHabit(habit: Habit) = 3L
        override suspend fun deleteHabit(id: Long) = Unit
        override suspend fun toggleRecord(habitId: Long, date: LocalDate) = true
        override suspend fun plantTree(habitId: Long) = Unit
        override suspend fun waterTree(treeId: Long) {
            val tree = forest.value.trees.single { it.id == treeId }
            check(forest.value.availableDrops[tree.habitId] == 1)
            forest.value = forest.value.copy(trees = forest.value.trees.map { if (it.id == treeId) it.copy(wateringCount = 1) else it },
                availableDrops = forest.value.availableDrops + (tree.habitId to 0))
        }
    }
}
