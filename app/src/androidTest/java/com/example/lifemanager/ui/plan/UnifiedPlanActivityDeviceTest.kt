package com.example.lifemanager.ui.plan

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.plan.PlanEntity
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanReminderToken
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.notification.PlanNotificationHelper
import com.example.lifemanager.notification.PlanReminderIdentity
import com.example.lifemanager.notification.PlanReminderIntents
import com.example.lifemanager.ui.navigation.PlanNavigationViewModel
import com.example.lifemanager.ui.navigation.V1Destination
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Dedicated synthetic-data AVD only. Each test removes only its UUID-owned rows, never clears data. */
@RunWith(AndroidJUnit4::class)
class UnifiedPlanActivityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun owners() = ViewModelProvider(compose.activity, compose.activity.v1Factory)
    private fun editor() = owners()[PlanEditorViewModel::class.java]
    private fun plans() = owners()[PlanViewModel::class.java]
    private fun calendar() = owners()[PlanCalendarViewModel::class.java]
    private fun statistics() = owners()[PlanStatisticsViewModel::class.java]
    private fun navigation() = owners()[PlanNavigationViewModel::class.java]

    private fun waitFor(predicate: () -> Boolean) {
        compose.waitUntil(10_000) { compose.waitForIdle(); predicate() }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(matcher)
    }

    private fun clickAction(label: String) {
        val matcher = hasText(label) and hasClickAction()
        scrollTo(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
    }

    private fun addInHighQuadrant() {
        val matcher = hasContentDescription("添加计划：重要且紧急") and hasClickAction()
        scrollTo(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
    }

    private fun openHighQuadrantPlan(title: String, id: Long) {
        val matcher = hasText(title) and hasAnyAncestor(hasTestTag("plan-quadrant-HIGH"))
        scrollTo(hasTestTag("quadrant-plan-$id"))
        compose.onNode(matcher).assertIsDisplayed().performClick()
    }

    private fun ready() {
        waitFor {
            editor().uiState.value.let { it.isAvailable && !it.isLoading && !it.isBusy } &&
                plans().uiState.value.let { it.isAvailable && !it.isLoading } &&
                statistics().uiState.value.let { it.isAvailable && !it.isLoading && it.statistics != null } &&
                calendar().uiState.value.let { it.isAvailable && !it.isLoading } &&
                compose.onAllNodes(hasContentDescription("计划入口") and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }

    private fun awaitEditor() {
        waitFor {
            editor().uiState.value.let {
                it.pane == PlanPane.EDIT && it.draft != null && it.isAvailable && !it.isLoading && !it.isBusy
            }
        }
    }

    private fun awaitDetail(title: String) {
        waitFor {
            editor().uiState.value.let {
                it.pane == PlanPane.DETAIL && it.detail?.title == title && it.isAvailable && !it.isLoading && !it.isBusy
            }
        }
    }

    private fun returnToList() {
        clickAction("返回计划")
        waitFor {
            editor().uiState.value.let { it.pane == PlanPane.LIST && it.isAvailable && !it.isLoading && !it.isBusy }
        }
    }

    /** A separate read handle checks the real production file, and is always closed. */
    private fun ownedRows(title: String): List<PlanEntity> = runBlocking(Dispatchers.IO) {
        val database = LifeManagerDatabaseFactory.open(InstrumentationRegistry.getInstrumentation().targetContext)
        try { database.planDao().allEntities().filter { it.title == title } }
        finally { database.close() }
    }

    /** Recover even a committed row whose UI receipt was not reached, then use normal guarded deletion. */
    private fun cleanup(title: String) {
        waitFor { !editor().uiState.value.isBusy }
        compose.runOnIdle {
            val model = editor()
            model.requestClose(model.uiState.value.stamp)
            if (model.uiState.value.confirmClose) model.confirmClose(model.uiState.value.stamp)
        }
        waitFor { editor().uiState.value.pane == PlanPane.LIST && !editor().uiState.value.isLoading }
        ownedRows(title).forEach { owned ->
            compose.runOnIdle { editor().open(owned.id, editor().uiState.value.stamp.generation) }
            awaitDetail(title)
            assertEquals(owned.id, editor().uiState.value.detail!!.id)
            compose.runOnIdle {
                val model = editor()
                model.requestDelete(model.uiState.value.stamp)
                model.confirmDelete(model.uiState.value.stamp)
            }
            waitFor {
                editor().uiState.value.let { it.pane == PlanPane.LIST && !it.isBusy && !it.isLoading }
            }
        }
        assertTrue(ownedRows(title).isEmpty(), "Only the test-owned plan must be removed")
    }

    @Test fun realPlanUiKeepsIdentityThroughDateCompletionCalendarAndStatistics() {
        val title = "F设备闭环-${UUID.randomUUID()}"
        var failure: Throwable? = null
        try {
            ready()
            val baseline = assertNotNull(statistics().uiState.value.statistics)
            val day = calendar().uiState.value.selectedDate
            addInHighQuadrant()
            awaitEditor()
            compose.onNodeWithContentDescription("计划标题").performTextReplacement(title)
            // Keep this synthetic test from scheduling OS alarms while changing time modes.
            scrollTo(hasContentDescription("单项计划提醒"))
            compose.onNodeWithContentDescription("单项计划提醒").assertIsOn().performClick()
            waitFor { editor().uiState.value.draft?.reminderEnabled == false }
            clickAction("保存计划")
            awaitDetail(title)
            val original = assertNotNull(editor().uiState.value.detail)
            val id = original.id
            assertTrue(id > 0)
            assertEquals(PlanTimeMode.NONE, original.timeMode)
            assertEquals(TodoPriority.HIGH, original.priority)
            assertEquals(id, ownedRows(title).single().id)
            assertEquals(TodoPriority.HIGH, ownedRows(title).single().priority)

            // A date changes calendar placement, while manual quadrant and identity stay fixed.
            returnToList()
            waitFor {
                plans().uiState.value.let {
                    !it.isLoading && it.rows.any { row -> row.plan.id == id && row.plan.priority == TodoPriority.HIGH }
                }
            }
            openHighQuadrantPlan(title, id)
            awaitDetail(title)
            clickAction("编辑计划")
            awaitEditor()
            clickAction("截止日期")
            scrollTo(hasContentDescription("仅日期截止"))
            compose.onNodeWithContentDescription("仅日期截止").performClick()
            scrollTo(hasContentDescription("截止日期输入"))
            compose.onNodeWithContentDescription("截止日期输入").performTextReplacement(day.toString())
            clickAction("保存计划")
            awaitDetail(title)
            val dated = assertNotNull(editor().uiState.value.detail)
            assertEquals(id, dated.id)
            assertEquals(original.createdAt, dated.createdAt)
            assertEquals(PlanTimeMode.DEADLINE, dated.timeMode)
            assertEquals(TodoPriority.HIGH, dated.priority)
            assertEquals(day, dated.dueDate)
            assertNull(dated.dueAt)
            assertFalse(dated.reminderEnabled)
            val stored = ownedRows(title).single()
            assertEquals(id, stored.id)
            assertEquals(day.toEpochDay(), stored.dueDate)
            assertEquals(TodoPriority.HIGH, stored.priority)
            returnToList()
            waitFor {
                plans().uiState.value.let {
                    !it.isLoading && it.rows.any { row -> row.plan.id == id && row.plan.priority == TodoPriority.HIGH }
                }
            }
            openHighQuadrantPlan(title, id)
            awaitDetail(title)

            clickAction("勾选完成")
            waitFor { editor().uiState.value.let { !it.isBusy && it.detail?.let { plan -> plan.id == id && plan.isCompleted } == true } }
            assertTrue(ownedRows(title).single().isCompleted)
            clickAction("撤销完成")
            waitFor { editor().uiState.value.let { !it.isBusy && it.detail?.let { plan -> plan.id == id && !plan.isCompleted } == true } }
            assertFalse(ownedRows(title).single().isCompleted)
            returnToList()

            clickAction("统计")
            waitFor {
                statistics().uiState.value.let {
                    it.isAvailable && !it.isLoading && it.statistics?.totalCount == baseline.totalCount + 1 &&
                        it.statistics?.completedCount == baseline.completedCount
                }
            }
            scrollTo(hasContentDescription("全库计划完成进度"))
            compose.onNodeWithContentDescription("全库计划完成进度").assertIsDisplayed()
            assertEquals(baseline.pendingCount + 1, statistics().uiState.value.statistics!!.pendingCount)

            clickAction("日历")
            waitFor {
                calendar().uiState.value.let {
                    it.isAvailable && !it.isLoading && it.days[day].orEmpty().any { row -> row.plan.id == id }
                }
            }
            scrollTo(hasTestTag("plan-calendar-entry-$id"))
            compose.onNodeWithTag("plan-calendar-entry-$id").assertIsDisplayed().performClick()
            awaitDetail(title)
            assertEquals(id, editor().uiState.value.detail!!.id)
            assertEquals(TodoPriority.HIGH, editor().uiState.value.detail!!.priority)

            clickAction("编辑计划")
            awaitEditor()
            clickAction("无日期待办")
            compose.onNodeWithText("确认切换").performClick()
            clickAction("保存计划")
            awaitDetail(title)
            val undated = ownedRows(title).single()
            assertEquals(id, undated.id)
            assertEquals(TodoPriority.HIGH, undated.priority)
            assertEquals(PlanTimeMode.NONE, undated.timeMode)
            waitFor { calendar().uiState.value.let { !it.isLoading && it.days.values.flatten().none { row -> row.plan.id == id } } }
            returnToList()
            clickAction("四象限")
            openHighQuadrantPlan(title, id)
            awaitDetail(title)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanup(title) }
            catch (cleanupError: Throwable) { failure?.addSuppressed(cleanupError) ?: throw cleanupError }
        }
    }

    @Test fun newPlanDraftSurvivesRecreationAndRequiresExplicitTabDeparture() {
        val title = "F设备草稿-${UUID.randomUUID()}"
        var failure: Throwable? = null
        try {
            ready()
            addInHighQuadrant()
            awaitEditor()
            compose.onNodeWithContentDescription("计划标题").performTextReplacement(title)
            val retained = editor()
            val stamp = retained.uiState.value.stamp
            compose.activityRule.scenario.recreate()
            awaitEditor()
            assertSame(retained, editor())
            assertEquals(stamp, editor().uiState.value.stamp)
            compose.onNodeWithContentDescription("计划标题").assertTextContains(title)
            assertTrue(ownedRows(title).isEmpty(), "An unsaved draft must not create a Room row")

            compose.onNodeWithContentDescription("设置入口").assertIsEnabled().performClick()
            waitFor { navigation().uiState.value.confirmation != null }
            compose.onNodeWithText("继续编辑").performClick()
            waitFor { navigation().uiState.value.confirmation == null && navigation().uiState.value.pending == null }
            assertEquals(V1Destination.PLAN, navigation().uiState.value.destination)
            compose.onNodeWithContentDescription("计划标题").assertTextContains(title)
            assertEquals(title, editor().uiState.value.draft!!.title)

            compose.onNodeWithContentDescription("设置入口").performClick()
            waitFor { navigation().uiState.value.confirmation != null }
            compose.onNodeWithText("放弃草稿并继续").performClick()
            waitFor { navigation().uiState.value.destination == V1Destination.SETTINGS && navigation().uiState.value.pending == null }
            compose.onNodeWithContentDescription("设置入口").assertIsSelected()
            assertNull(editor().uiState.value.draft)
            assertEquals(PlanPane.LIST, editor().uiState.value.pane)
            assertTrue(ownedRows(title).isEmpty(), "Discarding the draft must not persist it")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanup(title) }
            catch (cleanupError: Throwable) { failure?.addSuppressed(cleanupError) ?: throw cleanupError }
        }
    }

    @Test fun strictPlanNotificationWaitsForDraftAndIsConsumedAcrossRecreation() {
        val targetTitle = "F设备通知目标-${UUID.randomUUID()}"
        val draftTitle = "F设备通知草稿-${UUID.randomUUID()}"
        var failure: Throwable? = null
        try {
            ready()
            addInHighQuadrant()
            awaitEditor()
            compose.onNodeWithContentDescription("计划标题").performTextReplacement(targetTitle)
            scrollTo(hasContentDescription("单项计划提醒"))
            compose.onNodeWithContentDescription("单项计划提醒").assertIsOn().performClick()
            waitFor { editor().uiState.value.draft?.reminderEnabled == false }
            clickAction("保存计划")
            awaitDetail(targetTitle)
            val target = assertNotNull(editor().uiState.value.detail)
            returnToList()
            addInHighQuadrant()
            awaitEditor()
            compose.onNodeWithContentDescription("计划标题").performTextReplacement(draftTitle)

            // Exercise the exact current click envelope through Android's real single-top lifecycle.
            // No notification is posted and no OS alarm is scheduled by this synthetic click.
            val token = PlanReminderToken(target.id, assertNotNull(editor().uiState.value.stamp.generation),
                LocalDate.now(), Instant.ofEpochMilli(System.currentTimeMillis()))
            compose.runOnIdle {
                val activity = compose.activity
                val click = PlanReminderIntents.metadata(Intent(activity, MainActivity::class.java)
                    .setAction(PlanNotificationHelper.OPEN_ACTION)
                    .setData(PlanReminderIdentity.data(token).toUri())
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), token)
                activity.startActivity(click)
            }
            waitFor { navigation().uiState.value.confirmation != null && navigation().uiState.value.pending?.planId == target.id }
            val ticket = assertNotNull(navigation().uiState.value.pending).ticket
            compose.onNodeWithText("继续编辑").performClick()
            waitFor { navigation().uiState.value.confirmation == null && navigation().uiState.value.waitingForDraft }
            compose.onNodeWithContentDescription("计划标题").assertTextContains(draftTitle)
            assertTrue(ownedRows(draftTitle).isEmpty())

            compose.activityRule.scenario.recreate()
            awaitEditor()
            assertEquals(ticket, navigation().uiState.value.pending?.ticket)
            assertTrue(navigation().uiState.value.waitingForDraft)
            assertNull(navigation().uiState.value.confirmation)
            compose.onNodeWithContentDescription("计划标题").assertTextContains(draftTitle)
            compose.onNodeWithText("继续编辑").assertDoesNotExist()

            clickAction("返回计划")
            waitFor { editor().uiState.value.confirmClose }
            compose.onNodeWithText("放弃草稿").performClick()
            awaitDetail(targetTitle)
            waitFor { navigation().uiState.value.pending == null && navigation().uiState.value.confirmation == null }
            assertEquals(target.id, editor().uiState.value.detail!!.id)
            assertTrue(ownedRows(draftTitle).isEmpty())
            assertEquals(target.id, ownedRows(targetTitle).single().id)

            val consumedStamp = editor().uiState.value.stamp
            compose.activityRule.scenario.recreate()
            awaitDetail(targetTitle)
            assertEquals(consumedStamp, editor().uiState.value.stamp)
            assertEquals(target.id, editor().uiState.value.detail!!.id)
            assertNull(navigation().uiState.value.pending)
            assertNull(navigation().uiState.value.confirmation)
            assertFalse(navigation().uiState.value.waitingForDraft)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            listOf(draftTitle, targetTitle).forEach { title ->
                try { cleanup(title) }
                catch (error: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = error else first.addSuppressed(error)
                }
            }
            cleanupFailure?.let { error -> failure?.addSuppressed(error) ?: throw error }
        }
    }
}
