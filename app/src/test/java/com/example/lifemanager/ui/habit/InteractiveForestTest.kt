package com.example.lifemanager.ui.habit

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InteractiveForestTest {
    @get:Rule val compose = createComposeRule()
    private val habits = listOf(Habit(id = 1, name = "阅读", startDate = LocalDate.now()), Habit(id = 2, name = "运动", startDate = LocalDate.now()))
    private fun tree(id: Long, habit: Long, water: Int = 0) =
        ForestTree(id, habit, if (habit == 1L) TreeSpecies.PINE else TreeSpecies.CHERRY, Instant.EPOCH, water)
    private fun state(trees: List<ForestTree> = listOf(tree(11, 1), tree(22, 2))) = HabitUiState(
        isAvailable = true, isLoading = false, generation = DataGeneration(1), habits = habits,
        forest = HabitForest(trees, mapOf(1L to 1, 2L to 0)),
    )
    private fun show(current: State<HabitUiState>, water: (Long) -> Unit = {}, plant: (Long) -> Unit = {}, large: Boolean = false) {
        compose.setContent {
            LifeManagerTheme(darkTheme = large) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                    LazyColumn { forestSection(current.value, water, plant) }
                }
            }
        }
    }

    @Test fun treesShareOneSceneAndWateringAppearsOnlyAfterSelectingTheCorrectTree() {
        val current = mutableStateOf(state())
        var watered: Long? = null
        show(current, water = { watered = it })
        compose.onNodeWithTag("shared_forest").assertExists()
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择树木：运动，第1棵树").assertIsDisplayed()
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择树木：运动，第1棵树").performClick()
        compose.onNodeWithContentDescription("浇水：运动，第1棵树").assertIsNotEnabled()
        compose.onNodeWithText("收获水滴后再来浇水吧").assertExists()
        compose.onNodeWithContentDescription("关闭树木详情").performClick()
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performClick()
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(11L, watered) }
    }

    @Test fun matureTreesStayInSceneAndPlantingUsesTheSelectedHabit() {
        val current = mutableStateOf(state(listOf(tree(11, 1, 20), tree(12, 1, 0), tree(22, 2, 20))))
        var planted: Long? = null
        show(current, plant = { planted = it })
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performClick()
        compose.onNodeWithContentDescription("再种一棵：阅读").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭树木详情").performClick()
        compose.onNodeWithContentDescription("选择树木：阅读，第2棵树").assertExists()
        compose.onNodeWithContentDescription("选择树木：运动，第1棵树").performClick()
        compose.onNodeWithContentDescription("再种一棵：运动").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2L, planted) }
    }

    @Test fun maintenanceOrReplacementInvalidatesSelectionEvenWhenTreeIdsAreReused() {
        val current = mutableStateOf(state())
        show(current)
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performClick()
        compose.runOnIdle { current.value = current.value.copy(isMaintaining = true) }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(isMaintaining = false, generation = DataGeneration(2)) }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performClick()
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsEnabled()
    }

    @Test fun locatorReachesOffscreenTreesAndWateringKeepsTheirIdentity() {
        val trees = (1L..60L).map { tree(it, if (it == 60L) 2 else 1, 10) }
        val current = mutableStateOf(state(trees).copy(forest = HabitForest(trees, mapOf(1L to 0, 2L to 1))))
        var watered: Long? = null
        show(current, water = { watered = it })
        compose.onNodeWithContentDescription("查找树木").performClick()
        compose.onNodeWithTag("forest_locator").performScrollToNode(hasContentDescription("定位树木：运动，第1棵树"))
        compose.onNodeWithContentDescription("定位树木：运动，第1棵树").performClick()
        compose.onNodeWithContentDescription("浇水：运动，第1棵树").performClick()
        compose.runOnIdle { assertEquals(60L, watered) }
        compose.onNodeWithContentDescription("关闭树木详情").performClick()
        compose.onNodeWithContentDescription("复位森林视角").performClick()
        compose.onNodeWithContentDescription("查找树木").performClick()
        compose.onNodeWithTag("forest_locator").performScrollToNode(hasContentDescription("定位树木：运动，第1棵树"))
        compose.onNodeWithContentDescription("定位树木：运动，第1棵树").performClick()
        compose.onNodeWithContentDescription("关闭树木详情").performClick()
        compose.onNodeWithContentDescription("选择树木：运动，第1棵树").assertIsDisplayed()
    }

    @Test fun zoomAndPanKeepTreesSelectableAndResetRestoresViewInDarkLargeText() {
        val current = mutableStateOf(state())
        show(current, large = true)
        compose.onNodeWithContentDescription("放大森林").performClick()
        compose.onNodeWithTag("shared_forest").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("复位森林视角").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("阅读", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        layouts.forEach { assertFalse(it.didOverflowHeight, "Task labels must have room for enlarged text") }
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsEnabled()
    }

    @Test fun gestureCameraUsesTheReplacementGenerationWithoutADisabledFrame() {
        val current = mutableStateOf(state())
        show(current)
        val original = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("shared_forest").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * .35f, height * .5f), androidx.compose.ui.geometry.Offset(width * .65f, height * .5f),
                androidx.compose.ui.geometry.Offset(width * .1f, height * .5f), androidx.compose.ui.geometry.Offset(width * .9f, height * .5f))
        }
        assertNotEquals(original, compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription], "Initial pinch must work before testing replacement")
        compose.onNodeWithContentDescription("复位森林视角").performClick()
        compose.runOnIdle { current.value = current.value.copy(generation = DataGeneration(2)) }
        val before = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("shared_forest").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * .35f, height * .5f), androidx.compose.ui.geometry.Offset(width * .65f, height * .5f),
                androidx.compose.ui.geometry.Offset(width * .1f, height * .5f), androidx.compose.ui.geometry.Offset(width * .9f, height * .5f))
        }
        val after = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(before, after)
    }

    @Test fun pinchStartingOnTwoTreesWinsOverTheirClickHandlersAndDragDoesNotOpenDetails() {
        val current = mutableStateOf(state())
        show(current)
        val scene = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").fetchSemanticsNode().boundsInRoot.center - scene.topLeft
        val second = compose.onNodeWithContentDescription("选择树木：运动，第1棵树").fetchSemanticsNode().boundsInRoot.center - scene.topLeft
        val before = compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("shared_forest").performTouchInput {
            pinch(first, second, first - androidx.compose.ui.geometry.Offset(25f, 0f), second + androidx.compose.ui.geometry.Offset(25f, 0f))
        }
        assertNotEquals(before, compose.onNodeWithTag("shared_forest").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertDoesNotExist()
        compose.onNodeWithContentDescription("复位森林视角").performClick()
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertDoesNotExist()
        compose.onNodeWithContentDescription("复位森林视角").performClick()
        compose.onNodeWithContentDescription("选择树木：阅读，第1棵树").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("浇水：阅读，第1棵树").assertIsEnabled()
    }
}
