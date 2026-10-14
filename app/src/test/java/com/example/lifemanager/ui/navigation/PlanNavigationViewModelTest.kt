package com.example.lifemanager.ui.navigation

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanNavigationViewModelTest {
    @Test fun `four destination selection is retained without losing list editor owner`()=scenario { f,n ->
        n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertEquals(V1Destination.HABIT,n.uiState.value.destination)
        n.requestDestination(V1Destination.PLAN,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination);assertNull(n.uiState.value.pending)
    }
    @Test fun `dirty draft requires consent and cancel changes neither route nor input`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val consent=assertNotNull(n.uiState.value.confirmation)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination)
        n.cancelLeave(consent);assertNull(n.uiState.value.pending);assertEquals("未保存草稿",f.model.uiState.value.draft!!.title)
        assertEquals(0,f.repo.writes)
    }
    @Test fun `explicit discard closes only the confirmed draft and selects destination`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        n.confirmLeave(assertNotNull(n.uiState.value.confirmation),f.model)
        assertEquals(V1Destination.SETTINGS,n.uiState.value.destination);assertNull(f.model.uiState.value.draft)
    }
    @Test fun `confirmation for modified draft cannot discard new content`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val old=assertNotNull(n.uiState.value.confirmation)
        f.model.change(PlanField.DESCRIPTION,"后来添加",f.model.uiState.value.stamp)
        n.confirmLeave(old,f.model)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination);assertEquals("后来添加",f.model.uiState.value.draft!!.description)
    }
    @Test fun `new notification waits for draft save then opens actual id once`()=scenario { f,n ->
        f.repo.rows.value=listOf(Plan(id=7,title="通知目标",timeZone="UTC",createdAt=f.now,updatedAt=f.now))
        draft(f);n.receive(PlanNotificationClick.Detail(PlanReminderToken(7,f.token,java.time.LocalDate.parse("2026-10-14"),f.now)))
        advanceUntilIdle();n.deliver(f.model);n.cancelLeave(assertNotNull(n.uiState.value.confirmation))
        assertNotNull(n.uiState.value.pending);assertEquals("未保存草稿",f.model.uiState.value.draft!!.title)
        n.deliver(f.model);assertNull(n.uiState.value.confirmation)
        f.model.save(f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model);advanceUntilIdle()
        assertEquals(7,f.model.uiState.value.detail!!.id);assertNull(n.uiState.value.pending)
        f.model.requestClose(f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertEquals(PlanPane.LIST,f.model.uiState.value.pane)
    }
    @Test fun `legacy notification cannot use reused old numeric identity`()=scenario { f,n ->
        f.repo.rows.value=listOf(Plan(id=7,title="新身份",timeZone="UTC",createdAt=f.now,updatedAt=f.now))
        n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        n.receive(PlanNotificationClick.Legacy);advanceUntilIdle();n.deliver(f.model)
        assertEquals(V1Destination.PLAN,n.uiState.value.destination);assertEquals(PlanPane.LIST,f.model.uiState.value.pane)
        assertNull(f.model.uiState.value.detail);assertTrue(n.uiState.value.message!!.contains("失效"))
    }
    @Test fun `old notification and rendered navigation stamp cannot target restored identities`()=scenario { f,n ->
        val old=f.model.uiState.value.stamp
        f.generations.commit(f.token) {};advanceUntilIdle()
        n.receive(PlanNotificationClick.Detail(PlanReminderToken(7,f.token,java.time.LocalDate.parse("2026-10-14"),f.now)))
        n.requestDestination(V1Destination.HABIT,old);advanceUntilIdle();n.deliver(f.model)
        assertNull(n.uiState.value.pending);assertEquals(V1Destination.PLAN,n.uiState.value.destination)
    }
    @Test fun `arrival and tab click during maintenance are rejected without replay`()=scenario { f,n ->
        val release=CompletableDeferred<Unit>();val task=launch { f.coordinator.withSession { release.await() } };runCurrent()
        n.receive(PlanNotificationClick.Legacy);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);runCurrent()
        release.complete(Unit);task.join();advanceUntilIdle();n.deliver(f.model)
        assertNull(n.uiState.value.pending);assertEquals(V1Destination.PLAN,n.uiState.value.destination)
    }
    @Test fun `old consent cannot confirm a superseding destination`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val old=assertNotNull(n.uiState.value.confirmation)
        n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        n.confirmLeave(old,f.model);assertEquals(V1Destination.PLAN,n.uiState.value.destination);assertNotNull(f.model.uiState.value.draft)
        n.confirmLeave(assertNotNull(n.uiState.value.confirmation),f.model);assertEquals(V1Destination.SETTINGS,n.uiState.value.destination)
    }
    @Test fun `clicking selected tab does not ask to discard its own draft`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.PLAN,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertNull(n.uiState.value.pending);assertNull(n.uiState.value.confirmation)
        assertEquals("未保存草稿",f.model.uiState.value.draft!!.title)
    }
    @Test fun `old close consent cannot discard draft while maintenance owns UI`()=scenario { f,_ ->
        draft(f);f.model.requestClose(f.model.uiState.value.stamp);val consent=f.model.uiState.value.stamp
        val release=CompletableDeferred<Unit>();val job=launch { f.coordinator.withSession { release.await() } };runCurrent()
        try {
            f.model.confirmClose(consent);assertNotNull(f.model.uiState.value.draft)
            f.model.cancelClose(consent);assertEquals("未保存草稿",f.model.uiState.value.draft!!.title)
        } finally { release.complete(Unit);job.join() };advanceUntilIdle()
    }
    @Test fun `newer arrival supersedes old consent before its enqueue can publish`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val old=assertNotNull(n.uiState.value.confirmation)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        f.generations.beforeCurrent={ if(++calls==1) { entered.complete(Unit);release.await() } }
        n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);runCurrent();assertTrue(entered.isCompleted)
        try { n.confirmLeave(old,f.model);assertNotNull(f.model.uiState.value.draft);assertEquals(V1Destination.PLAN,n.uiState.value.destination) }
        finally { release.complete(Unit);f.generations.beforeCurrent={} };advanceUntilIdle()
    }
    @Test fun `failed plan detail read does not block recovery navigation to settings`()=scenario { f,n ->
        f.repo.rows.value=listOf(Plan(id=7,title="读取失败目标",timeZone="UTC",createdAt=f.now,updatedAt=f.now))
        f.model.open(7,f.token);advanceUntilIdle();f.repo.readsFail=true;f.model.retryRead();advanceUntilIdle()
        assertFalse(f.model.uiState.value.isAvailable)
        n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertEquals(V1Destination.SETTINGS,n.uiState.value.destination)
    }
    @Test fun `rejected stale notification removes superseded dialog without discarding draft`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val old=assertNotNull(n.uiState.value.confirmation)
        n.receive(PlanNotificationClick.Detail(PlanReminderToken(7,DataGeneration(3),java.time.LocalDate.parse("2026-10-14"),f.now)))
        advanceUntilIdle();assertNull(n.uiState.value.confirmation);assertNull(n.uiState.value.pending)
        n.confirmLeave(old,f.model);assertEquals("未保存草稿",f.model.uiState.value.draft!!.title)
        n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        val fresh=assertNotNull(n.uiState.value.confirmation);n.cancelLeave(fresh)
        assertNull(n.uiState.value.confirmation);assertEquals(0,f.repo.writes)
    }
    @Test fun `rejected destination enqueue removes superseded consent and keeps input`()=scenario { f,n ->
        draft(f);n.requestDestination(V1Destination.HABIT,f.model.uiState.value.stamp);advanceUntilIdle();n.deliver(f.model)
        assertNotNull(n.uiState.value.confirmation)
        f.generations.nextCurrentFailures.add(IllegalStateException("read unavailable"))
        n.requestDestination(V1Destination.SETTINGS,f.model.uiState.value.stamp);advanceUntilIdle()
        assertNull(n.uiState.value.confirmation);assertNull(n.uiState.value.pending)
        assertEquals("未保存草稿",f.model.uiState.value.draft!!.title);assertEquals(V1Destination.PLAN,n.uiState.value.destination)
    }
    @Test fun `buffered older generation is invalidation only and cannot become navigation token`()=metadataScenario(false) { n,_ ->
        assertEquals(DataGeneration(4),n.uiState.value.generation)
    }
    @Test fun `unreadable live metadata fails closed and retry obtains fresh token without pending replay`()=metadataScenario(true) { n,repository ->
        assertNull(n.uiState.value.generation);assertNotNull(n.uiState.value.error)
        repository.broken=false;n.retryRead();advanceUntilIdle()
        assertEquals(DataGeneration(4),n.uiState.value.generation);assertNull(n.uiState.value.pending)
    }
    private class LaggingGenerations(var broken:Boolean):DataGenerationRepository {
        override fun observe()=flowOf(DataGeneration(3))
        override suspend fun current():DataGeneration { check(!broken);return DataGeneration(4) }
        override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit):DataGeneration=error("not exercised")
    }
    private fun metadataScenario(broken:Boolean,block:suspend TestScope.(PlanNavigationViewModel,LaggingGenerations)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val repository=LaggingGenerations(broken)
        val owner=PlanNavigationViewModel(GenerationAccess(repository,MaintenanceCoordinator(repository)),dispatcher)
        try { advanceUntilIdle();block(owner,repository) } finally { owner.viewModelScope.cancel();Dispatchers.resetMain() }
    }
    private suspend fun TestScope.draft(f:EditorTestWorld) {
        f.model.openNew(f.token);advanceUntilIdle();f.model.change(PlanField.TITLE,"未保存草稿",f.model.uiState.value.stamp)
    }
    private fun scenario(block:suspend TestScope.(EditorTestWorld,PlanNavigationViewModel)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=EditorTestWorld(dispatcher);val n=PlanNavigationViewModel(GenerationAccess(f.generations,f.coordinator),dispatcher)
        try { advanceUntilIdle();block(f,n) } finally { f.model.viewModelScope.cancel();n.viewModelScope.cancel();Dispatchers.resetMain() }
    }
}
