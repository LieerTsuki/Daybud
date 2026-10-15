package com.example.lifemanager.data.local

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.data.plan.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.plan.*
import java.time.Clock
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Isolated real Room consumers; replacement is a synthetic transaction, not SAF restore. */
@RunWith(AndroidJUnit4::class)
class BusinessMaintenanceConsumerDeviceTest {
    @Test fun planDraftSurvivesCancelledMaintenanceAndOldActionsCannotEditReusedIdentity(): Unit = runBlocking {
        val fixture = V6DeviceFixture()
        val database = fixture.database
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC)
        val repository = PlanRepositoryImpl(database, database.planDao(), clock)
        val id = repository.save(Plan(title = "synthetic original", timeZone = "UTC", createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH), emptyList(), DataGeneration(0))
        val reminders = object : PlanReminderEffects {
            override suspend fun reconcile(planId: Long, expected: DataGeneration) = Unit
        }
        val cases = PlanUseCases(repository, coordinator, reminders)
        val model = PlanEditorViewModel(repository, cases, GenerationAccess(generations, coordinator),
            RoomPlanReminderSources(database.planDao()), Dispatchers.IO, clock)
        try {
            withTimeout(10_000) { model.uiState.first { it.isAvailable } }
            withContext(Dispatchers.Main.immediate) { model.open(id, DataGeneration(0)) }
            val detail = withTimeout(10_000) { model.uiState.first { it.isAvailable && it.detail?.id == id } }
            withContext(Dispatchers.Main.immediate) { model.beginEdit(detail.stamp) }
            withContext(Dispatchers.Main.immediate) { model.change(PlanField.TITLE, "synthetic old draft", model.uiState.value.stamp) }
            val draft = model.uiState.value.stamp
            coordinator.withSession {
                awaitActions(model) { model.save(draft) }
            }
            assertEquals("synthetic original", repository.get(id)!!.title)
            assertEquals("synthetic old draft", model.uiState.value.draft!!.title)
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    database.planDao().delete(id)
                    database.planDao().insert(PlanEntity(id = id, title = "synthetic restored", timeZone = "UTC", createdAt = 7, updatedAt = 8))
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.stamp.generation == DataGeneration(1) && it.isAvailable && !it.isMaintaining } }
            awaitActions(model) {
                model.change(PlanField.TITLE, "stale write", draft)
                model.save(draft)
                model.requestDelete(draft)
                model.open(id, DataGeneration(0))
            }
            assertNull(model.uiState.value.draft)
            assertEquals(PlanPane.LIST, model.uiState.value.pane)
            assertEquals("synthetic restored", repository.get(id)!!.title)
            assertEquals(DataGeneration(1), generations.current())
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            fixture.close()
        }
    }

    @Test fun subscriptionReplacementInvalidatesDraftWithoutEditingRestoredSameId(): Unit = runBlocking {
        val fixture = V6DeviceFixture()
        val database = fixture.database
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = SubscriptionRepositoryImpl(database)
        val today = LocalDate.now()
        repository.saveSubscription(Subscription(appName = "synthetic original", amountMinor = 100,
            billingCycle = BillingCycle.MONTHLY, nextBillingDate = today, startDate = today,
            createdAt = Instant.now(), updatedAt = Instant.now()), emptySet())
        val model = SubscriptionViewModel(repository, SubscriptionAlarms(), RoomV1SettingsRepository(database), Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { !it.isLoading && it.subscriptions.isNotEmpty() } }
            withContext(Dispatchers.Main.immediate) { model.openEditor(loaded.subscriptions.single(), loaded.generation) }
            withTimeout(10_000) { model.uiState.first { !it.editor.isLoadingReminders } }
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repository.saveSubscription(loaded.subscriptions.single().copy(appName = "synthetic restored"), emptySet())
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.subscriptions.singleOrNull()?.appName == "synthetic restored" && !it.isMaintaining } }
            awaitActions(model) {
                model.openEditor(loaded.subscriptions.single(), loaded.generation)
                model.cancelSubscription(loaded.subscriptions.single().id, loaded.generation)
                model.deleteSubscription(loaded.subscriptions.single().id, loaded.generation)
            }
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("synthetic restored", repository.getSubscriptions().single().appName)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            fixture.close()
        }
    }


    // A rejected callback must not merely have an unexecuted IO job when the assertion runs.
    private suspend fun awaitActions(model: ViewModel, actions: () -> Unit) {
        val jobs = withContext(Dispatchers.Main.immediate) {
            val existing = model.viewModelScope.coroutineContext.job.children.toSet()
            actions()
            model.viewModelScope.coroutineContext.job.children.filterNot(existing::contains).toList()
        }
        withTimeout(10_000) { jobs.forEach { it.join() } }
    }

    // Only Android alarm service is replaced. Room, repositories, ViewModels and coordinator are real.
    private class SubscriptionAlarms : SubscriptionReminderSchedulerContract {
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) = Unit
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = Unit
        override fun cancelAll(subscriptionId: Long) = Unit
    }
}
