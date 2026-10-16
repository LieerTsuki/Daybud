package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
class InlineMonthWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = EditorTestWorld(Dispatchers.IO)
    private val access = GenerationAccess(fixture.generations, fixture.coordinator)
    private val clock = Clock.fixed(fixture.now, ZoneId.systemDefault())
    private val list = PlanViewModel(fixture.repo, fixture.cases, access, Dispatchers.IO, clock)
    private val calendar = PlanCalendarViewModel(fixture.repo, access, Dispatchers.IO, clock)

    @After fun cleanup() {
        listOf(list, calendar, fixture.model).forEach { it.viewModelScope.cancel() }
    }

    @Test fun `board starts with a collapsed month calendar and has no section tabs`() {
        compose.setContent {
            LifeManagerTheme {
                Box(Modifier.size(320.dp, 640.dp)) { PlanWorkspace(list, fixture.model, calendar) }
            }
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            list.uiState.value.isAvailable && calendar.uiState.value.isAvailable && fixture.model.uiState.value.isAvailable
        }
        compose.onNodeWithContentDescription("展开月历").assertExists().assertIsEnabled()
        compose.onNodeWithText("日历", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("统计", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("周视图", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("日视图", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("plan-calendar-date-${calendar.uiState.value.selectedDate}").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加计划：重要且紧急").assertExists()
    }
}
