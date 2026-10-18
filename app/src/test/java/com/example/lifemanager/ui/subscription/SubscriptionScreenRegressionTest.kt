package com.example.lifemanager.ui.subscription

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.V1AppSettings
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.V1SettingsRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SubscriptionScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: SubscriptionViewModel

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
    }

    private fun showSubscriptions() {
        val generations = TestGenerations(7)
        model = SubscriptionViewModel(Records(), NoAlarms, Preferences(), Dispatchers.IO,
            GenerationAccess(generations, MaintenanceCoordinator(generations)))
        compose.setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            LifeManagerTheme { SubscriptionContent(state, model, onExport = {}) }
        }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.isAvailable }
    }

    @Test fun newSubscriptionHasNoCurrencyCategoryNotesOrAdvanceReminderControls() {
        showSubscriptions()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); !model.uiState.value.editor.isLoadingReminders }
        compose.onNodeWithText("订阅名称").assertExists()
        compose.onNodeWithText("金额").assertExists()
        compose.onNodeWithText("币种（ISO 4217，例如 CNY）").assertDoesNotExist()
        compose.onNodeWithText("分类（可选）").assertDoesNotExist()
        compose.onNodeWithText("备注（可选）").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("扣费当天 09:00 提醒"))
        compose.onNodeWithText("扣费当天 09:00 提醒").assertExists()
    }

    @Test fun monthlyStatementCanSwitchMonthsAndOpenTheActualSubscription() {
        showSubscriptions()
        compose.onNodeWithText("导出 CSV").assertDoesNotExist()
        compose.onNodeWithText("账单").performClick()
        compose.onNodeWithText("本月预计").assertExists()
        compose.onNodeWithContentDescription("当月预计费用 ¥15.00").assertExists()
        compose.onNodeWithContentDescription("上个月账单").performClick()
        compose.onNodeWithContentDescription("下个月账单").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("维护期间保留的订阅"))
        compose.onNodeWithText("维护期间保留的订阅").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.detailId == 42L }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("实际扣费历史"))
        compose.onNodeWithText("实际扣费历史").assertExists()
    }

    @Test fun maintenanceMarksAddSubscriptionFabDisabledWhileKeepingAvailableSnapshot() {
        val generations = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(generations)
        val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        model = SubscriptionViewModel(Records(), NoAlarms, Preferences(), Dispatchers.IO,
            GenerationAccess(generations, coordinator))
        try {
            compose.setContent {
                val state by model.uiState.collectAsStateWithLifecycle()
                LifeManagerTheme { SubscriptionContent(state, model, onExport = {}) }
            }
            compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.isAvailable }
            compose.onNodeWithContentDescription("添加订阅").assertIsEnabled()
            maintenanceScope.launch { coordinator.withSession { awaitCancellation() } }
            compose.waitUntil(5000) {
                compose.waitForIdle()
                coordinator.state.value == MaintenanceState.READY && model.uiState.value.isMaintaining
            }
            assertTrue(model.uiState.value.isAvailable)
            compose.onNodeWithText("维护期间保留的订阅").assertExists()
            // An onClick guard alone still exposes an enabled button to Compose semantics/TalkBack.
            compose.onNodeWithContentDescription("添加订阅").assertIsNotEnabled()
        } finally { maintenanceScope.cancel() }
    }

    private object NoAlarms : SubscriptionReminderSchedulerContract {
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) = Unit
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = Unit
        override fun cancelAll(subscriptionId: Long) = Unit
    }

    private class Preferences : V1SettingsRepository {
        private val settings = MutableStateFlow(V1AppSettings())
        override fun observe() = settings
        override suspend fun update(expected: DataGeneration, transform: (V1AppSettings) -> V1AppSettings): V1AppSettings = error("Not used")
    }

    private class Records : SubscriptionRepository {
        private val subscriptions = MutableStateFlow(listOf(Subscription(
            id = 42, appName = "维护期间保留的订阅", amountMinor = 1500, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now().plusDays(10), startDate = LocalDate.now().minusMonths(1),
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )))
        private val payments = MutableStateFlow(emptyList<SubscriptionPayment>())
        override fun observeSubscriptions() = subscriptions
        override fun observeAllPayments() = payments
        override fun observePayments(subscriptionId: Long) = payments
        override fun observeReminders(subscriptionId: Long) = MutableStateFlow(emptySet<Int>())
        override suspend fun getSubscriptions() = subscriptions.value
        override suspend fun getSubscription(id: Long) = subscriptions.value.firstOrNull { it.id == id }
        override suspend fun getReminderDays(subscriptionId: Long) = emptySet<Int>()
        override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long = error("Not used")
        override suspend fun savePayment(payment: SubscriptionPayment): Long = error("Not used")
        override suspend fun deletePayment(id: Long): Unit = error("Not used")
        override suspend fun deleteSubscription(id: Long): Unit = error("Not used")
    }
}
