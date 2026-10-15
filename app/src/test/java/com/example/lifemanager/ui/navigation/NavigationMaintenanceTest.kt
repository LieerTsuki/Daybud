package com.example.lifemanager.ui.navigation

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.PausingMainDispatcher
import com.example.lifemanager.ui.plan.EditorTestWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

/** Replaces the three obsolete route-owner permutations with the active unified contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationMaintenanceTest {
    @Test fun replacementInvalidatesAnUnconsumedSubscriptionIdentity() = scenario { f,n,_ ->
        n.receive(PlanNotificationClick.Subscription(42,f.token));advanceUntilIdle()
        assertEquals(42L,assertNotNull(n.uiState.value.pending).subscriptionId)
        f.coordinator.withSession { session ->
            f.coordinator.withMaintenance(session) { f.generations.commit(session.generation) {} }
            runCurrent()
            assertNull(n.uiState.value.pending)
        }
        advanceUntilIdle()
        assertEquals(DataGeneration(5),n.uiState.value.generation)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination)
    }
    @Test fun readyFreezeRejectsNewArrivalsWithoutReplay() = scenario { f,n,_ ->
        f.coordinator.withSession {
            assertEquals(MaintenanceState.READY,f.coordinator.state.value)
            n.receive(PlanNotificationClick.Subscription(42,f.token));runCurrent()
            assertNull(n.uiState.value.pending)
        }
        advanceUntilIdle();assertNull(n.uiState.value.pending)
    }
    @Test fun drainingFreezeRejectsNewArrivalsWithoutReplay() = scenario { f,n,_ ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val writer=launch { f.coordinator.run(f.token) { entered.complete(Unit);release.await() } }
        entered.await()
        val owner=launch { f.coordinator.withSession { finish.await() } }
        try {
            runCurrent();assertEquals(MaintenanceState.DRAINING,f.coordinator.state.value)
            n.receive(PlanNotificationClick.Subscription(42,f.token));runCurrent();assertNull(n.uiState.value.pending)
        } finally { release.complete(Unit);writer.join();finish.complete(Unit);owner.join() }
        advanceUntilIdle();assertNull(n.uiState.value.pending)
    }
    @Test fun runningFreezeRejectsNewArrivalsWithoutReplay() = scenario { f,n,_ ->
        f.coordinator.withSession { session ->
            f.coordinator.withMaintenance(session) {
                assertEquals(MaintenanceState.RUNNING,f.coordinator.state.value)
                n.receive(PlanNotificationClick.Subscription(42,f.token));runCurrent();assertNull(n.uiState.value.pending)
            }
        }
        advanceUntilIdle();assertNull(n.uiState.value.pending)
    }
    @Test fun shortFreezeCannotQueueAnArrivalUntilIdle() = scenario { f,n,_ ->
        f.coordinator.withSession { n.receive(PlanNotificationClick.Subscription(42,f.token)) }
        advanceUntilIdle();assertNull(n.uiState.value.pending)
    }
    @Test fun cancelledMaintenanceKeepsDraftAndRejectsFrozenNotification() = scenario { f,n,_ ->
        f.model.openNew(f.token);advanceUntilIdle()
        f.model.change(com.example.lifemanager.ui.plan.PlanField.TITLE,"维护前输入",f.model.uiState.value.stamp)
        val release=CompletableDeferred<Unit>()
        val owner=launch { f.coordinator.withSession { release.await() } };runCurrent()
        n.receive(PlanNotificationClick.Subscription(42,f.token));runCurrent()
        owner.cancelAndJoin();advanceUntilIdle()
        assertEquals("维护前输入",f.model.uiState.value.draft!!.title)
        assertNull(n.uiState.value.pending);assertEquals(0,f.repo.writes)
    }
    @Test fun arrivalRetainsOriginalSourceBeforeItsDispatcherTurn() = scenario { f,n,_ ->
        n.receive(PlanNotificationClick.Subscription(42,f.token))
        f.generations.commit(f.token) {}
        advanceUntilIdle()
        assertNull(n.uiState.value.pending)
        n.receive(PlanNotificationClick.Subscription(42,f.token));advanceUntilIdle()
        assertNull(n.uiState.value.pending)
        n.receive(PlanNotificationClick.Subscription(42,DataGeneration(5)));advanceUntilIdle()
        assertEquals(DataGeneration(5),assertNotNull(n.uiState.value.pending).generation)
    }
    @Test fun maintenanceDrainsTheActualMainNavigationEffect() = scenario { f,n,main ->
        n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle()
        main.pauseNext=true
        val delivery=async { n.deliver(f.model) };runCurrent()
        assertTrue(main.hasHeldPublication)
        val release=CompletableDeferred<Unit>()
        val owner=launch { f.coordinator.withSession { release.await() } }
        try {
            runCurrent();assertEquals(MaintenanceState.DRAINING,f.coordinator.state.value)
            assertFalse(delivery.isCompleted)
            main.resumeHeld();runCurrent();delivery.await()
            assertEquals(MaintenanceState.READY,f.coordinator.state.value)
        } finally { main.resumeHeld();release.complete(Unit);owner.join();delivery.cancelAndJoin() }
    }
    @Test fun newerArrivalPreventsOlderMainEffectFromSelectingDestination() = scenario { f,n,main ->
        n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle()
        main.pauseNext=true
        val delivery=async { n.deliver(f.model) };runCurrent()
        try {
            assertTrue(main.hasHeldPublication)
            n.receive(PlanNotificationClick.Subscription(42,f.token));runCurrent()
            val newer=assertNotNull(n.uiState.value.pending)
            main.resumeHeld();runCurrent()
            assertFalse(delivery.await());assertEquals(newer,n.uiState.value.pending)
            assertEquals(V1Destination.PLAN,n.uiState.value.destination)
        } finally { main.resumeHeld();delivery.cancelAndJoin() }
    }
    private fun scenario(block:suspend TestScope.(EditorTestWorld,PlanNavigationViewModel,PausingMainDispatcher)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val main=PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val f=EditorTestWorld(dispatcher);val n=PlanNavigationViewModel(GenerationAccess(f.generations,f.coordinator),dispatcher)
        try { advanceUntilIdle();block(f,n,main) }
        finally { main.resumeHeld();f.model.viewModelScope.cancel();n.viewModelScope.cancel();runCurrent();Dispatchers.resetMain() }
    }
}
