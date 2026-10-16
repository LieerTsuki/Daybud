package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.TodoPriority
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
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanOverviewWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val f = EditorTestWorld(Dispatchers.IO)
    private val access = GenerationAccess(f.generations, f.coordinator)
    private val clock = Clock.fixed(f.now, ZoneId.systemDefault())
    private val day = LocalDate.now(clock)
    private val list = PlanViewModel(f.repo, f.cases, access, Dispatchers.IO, clock)
    private val calendar = PlanCalendarViewModel(f.repo, access, Dispatchers.IO, clock)

    @After fun cleanup() { listOf(list, calendar, f.model).forEach { it.viewModelScope.cancel() } }
    private fun waitFor(predicate: () -> Boolean) { compose.waitUntil(5_000) { compose.waitForIdle(); predicate() } }
    private fun scroll(matcher: SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }
    private fun show() {
        compose.setContent {
            LifeManagerTheme { Box(Modifier.size(320.dp, 640.dp)) { PlanWorkspace(list, f.model, calendar) } }
        }
        waitFor { list.uiState.value.isAvailable && calendar.uiState.value.isAvailable && f.model.uiState.value.isAvailable }
    }
    private fun expandMonth() {
        scroll(hasContentDescription("展开月历"))
        compose.onNodeWithContentDescription("展开月历").assertIsEnabled().performClick()
        waitFor { calendar.uiState.value.let { it.isAvailable && !it.isLoading } }
    }
    private fun doubleClickDate(date: LocalDate) {
        scroll(hasTestTag("plan-calendar-date-$date"))
        compose.onNodeWithTag("plan-calendar-date-$date").performTouchInput { doubleClick() }
        compose.onNodeWithTag("plan-day-details").assertExists()
    }

    @Test fun `double click opens actual identity and return preserves expanded chosen month`() {
        val target = day.plusMonths(1)
        f.repo.rows.value = listOf(Plan(id = 7, title = "真实日历计划", timeZone = clock.zone.id,
            createdAt = f.now, updatedAt = f.now, timeMode = PlanTimeMode.DEADLINE, dueDate = target))
        show()
        expandMonth()
        compose.onNodeWithContentDescription("下个月").performClick()
        waitFor { !calendar.uiState.value.isLoading && calendar.uiState.value.selectedDate == target }
        doubleClickDate(target)
        compose.onNodeWithTag("plan-calendar-entry-7").performClick()
        waitFor { f.model.uiState.value.detail?.id == 7L }
        compose.onNodeWithContentDescription("展开月历").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起月历").assertDoesNotExist()
        scroll(hasText("返回计划"))
        compose.onNodeWithText("返回计划").performClick()
        waitFor { f.model.uiState.value.pane == PlanPane.LIST }
        scroll(hasContentDescription("收起月历"))
        compose.onNodeWithContentDescription("收起月历").assertExists()
        assertEquals(target, calendar.uiState.value.selectedDate)
        assertEquals(PlanCalendarMode.MONTH, calendar.uiState.value.mode)
        scroll(hasTestTag("plan-calendar-date-$target"))
        compose.onNodeWithTag("plan-calendar-date-$target").assertExists()
    }

    @Test fun `month expands and collapses without changing search and has no obsolete sections`() {
        f.repo.rows.value = listOf(Plan(id = 7, title = "长期目标", timeZone = clock.zone.id, createdAt = f.now, updatedAt = f.now))
        show()
        compose.runOnIdle { list.onFilterChanged(PlanFilter(query = "无结果", status = PlanStatus.ALL), f.token) }
        waitFor { !list.uiState.value.isLoading }
        expandMonth()
        scroll(hasTestTag("plan-calendar-date-$day"))
        compose.onNodeWithTag("plan-calendar-date-$day").assertExists()
        scroll(hasContentDescription("收起月历"))
        compose.onNodeWithContentDescription("收起月历").performClick()
        compose.onNodeWithTag("plan-calendar-date-$day").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开月历").assertExists()
        compose.onNodeWithText("日历", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("统计", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("周视图", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("日视图", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("无结果", list.uiState.value.filter.query)
    }

    @Test fun `editor hides month dropdown and keeps dirty draft on cancel close`() {
        show()
        scroll(hasContentDescription("添加计划：重要且紧急"))
        compose.onNodeWithContentDescription("添加计划：重要且紧急").performClick()
        waitFor { f.model.uiState.value.draft != null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("必须保留的草稿")
        compose.onNodeWithContentDescription("展开月历").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起月历").assertDoesNotExist()
        compose.onNodeWithText("统计").assertDoesNotExist()
        scroll(hasText("返回计划"))
        compose.onNodeWithText("返回计划").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("必须保留的草稿", f.model.uiState.value.draft!!.title)
        assertEquals(0, f.repo.writes)
    }

    @Test fun `retained day details action cannot select replacement generation`() {
        f.repo.rows.value = listOf(Plan(id = 7, title = "旧日历记录", timeZone = clock.zone.id,
            createdAt = f.now, updatedAt = f.now, timeMode = PlanTimeMode.DEADLINE, dueDate = day))
        show()
        expandMonth()
        waitFor { calendar.uiState.value.let { it.isAvailable && !it.isLoading && it.days[day].orEmpty().any { row -> row.plan.id == 7L } } }
        scroll(hasTestTag("plan-calendar-date-$day"))
        val old = compose.onNodeWithTag("plan-calendar-date-$day").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "查看当天全部计划" }.action
        compose.mainClock.autoAdvance = false
        try {
            runBlocking(Dispatchers.IO) {
                f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
                    f.generations.commit(session.generation) { f.repo.rows.value = listOf(f.repo.rows.value.single().copy(title = "替换日历记录")) }
                } }
            }
            waitFor { calendar.uiState.value.generation == DataGeneration(5) && calendar.uiState.value.isAvailable && f.model.uiState.value.stamp.generation == DataGeneration(5) }
            compose.runOnIdle { old() }
            compose.onNodeWithTag("plan-day-details").assertDoesNotExist()
            assertEquals(PlanPane.LIST, f.model.uiState.value.pane)
            assertNull(f.model.uiState.value.detail)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun `day dialog contains every dated task and full notes but excludes undated plans`() {
        val title = "完整任务名称不丢失".repeat(8)
        val note = "当天任务完整备注：需要带好资料并确认所有细节。".repeat(6)
        f.repo.rows.value = (1L..6L).map { id ->
            Plan(id = id, title = if (id == 1L) title else "当天计划$id", description = note + id,
                priority = TodoPriority.entries[(id % 4).toInt()], timeZone = clock.zone.id,
                createdAt = f.now, updatedAt = f.now, timeMode = PlanTimeMode.DEADLINE, dueDate = day,
                isCompleted = id == 6L, completedAt = if (id == 6L) f.now else null)
        } + Plan(id = 8, title = "没有日期不归当天", timeZone = clock.zone.id, createdAt = f.now, updatedAt = f.now)
        show()
        expandMonth()
        doubleClickDate(day)
        (1L..6L).forEach { id ->
            compose.onNodeWithTag("plan-day-details-list").performScrollToNode(hasTestTag("plan-calendar-entry-$id"))
            compose.onNodeWithTag("plan-calendar-entry-$id").assertExists()
            compose.onNodeWithText(note + id).assertExists()
            if (id == 6L) compose.onNodeWithText("已完成").assertExists()
        }
        compose.onNodeWithText("没有日期不归当天").assertDoesNotExist()
        compose.onNodeWithTag("plan-day-details-list").performScrollToNode(hasTestTag("plan-calendar-entry-1"))
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("plan-day-details"))).assertExists()
        assertEquals(0, f.repo.writes)
    }
}
