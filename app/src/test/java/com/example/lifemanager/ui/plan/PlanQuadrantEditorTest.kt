package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.ui.theme.LifeManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanQuadrantEditorTest {
    @get:Rule val compose = createComposeRule()
    private val world = EditorTestWorld(Dispatchers.IO)

    @After fun cleanup() { world.model.viewModelScope.cancel() }

    private fun show(detail: Boolean = false, initialPriority: TodoPriority? = null) {
        compose.setContent {
            LifeManagerTheme {
                val state by world.model.uiState.collectAsState()
                Box(Modifier.size(320.dp, 640.dp)) {
                    if (detail) PlanDetail(state, world.model) else PlanEditor(state, world.model)
                }
            }
        }
        waitFor { it.isAvailable }
        compose.runOnIdle {
            if (detail) world.model.open(7, world.token)
            else if (initialPriority != null) world.model.openNewInQuadrant(initialPriority, world.token)
            else world.model.openNew(world.token)
        }
        waitFor { it.isAvailable && it.pane == if (detail) PlanPane.DETAIL else PlanPane.EDIT }
    }

    private fun waitFor(predicate: (PlanEditorState) -> Boolean) {
        compose.waitUntil(5_000) { compose.waitForIdle(); predicate(world.model.uiState.value) }
    }

    @Test fun `editor exposes manual quadrant choice and persists the selected quadrant`() {
        show()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("象限"))
        compose.onNodeWithText("重要且紧急").performClick()
        assertEquals(TodoPriority.HIGH, world.model.uiState.value.draft!!.priority)
        compose.onNodeWithText("优先级").assertDoesNotExist()
        compose.runOnIdle {
            world.model.change(PlanField.TITLE, "手动象限计划", world.model.uiState.value.stamp)
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("保存计划"))
        compose.onNodeWithText("保存计划").performClick()
        waitFor { it.detail != null && !it.isBusy }
        assertEquals(TodoPriority.HIGH, world.repo.rows.value.single().priority)
    }

    @Test fun `detail names the stored quadrant`() {
        world.repo.rows.value = listOf(Plan(id = 7, title = "象限详情", priority = TodoPriority.MEDIUM,
            timeZone = "Asia/Shanghai", createdAt = world.now, updatedAt = world.now))
        show(detail = true)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("重要不紧急", substring = true))
        compose.onNodeWithText("重要不紧急", substring = true).assertExists()
        compose.onNodeWithText("中优先级", substring = true).assertDoesNotExist()
    }

    @Test fun `each quadrant add preset is stored on the newly created plan`() {
        show(initialPriority = TodoPriority.HIGH)
        listOf(TodoPriority.HIGH, TodoPriority.MEDIUM, TodoPriority.LOW, TodoPriority.NONE).forEachIndexed { index, priority ->
            if (index > 0) {
                compose.runOnIdle { world.model.openNewInQuadrant(priority, world.token) }
                waitFor { it.isAvailable && it.pane == PlanPane.EDIT }
            }
            assertEquals(priority, world.model.uiState.value.draft!!.priority)
            compose.runOnIdle {
                world.model.change(PlanField.TITLE, "新计划 $index", world.model.uiState.value.stamp)
                world.model.save(world.model.uiState.value.stamp)
            }
            waitFor { it.detail != null && !it.isBusy }
            assertEquals(priority, world.repo.rows.value.single { it.title == "新计划 $index" }.priority)
        }
        assertEquals(4, world.repo.writes)
    }

    @Test fun `blank preset is clean and can close without discard confirmation`() {
        show(initialPriority = TodoPriority.HIGH)
        compose.runOnIdle { world.model.requestClose(world.model.uiState.value.stamp) }
        waitFor { it.pane == PlanPane.LIST }
        assertFalse(world.model.uiState.value.confirmClose)
        assertEquals(0, world.repo.writes)
    }

    @Test fun `discard confirmation retains the requested quadrant while cancelling keeps the old draft`() {
        show(initialPriority = TodoPriority.HIGH)
        compose.runOnIdle {
            world.model.change(PlanField.TITLE, "保留的重要计划", world.model.uiState.value.stamp)
            world.model.openNewInQuadrant(TodoPriority.LOW, world.token)
        }
        assertTrue(world.model.uiState.value.confirmClose)
        val cancelledConfirmation = world.model.uiState.value.stamp
        compose.runOnIdle {
            world.model.cancelClose(cancelledConfirmation)
            assertEquals("保留的重要计划", world.model.uiState.value.draft!!.title)
            assertEquals(TodoPriority.HIGH, world.model.uiState.value.draft!!.priority)
            world.model.openNewInQuadrant(TodoPriority.MEDIUM, world.token)
            world.model.confirmClose(cancelledConfirmation)
            assertTrue(world.model.uiState.value.confirmClose)
            assertEquals("保留的重要计划", world.model.uiState.value.draft!!.title)
            world.model.confirmClose(world.model.uiState.value.stamp)
        }
        waitFor { it.isAvailable && it.pane == PlanPane.EDIT && !it.confirmClose && it.draft?.title == "" }
        assertEquals(TodoPriority.MEDIUM, world.model.uiState.value.draft!!.priority)
        assertEquals(0, world.repo.writes)
    }

    @Test fun `old rendered generation cannot open a quadrant after data replacement`() {
        show(initialPriority = TodoPriority.HIGH)
        runBlocking { world.generations.commit(world.token) {} }
        waitFor { it.stamp.generation != world.token && it.pane == PlanPane.LIST && it.isAvailable }
        compose.runOnIdle { world.model.openNewInQuadrant(TodoPriority.LOW, world.token) }
        assertEquals(PlanPane.LIST, world.model.uiState.value.pane)
        compose.runOnIdle {
            world.model.openNewInQuadrant(TodoPriority.MEDIUM, world.model.uiState.value.stamp.generation)
        }
        waitFor { it.pane == PlanPane.EDIT && it.isAvailable }
        assertEquals(TodoPriority.MEDIUM, world.model.uiState.value.draft!!.priority)
        assertEquals(0, world.repo.writes)
    }

    @Test fun `adding changing and removing a deadline preserves the manually selected quadrant`() {
        show(initialPriority = TodoPriority.LOW)
        compose.runOnIdle {
            world.model.change(PlanField.TITLE, "象限不会自动变化", world.model.uiState.value.stamp)
            world.model.setMode(PlanTimeMode.DEADLINE, world.model.uiState.value.stamp)
            world.model.setDateOnly(true, world.model.uiState.value.stamp)
            world.model.change(PlanField.DUE_DATE, "2026-09-01", world.model.uiState.value.stamp)
            world.model.save(world.model.uiState.value.stamp)
        }
        waitFor { it.detail != null && !it.isBusy }
        val id = world.repo.rows.value.single().id
        assertEquals(TodoPriority.LOW, world.repo.rows.value.single().priority)
        compose.runOnIdle {
            world.model.beginEdit(world.model.uiState.value.stamp)
            world.model.change(PlanField.DUE_DATE, "2036-10-20", world.model.uiState.value.stamp)
            world.model.save(world.model.uiState.value.stamp)
        }
        waitFor { it.detail != null && !it.isBusy }
        assertEquals(TodoPriority.LOW, world.repo.rows.value.single().priority)
        compose.runOnIdle {
            world.model.beginEdit(world.model.uiState.value.stamp)
            world.model.setMode(PlanTimeMode.NONE, world.model.uiState.value.stamp)
            world.model.confirmMode(world.model.uiState.value.stamp)
            world.model.save(world.model.uiState.value.stamp)
        }
        waitFor { it.detail != null && !it.isBusy }
        val saved = world.repo.rows.value.single()
        assertEquals(id, saved.id)
        assertEquals(PlanTimeMode.NONE, saved.timeMode)
        assertEquals(TodoPriority.LOW, saved.priority)
    }
}
