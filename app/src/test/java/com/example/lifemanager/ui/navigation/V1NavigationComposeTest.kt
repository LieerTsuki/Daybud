package com.example.lifemanager.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.repository.*
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.habit.HabitViewModel
import com.example.lifemanager.ui.plan.*
import com.example.lifemanager.ui.settings.*
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V1NavigationComposeTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var backDispatcher:OnBackPressedDispatcher
    private val f=EditorTestWorld(Dispatchers.IO)
    private val access=GenerationAccess(f.generations,f.coordinator)
    private val list=PlanViewModel(f.repo,f.cases,access,Dispatchers.IO)
    private val calendar=PlanCalendarViewModel(f.repo,access,Dispatchers.IO)
    private val prefs=MemoryV1Settings().apply { afterWrite={ f.enabled=rows.value.planReminders } }
    private val settings=V1SettingsViewModel(prefs,access,SettingsEffects(),Dispatchers.IO)
    private val nav=PlanNavigationViewModel(access,Dispatchers.IO)
    private val backupWorld=V1BackupSettingsViewModelTest.World(Dispatchers.IO,f.generations,f.coordinator)
    private val backup=backupWorld.model
    private val habit=HabitViewModel(EmptyHabits(),Dispatchers.IO,access)
    private val subscription=SubscriptionViewModel(EmptySubscriptions(),object:SubscriptionReminderSchedulerContract {
        override fun cancel(subscriptionId:Long,reminderDays:Set<Int>) {};override fun cancelAll(subscriptionId:Long) {}
    },prefs,Dispatchers.IO,access)
    @After fun cleanup() { listOf(f.model,list,calendar,settings,nav,habit,subscription,backup).forEach { it.viewModelScope.cancel() } }
    private fun waitFor(predicate:()->Boolean) { compose.waitUntil(5000) { compose.waitForIdle();predicate() } }
    private fun show() {
        compose.setContent { Box(Modifier.size(360.dp,720.dp)) {
            backDispatcher=checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            V1NavGraph(nav,list,f.model,calendar,settings,habit,subscription,backup)
        } }
        waitFor { f.model.uiState.value.isAvailable && settings.uiState.value.isAvailable && list.uiState.value.isAvailable }
    }
    @Test fun `four real destinations show actual pages rather than old separate tabs`() {
        show();compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo().assertExists()
        compose.onNodeWithText("待办提醒").assertDoesNotExist();compose.onNodeWithText("日程提醒").assertDoesNotExist()
        compose.onNodeWithContentDescription("打卡入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.HABIT }
        compose.onNodeWithContentDescription("添加习惯").assertExists()
        compose.onNodeWithContentDescription("订阅入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION }
        compose.onNodeWithContentDescription("添加订阅").assertExists()
        compose.onNodeWithContentDescription("计划入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.PLAN }
        compose.onNodeWithContentDescription("添加计划：重要且紧急").assertExists()
    }
    @Test fun `subscription notification reaches actual page once and closing detail does not replay it`() {
        show()
        compose.runOnIdle { nav.receive(PlanNotificationClick.Subscription(42,f.token)) }
        waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION && subscription.uiState.value.detailId==42L }
        compose.onNodeWithText("订阅不存在或已删除").assertExists()
        assertEquals(null,nav.uiState.value.pending)
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithContentDescription("订阅入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION }
        assertEquals(null,subscription.uiState.value.detailId)
    }
    @Test fun `subscription notification keeps dirty plan until user closes the original draft`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("保留计划输入")
        compose.runOnIdle { nav.receive(PlanNotificationClick.Subscription(42,f.token)) }
        waitFor { nav.uiState.value.confirmation!=null }
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("保留计划输入",f.model.uiState.value.draft!!.title)
        assertEquals(V1Destination.PLAN,nav.uiState.value.destination)
        compose.runOnIdle { f.model.requestClose(f.model.uiState.value.stamp);f.model.confirmClose(f.model.uiState.value.stamp) }
        waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION && subscription.uiState.value.detailId==42L }
        assertEquals(0,f.repo.writes)
    }
    @Test fun `subscription notification retains subscription draft and opens only after explicit close`() {
        show();compose.onNodeWithContentDescription("订阅入口").performClick()
        waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION && subscription.uiState.value.isAvailable }
        compose.runOnIdle { subscription.openEditor();subscription.updateEditor { it.copy(name="保留订阅输入") } }
        waitFor { !subscription.uiState.value.editor.isLoadingReminders }
        compose.runOnIdle { nav.receive(PlanNotificationClick.Subscription(42,f.token)) }
        waitFor { subscription.uiState.value.pendingNotificationId==42L }
        assertEquals("保留订阅输入",subscription.uiState.value.editor.name)
        compose.onNodeWithText("保存或关闭当前编辑后查看").assertExists()
        compose.runOnIdle { subscription.closeEditor(f.token) }
        waitFor { subscription.uiState.value.detailId==42L }
        assertEquals(null,nav.uiState.value.pending)
    }
    @Test fun `plan notification cannot silently discard subscription draft and stale consent cannot close new input`() {
        show();compose.onNodeWithContentDescription("订阅入口").performClick()
        waitFor { nav.uiState.value.destination==V1Destination.SUBSCRIPTION && subscription.uiState.value.isAvailable }
        compose.runOnIdle { subscription.openEditor();subscription.updateEditor { it.copy(name="原订阅输入") } }
        waitFor { !subscription.uiState.value.editor.isLoadingReminders }
        compose.runOnIdle { nav.receive(PlanNotificationClick.Legacy) }
        waitFor { nav.uiState.value.confirmation!=null }
        val old=assertNotNull(nav.uiState.value.confirmation)
        val oldConfirm=compose.onNodeWithText("放弃草稿并继续").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { subscription.updateEditor { it.copy(name="后来订阅输入") };oldConfirm() }
        assertEquals("后来订阅输入",subscription.uiState.value.editor.name)
        assertEquals(V1Destination.SUBSCRIPTION,nav.uiState.value.destination)
        waitFor { nav.uiState.value.confirmation!=old }
        compose.onNodeWithText("继续编辑").performClick()
        assertTrue(subscription.uiState.value.editor.isOpen)
        compose.runOnIdle { subscription.closeEditor(f.token) }
        waitFor { nav.uiState.value.destination==V1Destination.PLAN }
        assertTrue(nav.uiState.value.message!!.contains("失效"))
    }
    @Test fun `old generation subscription notification and busy arrival cannot route after maintenance`() {
        show()
        compose.runOnIdle { nav.receive(PlanNotificationClick.Subscription(42,DataGeneration(3))) }
        compose.waitForIdle();assertEquals(V1Destination.PLAN,nav.uiState.value.destination)
        frozen { compose.runOnIdle { nav.receive(PlanNotificationClick.Subscription(42,f.token)) } }
        compose.waitForIdle();assertEquals(V1Destination.PLAN,nav.uiState.value.destination)
        assertEquals(null,nav.uiState.value.pending);assertEquals(null,subscription.uiState.value.detailId)
    }
    @Test fun `unified settings exposes actual backup controls`() {
        show();compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithText("导出完整 V3 备份").performScrollTo().assertIsDisplayed().performClick()
        waitFor { backup.uiState.value.step==BackupStep.EXPORT_NOTICE }
        compose.onNodeWithText("取消").performClick();waitFor { !backup.uiState.value.busy }
        assertTrue(backupWorld.saved.isEmpty())
    }
    @Test fun `retained tab action is rejected while backup waits for privacy consent`() {
        show();compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        val old=compose.onNodeWithContentDescription("打卡入口").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { backup.start(BackupAction.EXPORT,f.token) };waitFor { backup.uiState.value.step==BackupStep.EXPORT_NOTICE }
        compose.runOnIdle { old() };compose.waitForIdle()
        assertEquals(V1Destination.SETTINGS,nav.uiState.value.destination)
    }
    @Test fun `retained preference action cannot write while backup is busy`() {
        show();compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo()
        val old=compose.onNodeWithContentDescription("计划提醒").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { backup.start(BackupAction.EXPORT,f.token) };waitFor { backup.uiState.value.step==BackupStep.EXPORT_NOTICE }
        compose.runOnIdle { old() };compose.waitForIdle()
        assertEquals(0,prefs.writes)
    }
    @Test fun `pending notification delivery excludes backup start even from a retained action`() {
        show();compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithText("导出完整 V3 备份").performScrollTo()
        val old=compose.onNodeWithText("导出完整 V3 备份").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var reads=0
        // Legacy receive captures and then rechecks; the third read is final delivery admission.
        f.generations.beforeCurrent={ if(++reads>=3) { entered.complete(Unit);release.await() } }
        try {
            compose.runOnIdle { nav.receive(PlanNotificationClick.Legacy) }
            waitFor { entered.isCompleted && nav.uiState.value.pending!=null }
            compose.runOnIdle { old() }
            assertEquals(BackupStep.IDLE,backup.uiState.value.step)
        } finally { f.generations.beforeCurrent={};release.complete(Unit) }
        waitFor { nav.uiState.value.destination==V1Destination.PLAN }
        compose.runOnIdle { old() };assertEquals(BackupStep.IDLE,backup.uiState.value.step)
    }
    @Test fun `cancelled backup maintenance retains draft and system back cannot leave confirmation`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        val originalStamp=f.model.uiState.value.stamp
        compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.runOnIdle { backup.start(BackupAction.CLEAR,f.token) };waitFor { backup.uiState.value.step==BackupStep.CLEAR_CHOICE }
        compose.onNodeWithText("跳过备份，继续确认").performClick();waitFor { backup.uiState.value.step==BackupStep.FINAL_CONFIRM }
        compose.runOnIdle { backDispatcher.onBackPressed() };compose.waitForIdle()
        assertEquals(V1Destination.SETTINGS,nav.uiState.value.destination)
        // Back may cancel the dialog; either path must retain the original draft and identity.
        if(backup.uiState.value.busy)compose.onNodeWithText("取消，保留原数据").performClick()
        waitFor { !backup.uiState.value.busy && !f.model.uiState.value.isMaintaining }
        assertEquals(originalStamp,f.model.uiState.value.stamp);assertNotNull(f.model.uiState.value.draft)
        assertEquals(0,backupWorld.commits)
    }
    @Test fun `successful maintenance invalidates retained draft and stale editor callback`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        val oldStamp=f.model.uiState.value.stamp
        compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.runOnIdle { backup.start(BackupAction.CLEAR,f.token) };waitFor { backup.uiState.value.step==BackupStep.CLEAR_CHOICE }
        compose.onNodeWithText("跳过备份，继续确认").performClick();waitFor { backup.uiState.value.step==BackupStep.FINAL_CONFIRM }
        compose.onNodeWithText("确认清空业务数据").performClick()
        waitFor { backup.uiState.value.outcome==V1BackupOutcome.COMMITTED && f.model.uiState.value.stamp.generation==DataGeneration(5) }
        compose.runOnIdle { f.model.change(PlanField.TITLE,"过期内容",oldStamp) }
        assertEquals(null,f.model.uiState.value.draft);assertEquals(1,backupWorld.commits)
    }
    @Test fun `tab dialog cancels without losing input and explicit discard switches`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("切页保留")
        compose.onNodeWithContentDescription("设置入口").performClick()
        waitFor { nav.uiState.value.confirmation!=null }
        compose.onNodeWithText("继续编辑").performClick()
        waitFor { nav.uiState.value.confirmation==null && nav.uiState.value.pending==null }
        assertEquals("切页保留",f.model.uiState.value.draft!!.title);assertEquals(V1Destination.PLAN,nav.uiState.value.destination)
        compose.onNodeWithContentDescription("设置入口").performClick()
        waitFor { nav.uiState.value.confirmation!=null }
        compose.onNodeWithText("放弃草稿并继续").performClick()
        waitFor { nav.uiState.value.destination==V1Destination.SETTINGS };assertEquals(0,f.repo.writes)
    }
    @Test fun `unified reminder control persists real preference independently`() {
        compose.setContent { LifeManagerTheme { V1SettingsScreen(settings,backup,{}) } };waitFor { settings.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo().performClick()
        waitFor { !prefs.rows.value.planReminders && !settings.uiState.value.isSaving }
        assertFalse(settings.uiState.value.settings!!.planReminders)
        assertEquals(true,prefs.rows.value.subscriptionReminders)
    }
    @Test fun `settings read error disables old controls and retry restores real data`() {
        compose.setContent { LifeManagerTheme { V1SettingsScreen(settings,backup,{}) } };waitFor { settings.uiState.value.isAvailable }
        prefs.failRead=true;prefs.signal.value++
        waitFor { !settings.uiState.value.isAvailable }
        compose.onNodeWithText("深色").assertIsNotEnabled();compose.onNodeWithText("重新读取设置").performScrollTo().assertExists()
        prefs.failRead=false;compose.onNodeWithText("重新读取设置").performClick();waitFor { settings.uiState.value.isAvailable }
    }
    @Test fun `expanded inline month survives leaving and returning through root destinations`() {
        show()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasContentDescription("展开月历"))
        compose.onNodeWithContentDescription("展开月历").performClick();waitFor { calendar.uiState.value.isAvailable && !calendar.uiState.value.isLoading }
        val selected=calendar.uiState.value.selectedDate
        compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithContentDescription("计划入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.PLAN }
        assertEquals(PlanCalendarMode.MONTH,calendar.uiState.value.mode);assertEquals(selected,calendar.uiState.value.selectedDate)
        compose.onNodeWithContentDescription("收起月历").assertExists()
    }
    @Test fun `retained root click cannot navigate after generation replacement`() {
        show();val old=compose.onNodeWithContentDescription("打卡入口").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance=false
        try {
            runBlocking { f.generations.commit(f.token) {} }
            waitFor { nav.uiState.value.generation==DataGeneration(5) && f.model.uiState.value.stamp.generation==DataGeneration(5) }
            compose.runOnIdle { old() };assertEquals(V1Destination.PLAN,nav.uiState.value.destination)
        } finally { compose.mainClock.autoAdvance=true }
    }
    @Test fun `dark narrow large font unified settings remain scrollable and actionable`() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
            LifeManagerTheme(themeMode=ThemeMode.DARK) { Box(Modifier.size(320.dp,640.dp)) { V1SettingsScreen(settings,backup,{}) } }
        } };waitFor { settings.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("订阅提醒").performScrollTo().performClick()
        waitFor { !settings.uiState.value.isSaving && !prefs.rows.value.subscriptionReminders }
        compose.onNodeWithText("返回计划").performScrollTo().assertExists()
    }
    @Test fun `retained reminder switch cannot write replacement preferences`() {
        compose.setContent { LifeManagerTheme { V1SettingsScreen(settings,backup,{}) } };waitFor { settings.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo()
        val old=compose.onNodeWithContentDescription("计划提醒").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance=false
        try {
            runBlocking { f.generations.commit(f.token) { prefs.rows.value=V1AppSettings(planReminders=false) } }
            waitFor { settings.uiState.value.generation==DataGeneration(5) && settings.uiState.value.isAvailable }
            compose.runOnIdle { old() };assertEquals(0,prefs.writes);assertFalse(prefs.rows.value.planReminders)
        } finally { compose.mainClock.autoAdvance=true }
    }
    @Test fun `changing unified reminder preference refreshes retained plan draft hint`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("设置入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.SETTINGS }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo().performClick()
        waitFor { !settings.uiState.value.isSaving && !prefs.rows.value.planReminders }
        compose.onNodeWithContentDescription("计划入口").performClick();waitFor { nav.uiState.value.destination==V1Destination.PLAN }
        waitFor { f.model.uiState.value.remindersEnabled==false }
        assertFalse(f.model.uiState.value.remindersEnabled!!);assertNotNull(f.model.uiState.value.draft)
    }
    @Test fun `system back cannot close frozen detail and cancellation keeps original pane`() {
        f.repo.rows.value=listOf(Plan(id=7,title="冻结详情",timeZone="UTC",createdAt=f.now,updatedAt=f.now))
        show();compose.runOnIdle { f.model.open(7,f.token) };waitFor { f.model.uiState.value.pane==PlanPane.DETAIL && f.model.uiState.value.isAvailable }
        frozen { compose.runOnIdle { backDispatcher.onBackPressed() };assertEquals(PlanPane.DETAIL,f.model.uiState.value.pane) }
        assertEquals(PlanPane.DETAIL,f.model.uiState.value.pane)
    }
    @Test fun `system back cannot propose discarding frozen dirty draft`() {
        show();compose.runOnIdle { f.model.openNew(f.token) };waitFor { f.model.uiState.value.draft!=null && f.model.uiState.value.isAvailable }
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("冻结保留")
        frozen { compose.runOnIdle { backDispatcher.onBackPressed() };assertFalse(f.model.uiState.value.confirmClose) }
        assertEquals("冻结保留",f.model.uiState.value.draft!!.title);assertEquals(0,f.repo.writes)
    }
    private fun frozen(block:()->Unit) {
        val release=CompletableDeferred<Unit>()
        val job=CoroutineScope(Dispatchers.IO).launch { f.coordinator.withSession { release.await() } }
        try { waitFor { nav.uiState.value.isMaintaining && f.model.uiState.value.isMaintaining };block() }
        finally { release.complete(Unit);runBlocking { job.join() };waitFor { !f.model.uiState.value.isMaintaining } }
    }
}

