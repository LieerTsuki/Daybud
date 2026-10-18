package com.example.lifemanager.ui.habit

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.example.lifemanager.MainActivity
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.domain.model.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs only on the controller's dedicated synthetic-data AVD. Deletes only IDs created here. */
@RunWith(AndroidJUnit4::class)
class InteractiveForestDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private fun model() = ViewModelProvider(activity)[HabitViewModel::class.java]
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) { compose.waitForIdle(); condition() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun sixTasksShareLandscapeAndRealTapsZoomWaterAndReplantPersistCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = LifeManagerDatabaseFactory.open(context)
        val repository = HabitRepositoryImpl(database)
        val ids = mutableListOf<Long>()
        var scenario: ActivityScenario<MainActivity>? = null
        val names = listOf("阅读时光", "晨间运动", "早点休息", "记得喝水", "安静冥想", "学习新知")
        try {
            runBlocking(Dispatchers.IO) {
                names.forEachIndexed { index, name ->
                    val id = repository.saveHabit(Habit(name = name, startDate = LocalDate.now().minusDays(60), iconKey = TreeSpecies.entries[index].key))
                    ids += id
                    val count = listOf(20, 12, 8, 5, 1, 0)[index]
                    repeat(count + 2) { repository.toggleRecord(id, LocalDate.now().minusDays(it.toLong())) }
                    val treeId = repository.observeForest().first().trees.single { it.habitId == id }.id
                    repeat(count) { repository.waterTree(treeId) }
                }
            }
            // Seed before starting the Activity: a separate Room handle does not publish
            // invalidations into the production singleton's existing observers.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity = it }
            waitFor { model().uiState.value.isAvailable && !model().uiState.value.isLoading }
            compose.onNodeWithContentDescription("打卡入口").performClick()
            compose.onNodeWithText("统计").performClick()
            waitFor { model().uiState.value.forest.trees.count { it.habitId in ids } == 6 }
            compose.onNodeWithTag("shared_forest").assertIsDisplayed()
            names.forEach { compose.onNodeWithContentDescription("选择树木：$it，第1棵树").assertExists() }
            compose.onNodeWithContentDescription("浇水：学习新知，第1棵树").assertDoesNotExist()
            screenshot("interactive-forest-overview.png")
            // Physical pointer taps verify hit targets, not only semantics callbacks.
            compose.onNodeWithContentDescription("选择树木：学习新知，第1棵树").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("浇水：学习新知，第1棵树").assertIsEnabled().performClick()
            waitFor { model().uiState.value.forest.trees.any { it.habitId == ids.last() && it.wateringCount == 1 } }
            val watered = runBlocking(Dispatchers.IO) { repository.observeForest().first() }
            assertEquals(1, watered.trees.single { it.habitId == ids.last() }.wateringCount)
            assertEquals(1, watered.availableDrops[ids.last()])
            assertEquals(2, watered.availableDrops[ids.first()])
            screenshot("interactive-forest-watering.png")
            compose.onNodeWithContentDescription("关闭树木详情").performClick()
            val beforeZoom = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            compose.onNodeWithTag("shared_forest").performTouchInput {
                pinch(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f), centerRight - androidx.compose.ui.geometry.Offset(40f, 0f),
                    centerLeft, centerRight)
            }
            assertTrue(beforeZoom != compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription], "Physical pinch must zoom the forest")
            compose.onNodeWithTag("shared_forest").performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription("复位森林视角").performClick()
            compose.onNodeWithContentDescription("选择树木：阅读时光，第1棵树").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("再种一棵：阅读时光").performClick()
            waitFor { model().uiState.value.forest.trees.count { it.habitId == ids.first() } == 2 }
            compose.onNodeWithContentDescription("关闭树木详情").performClick()
            val replanted = runBlocking(Dispatchers.IO) { repository.observeForest().first() }.trees.filter { it.habitId == ids.first() }
            assertEquals(listOf(0, 20), replanted.map { it.wateringCount }.sorted())
            compose.onNodeWithContentDescription("选择树木：阅读时光，第1棵树").assertExists()
            compose.onNodeWithContentDescription("选择树木：阅读时光，第2棵树").assertExists()
            screenshot("interactive-forest-replanted.png")
        } finally {
            scenario?.close()
            runBlocking(Dispatchers.IO) { ids.forEach { repository.deleteHabit(it) } }
            assertTrue(runBlocking(Dispatchers.IO) { repository.observeHabits().first().none { it.id in ids } })
            database.close()
        }
    }
}
