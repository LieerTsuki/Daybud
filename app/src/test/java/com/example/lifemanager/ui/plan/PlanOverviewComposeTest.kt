package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanOverviewComposeTest {
    @get:Rule val compose=createComposeRule()
    private val day=LocalDate.parse("2026-10-14")
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun scroll(matcher:SemanticsMatcher) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher) }
    @Test fun `calendar month selection is accessible and preserves civil date`() {
        var selected:LocalDate?=null
        val state=PlanCalendarState(generation=DataGeneration(4),selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false,
            days=(1..31).associate { LocalDate.of(2026,10,it) to emptyList<PlanListRow>() })
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) {
            PlanCalendarScreen(state,{selected=it},{},{},{},{},{},null)
        } } }
        scroll(hasContentDescription("2026-10-20",substring=true))
        compose.onNodeWithContentDescription("2026-10-20",substring=true).assertHeightIsAtLeast(48.dp).performClick()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        assertEquals(LocalDate.parse("2026-10-20"),selected)
    }
    @Test fun `calendar error does not display a fake successful empty day`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isLoading=false,readError="读取日历失败")
        compose.setContent { LifeManagerTheme { PlanCalendarScreen(state,{},{},{},{},{},{},null) } }
        compose.onNodeWithText("读取日历失败").assertExists()
        compose.onNodeWithText("此日期暂无日程").assertDoesNotExist()
        compose.onNodeWithText("重新读取日历").assertIsEnabled()
    }
    @Test fun `empty month cells are compact without losing touch target`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false)
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{},{},{},null) } } }
        scroll(hasTestTag("plan-calendar-date-$day"))
        compose.onNodeWithTag("plan-calendar-date-$day").assertHeightIsAtLeast(48.dp)
        val bounds = compose.onNodeWithTag("plan-calendar-date-$day").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 64.dp)
    }
    @Test fun `month starts on Sunday with October first aligned under Thursday`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false)
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{},{},{},null) } } }
        val sunday=compose.onNodeWithText("日").fetchSemanticsNode().boundsInRoot
        val monday=compose.onNodeWithText("一").fetchSemanticsNode().boundsInRoot
        val thursday=compose.onNodeWithText("四").fetchSemanticsNode().boundsInRoot
        val first=compose.onNodeWithTag("plan-calendar-date-2026-10-01").fetchSemanticsNode().boundsInRoot
        assertTrue(sunday.left<monday.left,"星期日应为首列")
        assertTrue(kotlin.math.abs(thursday.center.x-first.center.x)<1f,"2026年10月1日应对齐星期四")
    }
    @Test fun `refresh keeps the verified month visible while disabling date actions`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=true)
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{},{},{},null) } } }
        compose.onNodeWithTag("plan-calendar-date-2026-10-01").assertExists().assertIsNotEnabled()
        compose.onNodeWithContentDescription("正在读取日历").assertExists()
    }
    @Test fun `month component omits standalone page title mode tabs and completion filter`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false)
        compose.setContent { LifeManagerTheme { PlanCalendarScreen(state,{},{},{},{},{},{},null) } }
        compose.onNodeWithText("日历手账").assertDoesNotExist()
        compose.onNodeWithText("月视图").assertDoesNotExist()
        compose.onNodeWithText("周视图").assertDoesNotExist()
        compose.onNodeWithText("日视图").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示已完成计划").assertDoesNotExist()
    }
    @Test fun `month navigation exposes compact controls with large dark text`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false)
        var moved=0
        var today=false
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                LifeManagerTheme(darkTheme=true) { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{moved=it},{today=true},{},null) } }
            }
        }
        compose.onNodeWithContentDescription("下个月").performClick()
        assertEquals(1,moved)
        compose.onNodeWithContentDescription("上个月").performClick()
        assertEquals(-1,moved)
        compose.onNodeWithText("回到今天").performClick()
        assertTrue(today)
    }
    @Test fun `calendar large dark text keeps date cells and long record reachable`() {
        val title="很长的真实日程内容".repeat(12)
        val plan=Plan(id=7,title=title,timeZone=zone.id,createdAt=java.time.Instant.EPOCH,updatedAt=java.time.Instant.EPOCH,
            timeMode=PlanTimeMode.DEADLINE,dueDate=day)
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false,
            days=mapOf(day to listOf(PlanListRow(plan,PlanOccurrence(7,day,PlanOccurrenceTime.DeadlineDate(day))))))
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                LifeManagerTheme(darkTheme=true) { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{},{},{},null) } }
            }
        }
        scroll(hasContentDescription("2026-10-14",substring=true));compose.onNodeWithContentDescription("2026-10-14",substring=true).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("plan-calendar-date-$day")),useUnmergedTree=true).assertIsDisplayed()
    }
}
