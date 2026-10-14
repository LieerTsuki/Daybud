package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanOverviewWorkspaceTest {
    @get:Rule val compose=createComposeRule()
    private val f=EditorTestWorld(Dispatchers.IO)
    private val access=GenerationAccess(f.generations,f.coordinator)
    private val clock=Clock.fixed(f.now,ZoneId.systemDefault())
    private val day=LocalDate.now(clock)
    private val list=PlanViewModel(f.repo,f.cases,access,Dispatchers.IO,clock)
    private val calendar=PlanCalendarViewModel(f.repo,access,Dispatchers.IO,clock)
    private val stats=PlanStatisticsViewModel(f.repo,access,Dispatchers.IO,clock)
    @After fun cleanup() { listOf(list,calendar,stats,f.model).forEach { it.viewModelScope.cancel() } }
    private fun waitFor(predicate:()->Boolean) { compose.waitUntil(5_000) { compose.waitForIdle();predicate() } }
    private fun scroll(matcher:SemanticsMatcher) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher) }
    private fun show() {
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanWorkspace(list,f.model,calendar,stats) } } }
        waitFor { list.uiState.value.isAvailable && calendar.uiState.value.isAvailable && stats.uiState.value.isAvailable && f.model.uiState.value.isAvailable }
    }
    @Test fun `calendar opens actual same identity and detail return preserves chosen day view`() {
        f.repo.rows.value=listOf(Plan(id=7,title="真实日历计划",timeZone=clock.zone.id,createdAt=f.now,updatedAt=f.now,timeMode=PlanTimeMode.DEADLINE,dueDate=day.plusDays(1)))
        show();scroll(hasText("日历"));compose.onNodeWithText("日历",useUnmergedTree=true).performClick()
        // The page triggers a fresh admitted read on appearance; act through its enabled control.
        waitFor { !calendar.uiState.value.isLoading && calendar.uiState.value.isAvailable }
        compose.onNodeWithText("日视图").assertIsEnabled().performClick()
        waitFor { !calendar.uiState.value.isLoading && calendar.uiState.value.mode==PlanCalendarMode.DAY }
        compose.onNodeWithContentDescription("下个日历窗口").performClick()
        waitFor { !calendar.uiState.value.isLoading && calendar.uiState.value.selectedDate==day.plusDays(1) }
        scroll(hasText("真实日历计划"));compose.onNodeWithText("真实日历计划").performClick()
        waitFor { f.model.uiState.value.detail?.id==7L }
        scroll(hasText("返回计划"));compose.onNodeWithText("返回计划").performClick()
        waitFor { f.model.uiState.value.pane==PlanPane.LIST }
        scroll(hasText("日历手账"));compose.onNodeWithText("日历手账").assertExists()
        assertEquals(day.plusDays(1),calendar.uiState.value.selectedDate);assertEquals(PlanCalendarMode.DAY,calendar.uiState.value.mode)
    }
    @Test fun `workspace statistics ignores hidden list filter and list return preserves it`() {
        f.repo.rows.value=listOf(Plan(id=7,title="长期目标",timeZone=clock.zone.id,createdAt=f.now,updatedAt=f.now))
        show();compose.runOnIdle { list.onFilterChanged(PlanFilter(query="无结果",category=PlanCategory.TODO),f.token) }
        waitFor { !list.uiState.value.isLoading }
        scroll(hasText("统计"));compose.onNodeWithText("统计").performClick()
        scroll(hasText("全库完成率：0%（0 / 1）"));compose.onNodeWithText("全库完成率：0%（0 / 1）").assertExists()
        scroll(hasText("列表"));compose.onNodeWithText("列表").performClick()
        assertEquals("无结果",list.uiState.value.filter.query);assertEquals(PlanCategory.TODO,list.uiState.value.filter.category)
    }
    @Test fun `editor hides section shortcuts and keeps dirty draft on cancel close`() {
        show();scroll(hasText("新建计划"));compose.onNodeWithText("新建计划").performClick()
        waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("必须保留的草稿")
        compose.onNodeWithText("日历",useUnmergedTree=true).assertDoesNotExist();compose.onNodeWithText("统计").assertDoesNotExist()
        scroll(hasText("返回计划"));compose.onNodeWithText("返回计划").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("必须保留的草稿",f.model.uiState.value.draft!!.title);assertEquals(0,f.repo.writes)
    }
    @Test fun `retained calendar open cannot select reused identity after replacement`() {
        f.repo.rows.value=listOf(Plan(id=7,title="旧日历记录",timeZone=clock.zone.id,createdAt=f.now,updatedAt=f.now,timeMode=PlanTimeMode.DEADLINE,dueDate=day))
        show();scroll(hasText("日历"));compose.onNodeWithText("日历").performClick()
        scroll(hasText("旧日历记录"));val old=compose.onNodeWithText("旧日历记录").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance=false
        try {
            runBlocking(Dispatchers.IO) { f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
                f.generations.commit(session.generation) { f.repo.rows.value=listOf(f.repo.rows.value.single().copy(title="替换日历记录")) }
            } } }
            waitFor { calendar.uiState.value.generation==DataGeneration(5) && calendar.uiState.value.isAvailable && f.model.uiState.value.stamp.generation==DataGeneration(5) }
            compose.runOnIdle { old() };assertEquals(PlanPane.LIST,f.model.uiState.value.pane);assertNull(f.model.uiState.value.detail)
        } finally { compose.mainClock.autoAdvance=true }
    }
}
