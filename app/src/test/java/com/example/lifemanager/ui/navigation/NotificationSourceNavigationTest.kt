package com.example.lifemanager.ui.navigation

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSourceNavigationTest {
    @Test fun originalSubscriptionSourceIsNotRecapturedAfterReusedIdRestore() = scenario { f,n ->
        f.generations.commit(f.token) {};advanceUntilIdle()
        n.receive(PlanNotificationClick.Subscription(42,f.token));advanceUntilIdle()
        assertNull(n.uiState.value.pending)
        n.receive(PlanNotificationClick.Subscription(42,DataGeneration(5)));advanceUntilIdle()
        assertEquals(DataGeneration(5),assertNotNull(n.uiState.value.pending).generation)
    }
    @Test fun coldArrivalWithUnreadableMetadataCannotSucceedAfterRecoveryBeforeDispatch() = scenario { f,n ->
        f.generations.currentFailure=IllegalStateException("missing metadata")
        n.receive(PlanNotificationClick.Subscription(42,f.token))
        f.generations.currentFailure=null;advanceUntilIdle()
        assertNull(n.uiState.value.pending)
        n.receive(PlanNotificationClick.Subscription(43,f.token));advanceUntilIdle()
        assertEquals(43L,assertNotNull(n.uiState.value.pending).subscriptionId)
    }
    @Test fun invalidSubscriptionIdsDoNotDisplaceAdmittedEvent() = scenario { f,n ->
        n.receive(PlanNotificationClick.Subscription(42,f.token));advanceUntilIdle()
        val prior=assertNotNull(n.uiState.value.pending)
        n.receive(PlanNotificationClick.Subscription(0,f.token))
        n.receive(PlanNotificationClick.Subscription(-1,f.token));advanceUntilIdle()
        assertEquals(prior,n.uiState.value.pending)
    }
    @Test fun repeatedTapOwnsNewTicketAndOldDraftConsentCannotAcknowledgeIt() = scenario { f,n ->
        f.model.openNew(f.token);advanceUntilIdle()
        f.model.change(PlanField.TITLE,"保留输入",f.model.uiState.value.stamp)
        val click=PlanNotificationClick.Detail(PlanReminderToken(42,f.token,LocalDate.parse("2026-10-14"),f.now))
        n.receive(click);advanceUntilIdle();n.deliver(f.model)
        val old=assertNotNull(n.uiState.value.confirmation)
        n.receive(click);advanceUntilIdle();n.deliver(f.model)
        val fresh=assertNotNull(n.uiState.value.confirmation)
        assertNotEquals(old.ticket,fresh.ticket)
        assertFalse(n.confirmLeave(old,f.model))
        assertEquals(fresh,n.uiState.value.confirmation)
        assertEquals("保留输入",f.model.uiState.value.draft!!.title)
    }
    @Test fun draftDecisionWaitDoesNotHoldPermitAndReplacementRejectsThePendingTarget() = scenario { f,n ->
        f.model.openNew(f.token);advanceUntilIdle()
        f.model.change(PlanField.TITLE,"等待决定",f.model.uiState.value.stamp)
        n.receive(PlanNotificationClick.Subscription(42,f.token));advanceUntilIdle()
        // A missing/unready subscription owner must leave the original event pending.
        assertFalse(n.deliver(f.model));assertNotNull(n.uiState.value.pending)
        f.coordinator.withSession { session ->
            assertEquals(MaintenanceState.READY,f.coordinator.state.value)
            f.coordinator.withMaintenance(session) { f.generations.commit(session.generation) {} }
        }
        advanceUntilIdle()
        assertNull(n.uiState.value.pending)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination)
    }
    @Test fun busyEditorRetainsNotificationUntilWriteCompletesWithoutLosingSource() = scenario { f,n ->
        f.model.openNew(f.token);advanceUntilIdle()
        f.model.change(PlanField.TITLE,"真实保存",f.model.uiState.value.stamp)
        val release=CompletableDeferred<Unit>()
        f.repo.beforeSave={ release.await() }
        f.model.save(f.model.uiState.value.stamp);runCurrent()
        try {
            assertTrue(f.model.uiState.value.isBusy)
            n.receive(PlanNotificationClick.Legacy);runCurrent()
            assertFalse(n.deliver(f.model))
            assertEquals(f.token,assertNotNull(n.uiState.value.pending).generation)
        } finally { release.complete(Unit) }
        advanceUntilIdle();assertTrue(n.deliver(f.model));advanceUntilIdle()
        assertEquals(PlanPane.LIST,f.model.uiState.value.pane)
        assertNull(n.uiState.value.pending);assertEquals(1,f.repo.writes)
    }
    private fun scenario(block:suspend TestScope.(EditorTestWorld,PlanNavigationViewModel)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=EditorTestWorld(dispatcher);val n=PlanNavigationViewModel(GenerationAccess(f.generations,f.coordinator),dispatcher)
        try { advanceUntilIdle();block(f,n) }
        finally { f.model.viewModelScope.cancel();n.viewModelScope.cancel();runCurrent();Dispatchers.resetMain() }
    }
}