/** Persistence only is substituted. Real module owners/pages provide their empty-state behavior. */
private class EmptyHabits:HabitRepository {
    override fun observeHabits()=flowOf(emptyList<Habit>())
    override fun observeRecords(start:LocalDate,end:LocalDate)=flowOf(emptyList<HabitRecord>())
    override suspend fun saveHabit(habit:Habit):Long=error("not exercised")
    override suspend fun deleteHabit(id:Long)=error("not exercised")
    override suspend fun toggleRecord(habitId:Long,date:LocalDate):Boolean=error("not exercised")
}
private class EmptySubscriptions:SubscriptionRepository {
    override fun observeSubscriptions()=flowOf(emptyList<Subscription>())
    override fun observeAllPayments()=flowOf(emptyList<SubscriptionPayment>())
    override fun observePayments(subscriptionId:Long)=flowOf(emptyList<SubscriptionPayment>())
    override fun observeReminders(subscriptionId:Long)=flowOf(emptySet<Int>())
    override suspend fun getSubscriptions()=emptyList<Subscription>()
    override suspend fun getSubscription(id:Long):Subscription?=null
    override suspend fun getReminderDays(subscriptionId:Long)=emptySet<Int>()
    override suspend fun saveSubscription(subscription:Subscription,reminderDays:Set<Int>):Long=error("not exercised")
    override suspend fun savePayment(payment:SubscriptionPayment):Long=error("not exercised")
    override suspend fun deletePayment(id:Long)=error("not exercised")
    override suspend fun deleteSubscription(id:Long)=error("not exercised")
}
