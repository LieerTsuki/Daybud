package com.example.lifemanager.ui.diary

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.lifemanager.MainActivity
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity and production persistence; only the test's uniquely named entry is deleted. */
@RunWith(AndroidJUnit4::class)
class DiaryActivityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fiveTabsDraftAutosaveTabDepartureRecreationCompletionAndCalendar() {
        val title = "设备日记-${UUID.randomUUID()}"
        val body = "今天在设备上写下完整的日记正文，切换页面后继续记录。"
        val originalOrientation = compose.activity.requestedOrientation
        var ownedDraftId: String? = null
        try {
            listOf("计划", "打卡", "日记", "订阅", "设置").forEach(::selectTab)
            selectTab("日记")
            node("写日记").performClick()
            awaitText("标题（可不填）")
            ownedDraftId = owner().uiState.value.draft?.id
            node("标题（可不填）").performTextInput(title)
            node("平静").performClick().assertIsSelected()
            node("记下今天的片段").performTextInput(body)
            closeSoftKeyboard()
            // Real time, real Room: the saved indicator must follow the debounce write.
            compose.waitUntil(10_000) {
                val state = owner().uiState.value
                state.draft?.body == body && state.saveStatus == DiarySaveStatus.SAVED
            }
            node("草稿已保存").assertIsDisplayed()
            selectTab("订阅")
            selectTab("日记")
            node(body).assertExists()
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            node(title).assertExists()
            node(body).assertExists()
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            compose.activityRule.scenario.recreate()
            awaitSelected("日记")
            node(title).assertExists()
            node("平静").assertIsSelected()
            node(body).assertExists()
            node("完成").performClick()
            awaitText("编辑")
            node(title).assertIsDisplayed()
            node(body).assertIsDisplayed()
            node("${LocalDate.now()} · 平静").assertIsDisplayed()
            node("返回").performClick()
            awaitText("日历")
            node("日历").performClick()
            node(YearMonth.now().toString()).assertIsDisplayed()
            node(LocalDate.now().dayOfMonth.toString())
                .performClick()
            val todayCount = owner().uiState.value.entries.count { it.date == LocalDate.now() }
            node("${LocalDate.now()} · ${todayCount}篇").assertIsDisplayed()
            node(title).performClick()
            awaitText("编辑")
            node(body).assertIsDisplayed()
        } finally {
            compose.runOnIdle { compose.activity.requestedOrientation = originalOrientation }
            // Use the same public UI owner, never reset application data or unrelated records.
            compose.runOnIdle {
                val vm = owner()
                val state = vm.uiState.value
                when {
                    ownedDraftId != null && state.draft?.id == ownedDraftId -> vm.askDiscard(state.stamp)
                    state.detail?.title == title -> vm.askDelete(state.stamp)
                    else -> state.entries.find { it.title == title }?.let { vm.openEntry(it.id, state.stamp) }
                }
            }
            compose.runOnIdle {
                val vm = owner(); val state = vm.uiState.value
                if (state.detail?.title == title && !state.confirmDelete) vm.askDelete(state.stamp)
            }
            compose.runOnIdle {
                val vm = owner(); val state = vm.uiState.value
                if (state.confirmDelete && state.detail?.title == title) vm.confirmDelete(state.stamp)
                else if (state.confirmDiscard && state.draft?.id == ownedDraftId) vm.confirmDiscard(state.stamp)
            }
            compose.waitUntil(10_000) {
                owner().uiState.value.let { state ->
                    !state.busy && state.entries.none { it.title == title } && state.drafts.none { it.id == ownedDraftId }
                }
            }
        }
    }

    private fun node(text: String): SemanticsNodeInteraction {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun owner() = ViewModelProvider(compose.activity, compose.activity.diaryFactory)[DiaryViewModel::class.java]
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { owner().uiState.value.let { !it.busy && !it.loading } }
        node(text).assertExists()
    }
    private fun selectTab(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(label + "入口") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription(label + "入口").performClick()
        awaitSelected(label)
    }
    private fun awaitSelected(label: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription(label + "入口") and isSelected()).fetchSemanticsNodes().size == 1
    }
}
