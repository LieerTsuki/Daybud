package com.example.lifemanager.ui.navigation

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.schedulers
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.LifeManagerApp
import com.example.lifemanager.MainActivity
import com.example.lifemanager.notification.ReminderGeneration
import com.example.lifemanager.notification.PlanNotificationHelper
import com.example.lifemanager.notification.PlanReminderIntents
import com.example.lifemanager.ui.plan.PlanEditorViewModel
import com.example.lifemanager.ui.plan.PlanViewModel
import com.example.lifemanager.ui.plan.PlanCalendarViewModel
import com.example.lifemanager.ui.plan.PlanStatisticsViewModel
import com.example.lifemanager.ui.settings.V1SettingsViewModel
import com.example.lifemanager.ui.settings.V1BackupSettingsViewModel
import com.example.lifemanager.ui.habit.HabitViewModel
import com.example.lifemanager.ui.plan.PlanPane
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.domain.plan.PlanStatus
import com.example.lifemanager.domain.plan.PlanRepository
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import java.util.concurrent.Executor

/** Exercises the actual Hilt Activity and production graph, including Intent consumption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeManagerApp::class, shadows = [V1ActivityActivationTest.ApplicationStartup::class])
@LooperMode(LooperMode.Mode.PAUSED)
class V1ActivityActivationTest {
    /** Robolectric does not run the merged manifest's AndroidX Startup provider. */
    @Implements(LifeManagerApp::class)
    class ApplicationStartup : ShadowApplication() {
        @RealObject lateinit var application: LifeManagerApp

        @Implementation
        fun onCreate() {
            val executor=Executor { it.run() }
            val configuration=Configuration.Builder().setTaskExecutor(executor).setExecutor(executor).build()
            val tasks=WorkManagerTaskExecutor(executor)
            val current=WorkManagerImpl(application,configuration,tasks,
                WorkDatabase.create(application,tasks.serialTaskExecutor,configuration.clock,true),schedulersCreator=schedulers())
            manager=current
            WorkManagerImpl.setDelegate(current)
            Shadow.directlyOn<Any?>(application, LifeManagerApp::class.java.name, "onCreate")
        }

        companion object {
            private var manager:WorkManagerImpl?=null
            @JvmStatic @Resetter
            fun reset() {
                try { manager?.close() }
                finally { manager=null;WorkManagerImpl.setDelegate(null) }
            }
        }
    }

    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity: MainActivity get() = controller.get()
    private val resources=object:ExternalResource() {
        override fun after() { releaseWorkManagerAndDatabase() }
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(resources).around(compose)
    private var applicationDatabase:RoomDatabase?=null

    @Before fun launchActualActivityAndCaptureDatabase() {
        // Direct lifecycle driving avoids ActivityScenario's SDK 28 lifecycle-event wait.
        controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup().windowFocusChanged(true)
        val repository=ReflectionHelpers.getField<PlanRepository>(activity.v1Factory,"plans")
        applicationDatabase=ReflectionHelpers.getField(repository,"database")
    }

    @After fun destroyActualActivity() {
        // close() handles partially started Activities and directly drives pause/stop/destroy.
        // Keep the Compose test environment alive until the Activity disposes its content.
        if (::controller.isInitialized) controller.close()
    }

    private fun releaseWorkManagerAndDatabase() {
        var failure:Throwable?=null
        fun closeResource(close:()->Unit) {
            try { close() }
            catch(error:Throwable) {
                val first=failure
                if(first==null)failure=error else first.addSuppressed(error)
            }
        }
        closeResource { ApplicationStartup.reset() }
        closeResource { applicationDatabase?.close() }
        applicationDatabase=null
        failure?.let { throw it }
    }

    private fun waitFor(predicate:()->Boolean) {
        compose.waitUntil(5000) { compose.waitForIdle();predicate() }
    }

    private fun scrollTo(matcher:SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(matcher)
    }

    private fun awaitEditorReady() {
        val editor=ViewModelProvider(activity,activity.v1Factory)[PlanEditorViewModel::class.java]
        waitFor { editor.uiState.value.let { it.pane==PlanPane.EDIT && it.draft!=null && it.isAvailable && !it.isLoading } }
    }

    private fun awaitDestination(destination:V1Destination) {
        waitFor {
            val owners=ViewModelProvider(activity,activity.v1Factory)
            owners[PlanNavigationViewModel::class.java].uiState.value.destination==destination &&
                (destination!=V1Destination.SETTINGS || owners[V1SettingsViewModel::class.java].uiState.value.let { it.isAvailable && !it.isLoading })
        }
    }

    private fun ready() {
        waitFor {
            compose.onAllNodes(hasContentDescription("添加计划：重要且紧急") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun addHighQuadrantPlan() {
        val add=hasContentDescription("添加计划：重要且紧急") and hasClickAction()
        scrollTo(add)
        compose.onNode(add).assertIsEnabled().performClick()
    }

    private fun subscriptionIntent(generation: Long = 0L) = Intent(activity, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 42L)
        .putExtra(ReminderGeneration.EXTRA, generation)

    @Test fun activityStartsRealUnifiedWorkspaceWithFourDestinations() {
        ready()
        listOf("计划入口", "打卡入口", "订阅入口", "设置入口").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        compose.onNodeWithContentDescription("待办").assertDoesNotExist()
        compose.onNodeWithContentDescription("日程").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置入口").performClick()
        awaitDestination(V1Destination.SETTINGS)
        compose.onNodeWithContentDescription("计划提醒").performScrollTo().assertExists()
        compose.onNodeWithText("导出完整 V2 备份").performScrollTo().assertExists()
    }

    @Test fun tabCyclesAndRotationRetainAllActualOwnersAndBackReturnsToPlan() {
        ready()
        val provider=ViewModelProvider(activity,activity.v1Factory)
        val types=listOf<Class<out ViewModel>>(PlanNavigationViewModel::class.java,PlanViewModel::class.java,
            PlanEditorViewModel::class.java,PlanCalendarViewModel::class.java,PlanStatisticsViewModel::class.java,
            V1SettingsViewModel::class.java,V1BackupSettingsViewModel::class.java)
        val owners=types.associateWith { provider[it] }
        val hilt=ViewModelProvider(activity)
        val habit=hilt[HabitViewModel::class.java]
        val subscription=hilt[SubscriptionViewModel::class.java]
        val editor=provider[PlanEditorViewModel::class.java]
        addHighQuadrantPlan()
        awaitEditorReady()
        val stamp=editor.uiState.value.stamp
        repeat(3) {
            listOf("打卡" to V1Destination.HABIT,"订阅" to V1Destination.SUBSCRIPTION,"设置" to V1Destination.SETTINGS).forEach { (label,destination) ->
                compose.onNodeWithContentDescription(label+"入口").performClick()
                awaitDestination(destination)
            }
        }
        controller.recreate()
        val reattached=ViewModelProvider(activity,activity.v1Factory)
        types.forEach { assertSame(owners[it],reattached[it]) }
        assertSame(habit,ViewModelProvider(activity)[HabitViewModel::class.java])
        assertSame(subscription,ViewModelProvider(activity)[SubscriptionViewModel::class.java])
        assertEquals(stamp,editor.uiState.value.stamp);assertNotNull(editor.uiState.value.draft)
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        awaitDestination(V1Destination.PLAN)
        compose.onNodeWithContentDescription("计划标题").assertExists()
    }

    @Test fun productionOwnersSaveCompleteAndReopenTheSameRoomPlanAcrossRotation() {
        ready()
        val editor=ViewModelProvider(activity,activity.v1Factory)[PlanEditorViewModel::class.java]
        val title="E6生产接线写入"
        addHighQuadrantPlan()
        awaitEditorReady()
        compose.onNodeWithContentDescription("计划标题").performTextReplacement(title)
        scrollTo(hasText("保存计划"))
        compose.onNodeWithText("保存计划").performClick()
        waitFor { editor.uiState.value.let { it.pane==PlanPane.DETAIL && it.detail!=null && !it.isBusy } }
        val id=requireNotNull(editor.uiState.value.detail).id
        assertTrue(id>0)
        assertEquals(PlanTimeMode.NONE,editor.uiState.value.detail!!.timeMode)
        assertEquals(TodoPriority.HIGH,editor.uiState.value.detail!!.priority)
        scrollTo(hasText("勾选完成"))
        compose.onNodeWithText("勾选完成").performClick()
        waitFor { editor.uiState.value.detail?.let { it.id==id && it.isCompleted }==true && !editor.uiState.value.isBusy }
        scrollTo(hasText("返回计划"))
        compose.onNodeWithText("返回计划").performClick()
        waitFor { editor.uiState.value.pane==PlanPane.LIST && !editor.uiState.value.isLoading }
        val list=SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
        val completedChip=hasContentDescription("重要且紧急：已完成 1 项") and hasClickAction()
        compose.onNode(list).performScrollToNode(completedChip)
        val plans=ViewModelProvider(activity,activity.v1Factory)[PlanViewModel::class.java]
        compose.onNode(completedChip).assertIsEnabled().performClick()
        waitFor { plans.uiState.value.let { state ->
            state.filter.status==PlanStatus.ALL && state.isAvailable && !state.isLoading &&
                state.rows.any { it.plan.id==id && it.plan.title==title && it.plan.isCompleted && it.plan.priority==TodoPriority.HIGH }
        } }
        compose.onNode(list).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("plan-quadrant-HIGH"))).performClick()
        waitFor { editor.uiState.value.detail?.let { it.id==id && it.isCompleted && it.title==title }==true }
        controller.recreate()
        assertSame(editor,ViewModelProvider(activity,activity.v1Factory)[PlanEditorViewModel::class.java])
        assertEquals(id,editor.uiState.value.detail!!.id)
        assertTrue(editor.uiState.value.detail!!.isCompleted)
        assertEquals(TodoPriority.HIGH,editor.uiState.value.detail!!.priority)
    }

    @Test fun rejectedMixedPlanEnvelopeCannotFallThroughToValidSubscriptionMetadata() {
        ready()
        val event=subscriptionIntent().setAction(PlanNotificationHelper.OPEN_ACTION)
            .putExtra(PlanReminderIntents.EXTRA_ID,7L)
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,event) }
        compose.waitForIdle()
        val owners=ViewModelProvider(activity,activity.v1Factory)
        assertNull(owners[PlanNavigationViewModel::class.java].uiState.value.pending)
        // Subscription is Hilt-owned; lookup through Activity's default factory reuses its instance.
        assertNull(ViewModelProvider(activity)[SubscriptionViewModel::class.java].uiState.value.detailId)
        compose.onNodeWithContentDescription("添加计划：重要且紧急").assertExists()
        assertFalse(event.hasExtra(MainActivity.EXTRA_SUBSCRIPTION_ID))
        assertFalse(event.hasExtra(ReminderGeneration.EXTRA))
    }

    @Test fun subscriptionNotificationWaitsForPlanDraftDecisionAndIsConsumedAcrossRecreation() {
        ready()
        addHighQuadrantPlan()
        awaitEditorReady()
        compose.onNodeWithContentDescription("计划标题").performTextReplacement("通知到达时保留")
        val event = subscriptionIntent()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, event) }
        waitFor { compose.onAllNodesWithText("继续编辑").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithContentDescription("计划标题").assertTextContains("通知到达时保留")
        compose.onNodeWithText("订阅不存在或已删除").assertDoesNotExist()
        compose.runOnIdle { assertFalse(event.hasExtra(MainActivity.EXTRA_SUBSCRIPTION_ID)) }
        // The retained event must survive rotation without requiring another Intent read.
        controller.recreate()
        awaitEditorReady()
        compose.onNodeWithContentDescription("计划标题").assertTextContains("通知到达时保留")
        scrollTo(hasText("返回计划"))
        compose.onNodeWithText("返回计划").performClick()
        compose.onNodeWithText("放弃草稿").performClick()
        waitFor {
            compose.onAllNodesWithText("订阅不存在或已删除").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("关闭", useUnmergedTree = true).performClick()
        controller.recreate()
        compose.onNodeWithText("订阅不存在或已删除").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加订阅").assertExists()
    }

    @Test fun staleSubscriptionNotificationCannotSwitchUnifiedDestinationOrOpenReusedIdentity() {
        ready()
        val event = subscriptionIntent(Long.MAX_VALUE)
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, event) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("添加计划：重要且紧急").assertExists()
        compose.onNodeWithText("订阅不存在或已删除").assertDoesNotExist()
        compose.runOnIdle { assertFalse(event.hasExtra(MainActivity.EXTRA_SUBSCRIPTION_ID)) }
    }
}
