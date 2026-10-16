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
        assertEquals(LocalDate.parse("2026-10-20"),selected)
    }
    @Test fun `calendar error does not display a fake successful empty day`() {
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isLoading=false,readError="读取日历失败")
        compose.setContent { LifeManagerTheme { PlanCalendarScreen(state,{},{},{},{},{},{},null) } }
        compose.onNodeWithText("读取日历失败").assertExists()
        compose.onNodeWithText("此日期暂无日程").assertDoesNotExist()
        compose.onNodeWithText("重新读取日历").assertIsEnabled()
    }
    @Test fun `statistics exposes real full database fraction and separate today counts`() {
        val state=PlanStatisticsState(statistics=PlanStatistics(3,1,2,1,1,1.0/3),todayScheduledCount=2,today=day,displayZone=zone,isAvailable=true,isLoading=false)
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanStatisticsScreen(state,{}) } } }
        scroll(hasText("全库完成率：33%（1 / 3）"));compose.onNodeWithText("全库完成率：33%（1 / 3）").assertExists()
        scroll(hasText("今日完成：1 项"));compose.onNodeWithText("今日完成：1 项").assertExists()
        scroll(hasText("今天日程：2 项"));compose.onNodeWithText("今天日程：2 项").assertExists()
        scroll(hasText("逾期：1 项"));compose.onNodeWithText("逾期：1 项").assertExists()
    }
    @Test fun `statistics failure never advertises zero or stale successful facts`() {
        val state=PlanStatisticsState(statistics=PlanStatistics(9,8,1,0,0,8.0/9),todayScheduledCount=5,today=day,displayZone=zone,isLoading=false,readError="读取统计失败")
        compose.setContent { LifeManagerTheme { PlanStatisticsScreen(state,{}) } }
        compose.onNodeWithText("读取统计失败").assertExists()
        compose.onNodeWithText("暂无计划").assertDoesNotExist()
        compose.onNodeWithText("全库完成率",substring=true).assertDoesNotExist()
    }
    @Test fun `empty statistics remains finite and announces no plans with large dark text`() {
        val state=PlanStatisticsState(statistics=PlanStatistics(0,0,0,0,0,0.0),todayScheduledCount=0,today=day,displayZone=zone,isAvailable=true,isLoading=false)
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                LifeManagerTheme(darkTheme=true) { Box(Modifier.size(320.dp,640.dp)) { PlanStatisticsScreen(state,{}) } }
            }
        }
        scroll(hasText("暂无计划"));compose.onNodeWithText("暂无计划").assertExists()
        scroll(hasText("全库完成率：0%（0 / 0）"));compose.onNodeWithText("全库完成率：0%（0 / 0）").assertExists()
        scroll(hasText("今天日程：0 项"));compose.onNodeWithText("今天日程：0 项").assertExists()
    }
    @Test fun `calendar large dark text keeps date cells and long record reachable`() {
        val title="很长的真实日程内容".repeat(12)
        val plan=Plan(id=7,title=title,timeZone=zone.id,createdAt=java.time.Instant.EPOCH,updatedAt=java.time.Instant.EPOCH,
            timeMode=PlanTimeMode.DEADLINE,dueDate=day)
        val state=PlanCalendarState(selectedDate=day,displayZone=zone,isAvailable=true,isLoading=false,
            days=mapOf(day to listOf(PlanListRow(plan,PlanOccurrence(7,day,PlanOccurrenceTime.DeadlineDate(day))))))
        var opened:Long?=null
        compose.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                LifeManagerTheme(darkTheme=true) { Box(Modifier.size(320.dp,640.dp)) { PlanCalendarScreen(state,{},{},{},{},{},{},{opened=it}) } }
            }
        }
        scroll(hasContentDescription("2026-10-14",substring=true));compose.onNodeWithContentDescription("2026-10-14",substring=true).assertHeightIsAtLeast(48.dp)
        scroll(hasTestTag("plan-calendar-entry-7"));compose.onNodeWithTag("plan-calendar-entry-7").performClick();assertEquals(7L,opened)
    }
}
