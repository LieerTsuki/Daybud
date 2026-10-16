package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanCalendarTitlesComposeTest {
    @get:Rule val compose=createComposeRule()
    private val day=LocalDate.parse("2026-10-14")
    private val zone=ZoneId.of("Asia/Shanghai")

    private fun plan(id:Long,title:String,date:LocalDate?=day)=Plan(
        id=id,title=title,timeZone=zone.id,createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH,
        timeMode=if(date==null) PlanTimeMode.NONE else PlanTimeMode.DEADLINE,dueDate=date)

    private fun show(plans:List<Plan>,largeText:Boolean=false,onDate:(LocalDate)->Unit={},onOpenDay:(LocalDate)->Unit={}) {
        val selected=day.plusDays(1)
        val state=PlanCalendarState(selectedDate=selected,displayZone=zone,isAvailable=true,isLoading=false,
            days=PlanCalendarQuery.select(plans,selected,PlanCalendarMode.MONTH,zone))
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(largeText) 2f else 1f)) {
                LifeManagerTheme(darkTheme=largeText) { Box(Modifier.size(320.dp,640.dp)) {
                    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        planMonthCalendar(state,onDate,onOpenDay,{},{},{})
                    }
                } }
            }
        }
    }

    private fun revealDate(count:Int) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasContentDescription("$day，$count 条日历记录"))
    }
    private fun inDate(text:String,count:Int)=hasText(text) and
        hasAnyAncestor(hasContentDescription("$day，$count 条日历记录"))

    @Test fun `unselected month date displays actual plan name and remains selectable`() {
        var selected:LocalDate?=null
        show(listOf(plan(1,"准备项目报告")),onDate={selected=it})
        revealDate(1)
        compose.onNode(inDate("准备项目报告",1),useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("$day，1 条日历记录").performClick()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        assertEquals(day,selected)
    }

    @Test fun `busy month date shows four names and remaining item count`() {
        show(listOf(plan(1,"写报告"),plan(2,"交材料"),plan(3,"开例会"),plan(4,"整理记录"),plan(5,"复核材料")))
        revealDate(5)
        listOf("写报告","交材料","开例会","整理记录").forEach {
            compose.onNode(inDate(it,5),useUnmergedTree=true).assertIsDisplayed()
        }
        compose.onNode(inDate("还有 1 项",5),useUnmergedTree=true).assertIsDisplayed()
        compose.onNode(inDate("复核材料",5),useUnmergedTree=true).assertDoesNotExist()
    }

    @Test fun `month title rendering excludes plans without a date`() {
        show(listOf(plan(1,"有日期的安排"),plan(2,"没有日期的计划",null)))
        revealDate(1)
        compose.onNode(inDate("有日期的安排",1),useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("没有日期的计划",useUnmergedTree=true).assertDoesNotExist()
    }

    @Test fun `narrow month cells keep long names within date bounds at two hundred percent text`() {
        val title="很长的计划名称用于检查窄屏日历".repeat(4)
        show(listOf(plan(1,title),plan(2,"第二个计划"),plan(3,"第三个计划")),largeText=true)
        revealDate(3)
        val cell=compose.onNodeWithContentDescription("$day，3 条日历记录")
        cell.assertHeightIsAtLeast(48.dp)
        val cellBounds=cell.fetchSemanticsNode().boundsInRoot
        val titleBounds=compose.onNode(inDate(title,3),useUnmergedTree=true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.left>=cellBounds.left && titleBounds.right<=cellBounds.right)
        assertTrue(titleBounds.top>=cellBounds.top && titleBounds.bottom<=cellBounds.bottom)
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNode(inDate(title,3),useUnmergedTree=true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1,layouts.single().lineCount,"每个日历任务名称必须严格单行")
        assertEquals(8f,layouts.single().layoutInput.style.fontSize.value,"大字体窄屏仍保留8sp可读下限")
        compose.onNode(inDate("第三个计划",3),useUnmergedTree=true).assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `short four character and long task names use one shared month font size`() {
        show(listOf(plan(1,"课"),plan(2,"准备报告"),plan(3,"很长的项目报告计划名称")))
        revealDate(3)
        val layouts=(1..3).map { id ->
            val results=mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("plan-calendar-title-$day-$id",useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.single()
        }
        assertEquals(1,layouts.map { it.layoutInput.style.fontSize }.distinct().size,"字号只取决于日期格宽度，不能随名称长短变化")
        layouts.forEach { assertEquals(1,it.lineCount) }
        assertTrue(!layouts[1].hasVisualOverflow,"320dp常规字号应尽量完整显示四个汉字")
    }

    @Test fun `calendar exposes full day details action for accessibility without mode controls`() {
        var opened:LocalDate?=null
        show(listOf(plan(1,"准备项目报告")),onOpenDay={opened=it})
        revealDate(1)
        val actions=compose.onNodeWithContentDescription("$day，1 条日历记录")
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.any { it.label=="查看当天全部计划" })
        assertTrue(actions.single { it.label=="查看当天全部计划" }.action())
        assertEquals(day,opened)
        compose.onNodeWithText("周视图").assertDoesNotExist()
        compose.onNodeWithText("日视图").assertDoesNotExist()
    }

    @Test fun `double tap opens date details without firing a single selection first`() {
        var selected:LocalDate?=null
        var opened:LocalDate?=null
        show(listOf(plan(1,"准备项目报告")),onDate={selected=it},onOpenDay={opened=it})
        revealDate(1)
        compose.onNodeWithTag("plan-calendar-date-$day").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(day,opened)
        assertEquals(null,selected,"双击不能先触发单击读取并让详情打开被loading拒绝")
    }

    @Test fun `every task line announces its matching quadrant`() {
        val priorities=listOf(TodoPriority.HIGH,TodoPriority.MEDIUM,TodoPriority.LOW,TodoPriority.NONE)
        show(priorities.mapIndexed { index,priority -> plan(index.toLong()+1,"任务${index+1}").copy(priority=priority) })
        revealDate(4)
        priorities.forEachIndexed { index,priority ->
            compose.onNodeWithTag("plan-calendar-title-$day-${index+1}",useUnmergedTree=true)
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,priorityLabel(priority)))
        }
    }
}
