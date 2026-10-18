package com.example.lifemanager.ui.subscription

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only UUID-owned synthetic rows are removed; the controller uses a dedicated AVD. */
@RunWith(AndroidJUnit4::class)
class SubscriptionBillsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun subscriptionFormDatePickerAndMonthlyBillsUseTheProductionDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = LifeManagerDatabaseFactory.open(context)
        val repository = SubscriptionRepositoryImpl(database)
        val ids = mutableListOf<Long>()
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        var scenario: ActivityScenario<MainActivity>? = null
        lateinit var activity: MainActivity
        fun model() = ViewModelProvider(activity)[SubscriptionViewModel::class.java]
        fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) { compose.waitForIdle(); condition() }
        try {
            val today = LocalDate.now()
            runBlocking(Dispatchers.IO) {
                listOf("阅读会员" to 2500L, "云端空间" to 1200L).forEachIndexed { index, (name, amount) ->
                    ids += repository.saveSubscription(Subscription(appName = "$name $suffix", amountMinor = amount,
                        billingCycle = BillingCycle.MONTHLY, nextBillingDate = today.plusDays(index.toLong()),
                        startDate = today.minusMonths(1), createdAt = Instant.now(), updatedAt = Instant.now()), emptySet())
                }
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity = it }
            waitFor { model().uiState.value.isAvailable && !model().uiState.value.isLoading }
            compose.onNodeWithContentDescription("订阅入口").performClick()
            waitFor { compose.onAllNodesWithText("订阅手账").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("订阅手账").assertIsDisplayed()
            compose.onNodeWithText("导出 CSV").assertDoesNotExist()
            screenshot("subscription-bills-list.png")
            compose.onNodeWithContentDescription("添加订阅").performClick()
            waitFor { !model().uiState.value.editor.isLoadingReminders }
            compose.onNodeWithText("订阅名称").performTextInput("音乐时光 $suffix")
            compose.onNodeWithText("金额").performTextInput("18.80")
            compose.onNodeWithText("分类（可选）").assertDoesNotExist()
            compose.onNodeWithText("备注（可选）").assertDoesNotExist()
            compose.onNodeWithText("币种（ISO 4217，例如 CNY）").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择下次扣费日期").performScrollTo().performClick()
            compose.onNodeWithText("确定").performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
                .performScrollToNode(hasText("扣费当天 09:00 提醒"))
            compose.onNodeWithText("扣费当天 09:00 提醒").assertIsDisplayed()
            screenshot("subscription-bills-editor.png")
            compose.onNodeWithText("保存订阅").performClick()
            waitFor { !model().uiState.value.editor.isOpen && model().uiState.value.subscriptions.any { it.appName == "音乐时光 $suffix" } }
            val saved = runBlocking(Dispatchers.IO) { repository.getSubscriptions().single { it.appName == "音乐时光 $suffix" } }
            ids += saved.id
            assertEquals(1880L, saved.amountMinor)
            assertEquals("CNY", saved.currency)
            assertTrue(saved.category.isNullOrEmpty() && saved.note.isNullOrEmpty())
            compose.onNodeWithText("账单").performClick()
            compose.onNodeWithContentDescription("上个月账单").assertIsDisplayed()
            compose.onNodeWithText("本月预计").assertExists()
            screenshot("subscription-bills-month.png")
            compose.onNodeWithContentDescription("下个月账单").performClick()
            compose.onNodeWithContentDescription("上个月账单").performClick()
            val matching = hasText("音乐时光 $suffix") and hasClickAction()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matching)
            compose.onNode(matching).performClick()
            waitFor { model().uiState.value.detailId == saved.id }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
                .performScrollToNode(hasText("实际扣费历史"))
            compose.onNodeWithText("实际扣费历史").assertIsDisplayed()
        } finally {
            scenario?.close()
            runBlocking(Dispatchers.IO) { ids.forEach { repository.deleteSubscription(it) } }
            database.close()
        }
    }
}
