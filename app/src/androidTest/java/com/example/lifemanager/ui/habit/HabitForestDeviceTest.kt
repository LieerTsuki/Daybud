package com.example.lifemanager.ui.habit

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.domain.model.HabitForest
import com.example.lifemanager.domain.model.TreeSpecies
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dedicated synthetic-data AVD only. Cleanup deletes only this test's UUID-owned habit. */
@RunWith(AndroidJUnit4::class)
class HabitForestDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun model() = ViewModelProvider(compose.activity)[HabitViewModel::class.java]

    private fun waitFor(predicate: () -> Boolean) {
        compose.waitUntil(10_000) { compose.waitForIdle(); predicate() }
    }

    private fun ready() {
        waitFor { model().uiState.value.let { it.isAvailable && !it.isLoading && !it.isWorking } }
        compose.onNodeWithContentDescription("打卡入口").assertIsEnabled().performClick()
        waitFor { compose.onAllNodesWithContentDescription("添加习惯").fetchSemanticsNodes().size == 1 }
    }

    private fun taskAction(description: String) {
        compose.onNodeWithContentDescription("打卡任务")
            .performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun forestAction(description: String) {
        compose.onNodeWithContentDescription("打卡统计与林场")
            .performScrollToNode(hasContentDescription(description))
    }

    private fun screenshot(filename: String) {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(directory, filename).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Screenshot encoding failed: $filename" }
            }
        } finally { bitmap.recycle() }
    }

    /** Check a separate handle to the real production file, not only a ViewModel's cached state. */
    private fun storedForest(): HabitForest = runBlocking(Dispatchers.IO) {
        val database = LifeManagerDatabaseFactory.open(InstrumentationRegistry.getInstrumentation().targetContext)
        try { HabitRepositoryImpl(database).observeForest().first() }
        finally { database.close() }
    }

    private fun ownedIds(title: String): List<Long> = runBlocking(Dispatchers.IO) {
        val database = LifeManagerDatabaseFactory.open(InstrumentationRegistry.getInstrumentation().targetContext)
        try { database.habitDao().observeAll().first().filter { it.name == title }.map { it.id } }
        finally { database.close() }
    }

    private fun cleanup(title: String) {
        waitFor { model().uiState.value.let { it.isAvailable && !it.isWorking && !it.editor.isSaving } }
        compose.runOnIdle { model().closeEditor() }
        ownedIds(title).forEach { id ->
            compose.runOnIdle { model().deleteHabit(id) }
            waitFor { model().uiState.value.let { !it.isWorking && it.habits.none { habit -> habit.id == id } } }
        }
        assertTrue(ownedIds(title).isEmpty(), "Only the UUID-owned habit is removed")
    }

    @Test fun realHabitUiCreatesSelectedSpeciesAndRequiresManualWateringThenUndoRollsBack() {
        val title = "阅读-${UUID.randomUUID().toString().take(8)}"
        val waterAction = "浇水：$title，第1棵树"
        var failure: Throwable? = null
        try {
            ready()
            compose.onNodeWithContentDescription("添加习惯").performClick()
            waitFor { model().uiState.value.editor.isOpen }
            compose.onNode(hasText("名称") and hasSetTextAction()).performTextReplacement(title)
            TreeSpecies.entries.forEach { species ->
                compose.onNodeWithContentDescription("选择树种：${species.label}").assertExists()
            }
            compose.onNodeWithText("图标名称").assertDoesNotExist()
            compose.onNodeWithText("颜色").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择树种：樱花").performClick().assertIsSelected()
            screenshot("forest-editor.png")
            compose.onNodeWithText("保存").assertIsEnabled().performClick()
            waitFor {
                model().uiState.value.let { state ->
                    !state.editor.isOpen && state.habits.any { it.name == title } &&
                        state.forest.trees.any { tree -> state.habits.any { it.id == tree.habitId && it.name == title } }
                }
            }
            val habitId = ownedIds(title).single()
            val seed = storedForest().trees.single { it.habitId == habitId }
            assertEquals(TreeSpecies.CHERRY, seed.species)
            assertEquals(0, seed.wateringCount)
            assertFalse(seed.isMature)
            assertEquals(0, storedForest().availableDrops[habitId])

            taskAction("打卡：$title")
            waitFor {
                model().uiState.value.let { state ->
                    !state.isWorking && state.cards.any { it.habit.id == habitId && it.completedToday } &&
                        state.forest.availableDrops[habitId] == 1
                }
            }
            // A check-in grants water; it must not directly grow the tree.
            assertEquals(seed, storedForest().trees.single { it.habitId == habitId })
            assertEquals(1, storedForest().availableDrops[habitId])
            screenshot("forest-tasks.png")

            compose.onNodeWithText("统计").performClick()
            waitFor { model().uiState.value.selectedTab == HabitTab.STATS }
            compose.onNodeWithText("最近 53 周热力图").assertDoesNotExist()
            forestAction(waterAction)
            compose.onNodeWithContentDescription(waterAction).assertIsDisplayed().assertIsEnabled()
            compose.onNode(
                hasContentDescription("樱花，种子，已浇水0次") and
                    hasParent(hasAnyChild(hasContentDescription(waterAction))),
                useUnmergedTree = true,
            ).assertExists()
            compose.onNodeWithContentDescription("再种一棵：$title").assertDoesNotExist()
            compose.onNodeWithContentDescription(waterAction).performClick()
            waitFor {
                model().uiState.value.let { state ->
                    !state.isWorking && state.forest.trees.any { it.id == seed.id && it.wateringCount == 1 } &&
                        state.forest.availableDrops[habitId] == 0
                }
            }
            forestAction(waterAction)
            compose.onNodeWithContentDescription(waterAction).assertIsNotEnabled()
            compose.onNode(
                hasText("萌芽 · 1/20") and hasParent(hasAnyChild(hasContentDescription(waterAction))),
                useUnmergedTree = true,
            ).assertExists()
            val watered = storedForest()
            assertEquals(1, watered.trees.single { it.id == seed.id }.wateringCount)
            assertEquals(0, watered.availableDrops[habitId])
            screenshot("forest-watered.png")

            compose.onNodeWithText("任务").performClick()
            waitFor { model().uiState.value.selectedTab == HabitTab.TASKS }
            taskAction("撤销打卡：$title")
            waitFor {
                model().uiState.value.let { state ->
                    !state.isWorking && state.cards.any { it.habit.id == habitId && !it.completedToday } &&
                        state.forest.trees.any { it.id == seed.id && it.wateringCount == 0 }
                }
            }
            assertEquals(seed, storedForest().trees.single { it.habitId == habitId })
            assertEquals(0, storedForest().availableDrops[habitId])
            compose.onNodeWithText("统计").performClick()
            forestAction(waterAction)
            compose.onNodeWithContentDescription(waterAction).assertIsNotEnabled()
            compose.onNode(
                hasContentDescription("樱花，种子，已浇水0次") and
                    hasParent(hasAnyChild(hasContentDescription(waterAction))),
                useUnmergedTree = true,
            ).assertExists()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanup(title) }
            catch (cleanupError: Throwable) { failure?.addSuppressed(cleanupError) ?: throw cleanupError }
        }
    }
}
