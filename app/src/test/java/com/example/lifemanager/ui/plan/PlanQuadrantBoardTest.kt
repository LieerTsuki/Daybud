package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanQuadrantBoardTest {
    @get:Rule val compose=createComposeRule()
    private val labels=listOf("重要且紧急","重要不紧急","紧急不重要","不重要不紧急")
    private val priorities=listOf(TodoPriority.HIGH,TodoPriority.MEDIUM,TodoPriority.LOW,TodoPriority.NONE)
    private fun scroll(matcher:SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }
    @Test fun `all unfinished plans are shown without expanding and completed plans stay collapsed`() {
        val rows=(1L..6L).map { id -> PlanListRow(Plan(id=id,title="未完成$id",priority=TodoPriority.HIGH,
            timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)) } +
            PlanListRow(Plan(id=7,title="已完成的计划",priority=TodoPriority.HIGH,isCompleted=true,
                completedAt=Instant.EPOCH,timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH))
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(PlanUiState(rows=rows,isAvailable=true,isLoading=false),{},{_,_->},{},{},{})
        } } }
        scroll(hasText("未完成6"))
        (1L..6L).forEach { compose.onNodeWithText("未完成$it").assertExists() }
        compose.onNodeWithText("展开其余",substring=true).assertDoesNotExist()
        compose.onNodeWithText("收起计划").assertDoesNotExist()
        compose.onNodeWithText("已完成的计划").assertDoesNotExist()
        scroll(hasContentDescription("重要且紧急：已完成 1 项"))
        compose.onNodeWithContentDescription("重要且紧急：已完成 1 项").performScrollTo().performClick()
        compose.onNodeWithText("已完成的计划").assertExists()
        compose.onNodeWithContentDescription("重要且紧急：已完成 1 项").performScrollTo().performClick()
        compose.onNodeWithText("已完成的计划").assertDoesNotExist()
    }
    @Test fun `board has four add entries and removes obsolete filter strips`() {
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(PlanUiState(isAvailable=true,isLoading=false),{},{_,_->},{},{},{},onNew={})
        } } }
        labels.forEach { label ->
            scroll(hasContentDescription("添加计划：$label"))
            compose.onNodeWithContentDescription("添加计划：$label").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        }
        listOf("全部分类","全部状态","全部日期","全部优先级","优先级排序","新建计划").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
    @Test fun `manual priorities place dated and undated records in their own quadrant`() {
        val rows=priorities.mapIndexed { index,priority ->
            PlanListRow(Plan(id=index+1L,title="计划$index",priority=priority,timeZone="Asia/Shanghai",
                createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH,
                timeMode=if(index%2==0) PlanTimeMode.DEADLINE else PlanTimeMode.NONE,
                dueDate=if(index%2==0) java.time.LocalDate.of(2026,10,16) else null))
        }
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(PlanUiState(rows=rows,isAvailable=true,isLoading=false),{},{_,_->},{},{},{})
        } } }
        priorities.forEachIndexed { index,priority ->
            scroll(hasTestTag("plan-quadrant-${priority.name}"))
            compose.onNode(hasText("计划$index") and hasAnyAncestor(hasTestTag("plan-quadrant-${priority.name}"))).assertExists()
        }
    }
    @Test fun `each add action supplies its own manual quadrant`() {
        val added=mutableListOf<TodoPriority>()
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(PlanUiState(isAvailable=true,isLoading=false),{},{_,_->},{},{},{},onNewInQuadrant={added+=it})
        } } }
        labels.forEach { label ->
            scroll(hasContentDescription("添加计划：$label"))
            compose.onNodeWithContentDescription("添加计划：$label").performClick()
        }
        assertEquals(priorities,added)
    }
    @Test fun `completed plan can be expanded and undone inside its original quadrant`() {
        val plan=Plan(id=9,title="完成后仍在原象限",priority=TodoPriority.HIGH,isCompleted=true,completedAt=Instant.EPOCH,
            timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
        val state=mutableStateOf(PlanUiState(rows=listOf(PlanListRow(plan)),isAvailable=true,isLoading=false))
        var undone:Pair<Long,Boolean>?=null
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(state.value,{},{id,value->undone=id to value},{},{},{})
        } } }
        scroll(hasContentDescription("重要且紧急：已完成 1 项"))
        compose.onNodeWithContentDescription("重要且紧急：已完成 1 项").performClick()
        val checkbox=hasContentDescription("撤销完成：完成后仍在原象限") and
            hasAnyAncestor(hasTestTag("plan-quadrant-HIGH"))
        scroll(checkbox)
        compose.onNode(checkbox).performClick()
        assertEquals(9L to false,undone)
    }
    @Test fun `repeating plan warns that checkbox completes the entire series`() {
        val plan=Plan(id=11,title="每天阅读",priority=TodoPriority.HIGH,timeMode=PlanTimeMode.ALL_DAY,
            allDayStartDate=java.time.LocalDate.of(2026,10,16),allDayEndDate=java.time.LocalDate.of(2026,10,16),
            repeatRule=com.example.lifemanager.domain.model.ScheduleRepeatRule.DAILY,
            timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanScreen(PlanUiState(rows=listOf(PlanListRow(plan)),isAvailable=true,isLoading=false),{},{_,_->},{},{},{})
        } } }
        scroll(hasText("重复系列 · 勾选完成将结束整个系列"))
        compose.onNodeWithText("重复系列 · 勾选完成将结束整个系列").assertIsDisplayed()
    }
}
