package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Clock
import java.time.ZoneId
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
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanWorkspaceComposeTest {
    @get:Rule val compose=createComposeRule()
    private val f=EditorTestWorld(Dispatchers.IO)
    private val list=PlanViewModel(f.repo,f.cases,GenerationAccess(f.generations,f.coordinator),Dispatchers.IO,Clock.fixed(f.now,ZoneId.of("Asia/Shanghai")))
    @After fun cleanup() { list.viewModelScope.cancel();f.model.viewModelScope.cancel() }
    private fun waitFor(predicate:()->Boolean) { compose.waitUntil(5_000) { compose.waitForIdle();predicate() } }
    private fun scroll(matcher:SemanticsMatcher) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher) }
    private fun show() {
        compose.setContent { LifeManagerTheme { Box(Modifier.size(320.dp,640.dp)) { PlanWorkspace(list,f.model) } } }
        waitFor { list.uiState.value.isAvailable && f.model.uiState.value.isAvailable }
    }
    @Test fun `new entry saves then returns to unchanged todo filter with persisted row`() {
        show();compose.runOnIdle { list.onFilterChanged(PlanFilter(category=PlanCategory.TODO),f.token) }
        waitFor { !list.uiState.value.isLoading }
        scroll(hasText("新建计划"));compose.onNodeWithText("新建计划").performClick()
        waitFor { f.model.uiState.value.pane==PlanPane.EDIT && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("真正的新目标")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { f.model.uiState.value.detail?.title=="真正的新目标" && !f.model.uiState.value.isBusy }
        scroll(hasText("返回计划"));compose.onNodeWithText("返回计划").performClick()
        waitFor { f.model.uiState.value.pane==PlanPane.LIST && !list.uiState.value.isLoading }
        assertEquals(PlanCategory.TODO,list.uiState.value.filter.category)
        scroll(hasText("真正的新目标"));compose.onNodeWithText("真正的新目标").assertExists()
        assertEquals(1,f.repo.rows.value.size)
    }
    @Test fun `opening actual list record edits same row and toggles completion in detail`() {
        f.repo.rows.value=listOf(Plan(id=7,title="旧目标",timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now))
        show();scroll(hasText("旧目标"));compose.onNodeWithText("旧目标").performClick()
        waitFor { f.model.uiState.value.detail?.id==7L }
        scroll(hasText("编辑计划"));compose.onNodeWithText("编辑计划").performClick()
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("编辑后的目标")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { f.model.uiState.value.detail?.title=="编辑后的目标" && !f.model.uiState.value.isBusy }
        scroll(hasText("勾选完成"));compose.onNodeWithText("勾选完成").performClick()
        waitFor { f.model.uiState.value.detail?.isCompleted==true && !f.model.uiState.value.isBusy }
        assertEquals(7L,f.repo.rows.value.single().id)
        scroll(hasText("撤销完成"));compose.onNodeWithText("撤销完成").performClick()
        waitFor { f.model.uiState.value.detail?.isCompleted==false && !f.model.uiState.value.isBusy }
    }
    @Test fun `failed reminder receipt remains actionable after returning to list`() {
        f.effects={_,_->error("synthetic alarm failure")};show()
        scroll(hasText("新建计划"));compose.onNodeWithText("新建计划").performClick()
        waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("提醒失败目标")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        // A commit receipt precedes the separately admitted detail read. Do not act on the
        // outgoing editor's semantics while the detail pane is still loading.
        waitFor { val state=f.model.uiState.value
            !state.isBusy && !state.isLoading && state.isAvailable && state.pane==PlanPane.DETAIL &&
                state.detail?.title=="提醒失败目标" && state.reminderReceipt!=null }
        scroll(hasText("返回计划"));compose.onNodeWithText("返回计划").performClick()
        waitFor { f.model.uiState.value.pane==PlanPane.LIST && f.model.uiState.value.isAvailable }
        scroll(hasText("仅重试 #41 的提醒"));f.effects={_,_->}
        compose.onNodeWithText("仅重试 #41 的提醒").performClick()
        waitFor { !f.model.uiState.value.isBusy && f.model.uiState.value.reminderReceipt==null }
        assertEquals(1,f.repo.writes);assertEquals(1,f.repo.rows.value.size)
    }
    @Test fun `date conversion keeps same ID and filter through both classifications`() {
        f.repo.rows.value=listOf(Plan(id=7,title="长期目标",tagNames=listOf("保留标签"),timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now))
        show();compose.runOnIdle { list.onFilterChanged(PlanFilter(category=PlanCategory.TODO),f.token) }
        waitFor { !list.uiState.value.isLoading };scroll(hasText("长期目标"));compose.onNodeWithText("长期目标").performClick()
        waitFor { f.model.uiState.value.detail?.id==7L }
        scroll(hasText("编辑计划"));compose.onNodeWithText("编辑计划").performClick()
        scroll(hasText("截止日期"));compose.onNodeWithText("截止日期").performClick()
        scroll(hasContentDescription("仅日期截止"));compose.onNodeWithContentDescription("仅日期截止").performClick()
        scroll(hasContentDescription("截止日期输入"));compose.onNodeWithContentDescription("截止日期输入").performTextReplacement("2026-10-20")
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { f.model.uiState.value.detail?.timeMode==PlanTimeMode.DEADLINE && !f.model.uiState.value.isBusy }
        assertEquals(PlanCategory.TODO,list.uiState.value.filter.category)
        assertEquals(listOf("保留标签"),f.repo.rows.value.single().tagNames)
        scroll(hasText("编辑计划"));compose.onNodeWithText("编辑计划").performClick()
        scroll(hasText("无日期待办"));compose.onNodeWithText("无日期待办").performClick()
        compose.onNodeWithText("确认切换").performClick()
        scroll(hasText("保存计划"));compose.onNodeWithText("保存计划").performClick()
        waitFor { f.model.uiState.value.detail?.timeMode==PlanTimeMode.NONE && !f.model.uiState.value.isBusy }
        assertEquals(7L,f.repo.rows.value.single().id)
        assertEquals(java.time.LocalDate.parse("2026-10-20"),f.repo.rows.value.single().dueDate)
    }
    @Test fun `retained list navigation actions cannot borrow a replacement generation`() {
        f.repo.rows.value=listOf(Plan(id=7,title="旧数据",timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now));show()
        scroll(hasText("新建计划"));val oldNew=compose.onNodeWithText("新建计划").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        scroll(hasText("旧数据"));val oldOpen=compose.onNodeWithText("旧数据").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance=false
        try {
            runBlocking(Dispatchers.IO) { f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
                f.generations.commit(session.generation) { f.repo.rows.value=listOf(f.repo.rows.value.single().copy(title="替换记录")) }
            } } }
            waitFor { f.model.uiState.value.isAvailable && f.model.uiState.value.stamp.generation?.value==5L && list.uiState.value.generation?.value==5L }
            compose.runOnIdle { oldNew();oldOpen() }
            assertEquals(PlanPane.LIST,f.model.uiState.value.pane);assertNull(f.model.uiState.value.draft);assertNull(f.model.uiState.value.detail)
        } finally { compose.mainClock.autoAdvance=true }
    }
}
