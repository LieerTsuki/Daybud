package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.theme.LifeManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanEditorComposeTest {
    @get:Rule val compose=createComposeRule()
    private val f=EditorTestWorld(Dispatchers.IO)
    @After fun cleanup() { f.model.viewModelScope.cancel() }
    private fun waitFor(predicate:(PlanEditorState)->Boolean) { compose.waitUntil(5_000) { compose.waitForIdle();predicate(f.model.uiState.value) } }
    private fun scroll(matcher:SemanticsMatcher) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher) }
    private fun show(editor:Boolean=true,dark:Boolean=false,large:Boolean=false) {
        compose.setContent { LifeManagerTheme(darkTheme=dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,if(large)2f else 1f)) {
                val state by f.model.uiState.collectAsState()
                Box(Modifier.size(320.dp,640.dp)) { if(editor)PlanEditor(state,f.model)else PlanDetail(state,f.model) }
            }
        } }
        waitFor { it.isAvailable }
        compose.runOnIdle { if(editor)f.model.openNew(f.token) else f.model.open(7,f.token) }
        waitFor { it.isAvailable && it.pane==if(editor)PlanPane.EDIT else PlanPane.DETAIL }
    }
    @Test fun `simple editor omits technical and historical metadata fields`() {
        show()
        assertFailsWith<AssertionError> { scroll(hasContentDescription("IANA 时区")) }
        assertFailsWith<AssertionError> { scroll(hasContentDescription("地点")) }
        assertFailsWith<AssertionError> { scroll(hasContentDescription("提前分钟")) }
        scroll(hasContentDescription("单项计划提醒"))
        compose.onNodeWithContentDescription("单项计划提醒").assertExists()
    }
    @Test fun `detail shows only active time in Shanghai without diagnostic fields`() {
        f.repo.rows.value=listOf(Plan(id=7,title="会议计划",description="带上笔记本",timeZone="America/New_York",
            timeMode=PlanTimeMode.TIMED,startAt=java.time.Instant.parse("2026-10-20T04:00:00Z"),
            endAt=java.time.Instant.parse("2026-10-20T05:00:00Z"),location="旧地点",createdAt=f.now,updatedAt=f.now))
        show(editor=false)
        scroll(hasText("2026-10-20 12:00 — 13:00"))
        compose.onNodeWithText("2026-10-20 12:00 — 13:00").assertExists()
        assertFailsWith<AssertionError> { scroll(hasText("时区：",substring=true)) }
        assertFailsWith<AssertionError> { scroll(hasText("记录 #",substring=true)) }
        assertFailsWith<AssertionError> { scroll(hasText("旧地点",substring=true)) }
        scroll(hasText("不重复"));compose.onNodeWithText("不重复").assertExists()
    }
    @Test fun `friendly Shanghai time entry saves the chosen instant without an offset field`() {
        show()
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("上海会议")
        scroll(hasText("时间区间"));compose.onNodeWithText("时间区间").performClick()
        scroll(hasContentDescription("开始时间输入"))
        compose.onNodeWithContentDescription("开始时间输入").performTextReplacement("2026-10-20 12:00")
        compose.onNodeWithContentDescription("开始时间输入").assertTextContains("2026-10-20 12:00")
        scroll(hasContentDescription("结束时间输入"))
        compose.onNodeWithContentDescription("结束时间输入").performTextReplacement("2026-10-20 13:00")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.detail!=null && !it.isBusy }
        val saved=f.repo.rows.value.single()
        assertEquals(java.time.Instant.parse("2026-10-20T04:00:00Z"),saved.startAt)
        assertEquals(java.time.Instant.parse("2026-10-20T05:00:00Z"),saved.endAt)
        assertEquals("Asia/Shanghai",saved.timeZone)
    }
    @Test fun `date and time picker commits only after both confirmations`() {
        show()
        scroll(hasText("截止日期"));compose.onNodeWithText("截止日期").performClick()
        scroll(hasContentDescription("选择截止时间"));compose.onNodeWithContentDescription("选择截止时间").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals("",f.model.uiState.value.draft!!.dueAt)
        compose.onNodeWithContentDescription("选择截止时间").performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals("",f.model.uiState.value.draft!!.dueAt)
        compose.onNodeWithText("确定").performClick()
        assertTrue(f.model.uiState.value.draft!!.dueAt.isNotBlank())
        scroll(hasContentDescription("截止时间输入"))
        compose.onNodeWithContentDescription("截止时间输入").assertTextContains("09:00", substring = true)
    }
    @Test fun `unconfigured historical reminder is off until enabling creates a real alarm lead`() {
        f.repo.rows.value=listOf(Plan(id=7,title="旧提醒计划",timeZone="Asia/Shanghai",reminderEnabled=true,
            reminderMinutes=null,timeMode=PlanTimeMode.DEADLINE,dueAt=java.time.Instant.parse("2026-10-20T04:00:00Z"),createdAt=f.now,updatedAt=f.now))
        show()
        compose.runOnIdle { f.model.open(7,f.token) }
        waitFor { it.detail?.id==7L && !it.isLoading }
        compose.runOnIdle { f.model.beginEdit(f.model.uiState.value.stamp) }
        waitFor { it.draft?.base?.id==7L && !it.isLoading }
        scroll(hasContentDescription("单项计划提醒"))
        compose.onNodeWithContentDescription("单项计划提醒").assertIsOff().performClick()
        assertTrue(f.model.uiState.value.draft!!.reminderEnabled)
        assertEquals("0",f.model.uiState.value.draft!!.reminderMinutes)
        compose.onNodeWithContentDescription("单项计划提醒").assertIsOn()
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.detail?.id==7L && !it.isBusy }
        assertTrue(f.repo.rows.value.single().reminderEnabled)
        assertEquals(0,f.repo.rows.value.single().reminderMinutes)
    }
    @Test fun `blank editor exposes real title entry and save writes once`() {
        show();compose.onNodeWithContentDescription("计划标题").performTextReplacement("手机测试目标")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.pane==PlanPane.DETAIL && !it.isBusy && it.detail!=null }
        assertEquals(listOf("手机测试目标"),f.repo.rows.value.map { it.title })
    }
    @Test fun `deadline field appears only when selected and keeps date semantics`() {
        show();scroll(hasText("截止日期"));compose.onNodeWithText("截止日期").performClick()
        scroll(hasContentDescription("仅日期截止"));compose.onNodeWithContentDescription("仅日期截止").performClick()
        scroll(hasContentDescription("截止日期输入"));compose.onNodeWithContentDescription("截止日期输入").performTextReplacement("2026-10-20")
        compose.runOnIdle { f.model.change(PlanField.TITLE,"日期计划",f.model.uiState.value.stamp) }
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.detail!=null && !it.isBusy }
        assertEquals(PlanTimeMode.DEADLINE,f.repo.rows.value.single().timeMode);assertNull(f.repo.rows.value.single().dueAt)
    }
    @Test fun `interval and all day selections expose their working fields`() {
        show();scroll(hasText("时间区间"));compose.onNodeWithText("时间区间").performClick()
        scroll(hasContentDescription("开始时间输入"));compose.onNodeWithContentDescription("开始时间输入").assertExists()
        scroll(hasText("全天日期"));compose.onNodeWithText("全天日期").performClick()
        compose.onNodeWithText("确认切换").performClick()
        scroll(hasContentDescription("全天开始日期输入"));compose.onNodeWithContentDescription("全天开始日期输入").assertExists()
    }
    @Test fun `invalid save keeps input and shows actionable error`() {
        show();scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.error!=null }
        assertEquals(0,f.repo.writes);assertNotNull(f.model.uiState.value.draft)
        scroll(hasText("表单无效",substring=true));compose.onNodeWithText("表单无效",substring=true).assertExists()
    }
    @Test fun `closing dirty editor offers explicit retain or discard`() {
        show();compose.onNodeWithContentDescription("计划标题").performTextReplacement("不要丢弃")
        scroll(hasText("返回计划"));compose.onNodeWithText("返回计划").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("不要丢弃",f.model.uiState.value.draft!!.title)
        compose.onNodeWithText("返回计划").performClick();compose.onNodeWithText("放弃草稿").performClick()
        waitFor { it.pane==PlanPane.LIST }
    }
    @Test fun `disabled global reminder message does not invent a settings toggle`() {
        f.enabled=false;show()
        scroll(hasText("计划提醒总开关已关闭，单项配置仍会保存但不会投递"))
        compose.onNodeWithText("计划提醒总开关已关闭，单项配置仍会保存但不会投递").assertExists()
        compose.onNodeWithText("打开系统提醒").assertDoesNotExist()
    }
    @Test fun `detail shows real long text and edits the same identity`() {
        val plan=Plan(id=7,title="原始标题",description="长备注".repeat(30),timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now)
        f.repo.rows.value=listOf(plan);show(editor=false,dark=true,large=true)
        scroll(hasText("编辑计划"));compose.onNodeWithText("编辑计划").performClick()
        waitFor { it.pane==PlanPane.EDIT }
        assertEquals(7,f.model.uiState.value.draft!!.base.id);assertEquals(plan.description,f.model.uiState.value.draft!!.description)
    }
    @Test fun `detail deletion dialog does not delete until confirmed`() {
        f.repo.rows.value=listOf(Plan(id=7,title="删除目标",timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now))
        show(editor=false);scroll(hasText("删除计划"));compose.onNodeWithText("删除计划").performClick()
        assertEquals(1,f.repo.rows.value.size)
        compose.onNodeWithText("取消删除").performClick();assertEquals(1,f.repo.rows.value.size)
    }
    @Test fun `conflict dialog can return to editing without discarding the draft`() {
        f.repo.rows.value=listOf(Plan(id=7,title="已有区间",timeZone="Asia/Shanghai",timeMode=PlanTimeMode.TIMED,
            startAt=java.time.Instant.parse("2026-10-20T04:00:00Z"),endAt=java.time.Instant.parse("2026-10-20T05:00:00Z"),createdAt=f.now,updatedAt=f.now))
        show();compose.runOnIdle {
            f.model.change(PlanField.TITLE,"冲突草稿",f.model.uiState.value.stamp)
            f.model.setMode(PlanTimeMode.TIMED,f.model.uiState.value.stamp)
            f.model.change(PlanField.START_AT,"2026-10-20T12:00+08:00",f.model.uiState.value.stamp)
            f.model.change(PlanField.END_AT,"2026-10-20T13:00+08:00",f.model.uiState.value.stamp)
        }
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { it.conflicts!=null && !it.isBusy }
        compose.onNodeWithText("继续修改").performClick()
        waitFor { it.conflicts==null && !it.confirmClose }
        scroll(hasContentDescription("计划标题"));compose.onNodeWithContentDescription("计划标题").performTextReplacement("保留后修改")
        assertEquals("保留后修改",f.model.uiState.value.draft!!.title);assertEquals(1,f.repo.rows.value.size)
    }
    @Test fun `mode confirmation preserves retained repeat and reminder configuration without technical fields`() {
        show();compose.runOnIdle {
            f.model.setRepeat(com.example.lifemanager.domain.model.ScheduleRepeatRule.CUSTOM,f.model.uiState.value.stamp)
            f.model.change(PlanField.REPEAT_INTERVAL,"3",f.model.uiState.value.stamp)
            f.model.toggleDay(java.time.DayOfWeek.MONDAY,f.model.uiState.value.stamp)
            f.model.change(PlanField.REPEAT_END,"2026-10-30",f.model.uiState.value.stamp)
            f.model.change(PlanField.REMINDER_MINUTES,"5",f.model.uiState.value.stamp)
        }
        scroll(hasText("时间区间"));compose.onNodeWithText("时间区间").performClick()
        compose.onNodeWithText("确认切换").assertExists()
        compose.onNodeWithText("MONDAY",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Asia/Shanghai",substring=true).assertDoesNotExist()
        compose.onNodeWithText("保留原类型").performClick()
        val draft=f.model.uiState.value.draft!!
        assertEquals(PlanTimeMode.NONE,draft.mode)
        assertEquals(com.example.lifemanager.domain.model.ScheduleRepeatRule.CUSTOM,draft.repeatRule)
        assertEquals("3",draft.repeatInterval)
        assertEquals(setOf(java.time.DayOfWeek.MONDAY),draft.repeatDays)
        assertEquals("2026-10-30",draft.repeatEnd)
        assertEquals("5",draft.reminderMinutes)
    }
    @Test fun `mode confirmation distinguishes active instant deadline from retained date`() {
        show();compose.runOnIdle {
            f.model.setMode(PlanTimeMode.DEADLINE,f.model.uiState.value.stamp)
            f.model.setDateOnly(true,f.model.uiState.value.stamp)
            f.model.change(PlanField.DUE_DATE,"2026-10-20",f.model.uiState.value.stamp)
            f.model.setDateOnly(false,f.model.uiState.value.stamp)
            f.model.change(PlanField.DUE_AT,"2026-10-21T16:00+08:00",f.model.uiState.value.stamp)
        }
        scroll(hasText("无日期待办"));compose.onNodeWithText("无日期待办").performClick()
        compose.onNode(hasText("2026-10-21 16:00",substring=true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("2026-10-20",substring=true) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("Asia/Shanghai",substring=true) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }
}
