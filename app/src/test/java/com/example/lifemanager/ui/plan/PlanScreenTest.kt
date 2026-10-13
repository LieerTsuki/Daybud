package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.ui.theme.LifeManagerTheme
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanScreenTest {
    @get:Rule val compose=createComposeRule()
    private val now=Instant.parse("2026-10-13T04:00:00Z")
    private val row=PlanListRow(Plan(id=1,title="长期学习目标",description="分步骤完成，不需要截止日期",timeZone="Asia/Shanghai",createdAt=now,updatedAt=now))
    private val state=mutableStateOf(PlanUiState(generation=DataGeneration(4),rows=listOf(row),isAvailable=true,isLoading=false))
    private var completed:Pair<Long,Boolean>?=null
    private var reads=0
    private var retried:Long?=null
    private var adapterModel:PlanViewModel?=null
    @After fun cleanup() { adapterModel?.viewModelScope?.cancel() }
    private fun scrollTo(matcher:SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }
    private fun show(dark:Boolean=false,large:Boolean=false) {
        compose.setContent { LifeManagerTheme(darkTheme=dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,if(large) 2f else 1f)) {
                Box(Modifier.size(320.dp,640.dp)) {
                    PlanScreen(state.value,{state.value=state.value.copy(filter=it)},{id,value->completed=id to value},
                        {reads++},{retried=it},{state.value=state.value.copy(message=null)},ZoneId.of("Asia/Shanghai"))
                }
            }
        } }
    }
    @Test fun `real record checkbox completes with its identity and target value`() {
        show();scrollTo(hasContentDescription("完成：长期学习目标"));compose.onNodeWithContentDescription("完成：长期学习目标").performClick()
        assertEquals(1L to true,completed)
    }
    @Test fun `completed checkbox offers undo instead of another completion`() {
        state.value=state.value.copy(rows=listOf(row.copy(plan=row.plan.copy(isCompleted=true,completedAt=now))))
        show();scrollTo(hasContentDescription("撤销完成：长期学习目标"));compose.onNodeWithContentDescription("撤销完成：长期学习目标").performClick()
        assertEquals(1L to false,completed)
    }
    @Test fun `maintenance preserves records but disables completion`() {
        state.value=state.value.copy(isMaintaining=true);show()
        compose.onNodeWithText("正在维护数据，暂不能修改").assertExists()
        scrollTo(hasContentDescription("完成：长期学习目标"));compose.onNodeWithContentDescription("完成：长期学习目标").assertIsNotEnabled()
    }
    @Test fun `read error exposes retry without masquerading as empty data`() {
        state.value=state.value.copy(isAvailable=false,rows=emptyList(),readError="读取失败")
        show();compose.onNodeWithText("重新读取").performScrollTo().performClick()
        assertEquals(1,reads)
        scrollTo(hasText("创建时间"))
        compose.onNodeWithText("当前筛选下暂无计划").assertDoesNotExist()
    }
    @Test fun `true empty success does not invent records or future module buttons`() {
        state.value=state.value.copy(rows=emptyList());show()
        scrollTo(hasText("当前筛选下暂无计划"));compose.onNodeWithText("当前筛选下暂无计划").assertIsDisplayed()
        compose.onNodeWithText("新建计划").assertDoesNotExist()
        compose.onNodeWithText("日历").assertDoesNotExist()
        compose.onNodeWithText("统计").assertDoesNotExist()
    }
    @Test fun `filters search and date not applicable hint are interactive`() {
        show();compose.onNodeWithText("待办").performScrollTo().performClick()
        assertEquals(PlanCategory.TODO,state.value.filter.category)
        compose.onNodeWithText("无日期待办不适用日期筛选").assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Kotlin")
        assertEquals("Kotlin",state.value.filter.query)
    }
    @Test fun `saved reminder warning offers only the receipt retry`() {
        state.value=state.value.copy(reminderFailures=mapOf(1L to PlanWriteReceipt(1,DataGeneration(4))))
        show();compose.onNodeWithContentDescription("重试提醒：1").performScrollTo().performClick()
        assertEquals(1L,retried)
        assertEquals(null,completed)
    }
    @Test fun `dark narrow screen at large font keeps long record and checkbox accessible`() {
        state.value=state.value.copy(rows=listOf(row.copy(plan=row.plan.copy(title="长期学习目标".repeat(12),description="很长的真实备注".repeat(16)))))
        show(dark=true,large=true)
        scrollTo(hasContentDescription("完成：${state.value.rows.single().plan.title}"))
        compose.onNodeWithContentDescription("完成：${state.value.rows.single().plan.title}").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("很长的真实备注".repeat(16)).assertExists()
    }
    @Test fun `retained adapter checkbox action cannot borrow replacement generation`() {
        val generations=TestGenerations(4)
        val coordinator=MaintenanceCoordinator(generations)
        val repo=AdapterRepository(row.plan)
        val effects=object:PlanReminderEffects { override suspend fun reconcile(planId:Long,expected:DataGeneration) {} }
        val model=PlanViewModel(repo,PlanUseCases(repo,coordinator,effects),GenerationAccess(generations,coordinator),Dispatchers.IO,Clock.fixed(now,ZoneId.of("Asia/Shanghai")))
        adapterModel=model
        compose.setContent { LifeManagerTheme { PlanScreen(model) } }
        compose.waitUntil(5_000) { compose.waitForIdle();model.uiState.value.isAvailable }
        scrollTo(hasContentDescription("完成：长期学习目标"))
        val oldClick=compose.onNodeWithContentDescription("完成：长期学习目标").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance=false
        try { runBlocking(Dispatchers.IO) {
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) { repo.rows.value=listOf(row.plan.copy(title="替换记录")) }
            } }
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            model.uiState.value.generation==DataGeneration(5) && model.uiState.value.rows.singleOrNull()?.plan?.title=="替换记录"
        }
        compose.runOnIdle { oldClick() }
        } finally { compose.mainClock.autoAdvance=true }
        compose.waitUntil(5_000) { compose.waitForIdle();model.uiState.value.pendingIds.isEmpty() }
        assertFalse(repo.rows.value.single().isCompleted)
    }

    private class AdapterRepository(plan:Plan):PlanRepository {
        val rows=MutableStateFlow(listOf(plan))
        override fun observeAll()=rows
        override fun observeTags()=MutableStateFlow(emptyList<Tag>())
        override suspend fun get(id:Long)=rows.value.find { it.id==id }
        override suspend fun getExceptions(id:Long)=emptyList<PlanException>()
        override suspend fun findLegacyPlan(source:PlanLegacySource,legacyId:Long):Long?=null
        override suspend fun setCompleted(id:Long,completed:Boolean,expected:DataGeneration) {
            rows.value=rows.value.map { if(it.id==id) it.copy(isCompleted=completed) else it }
        }
        override suspend fun save(plan:Plan,tagNames:List<String>,expected:DataGeneration):Long=error("not E1")
        override suspend fun delete(id:Long,expected:DataGeneration)=error("not E1")
        override suspend fun saveException(value:PlanException,expected:DataGeneration)=error("not E1")
    }
}
