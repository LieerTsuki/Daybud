package com.example.lifemanager.ui.plan

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {
    @Test fun `fresh snapshot includes completed records for quadrant history`() = scenario { f ->
        assertTrue(f.model.uiState.value.isAvailable)
        assertFalse(f.model.uiState.value.isLoading)
        assertEquals(DataGeneration(4),f.model.uiState.value.generation)
        assertEquals(listOf(1L,2L,3L),f.model.uiState.value.rows.map { it.plan.id })
    }
    @Test fun `filter drives real repository rows and preserves completion status selection`() = scenario { f ->
        f.model.onFilterChanged(PlanFilter(category=PlanCategory.TODO,status=PlanStatus.ALL,query="长期"),DataGeneration(4))
        advanceUntilIdle()
        assertEquals(listOf(1L,3L),f.model.uiState.value.rows.map { it.plan.id })
        assertEquals("长期",f.model.uiState.value.filter.query)
    }
    @Test fun `complete persists once and leaves original filter unchanged`() = scenario { f ->
        f.model.setCompleted(1,true,DataGeneration(4)); advanceUntilIdle()
        assertTrue(f.repo.rows.value.first { it.id==1L }.isCompleted)
        assertEquals(listOf(1L,2L,3L),f.model.uiState.value.rows.map { it.plan.id })
        assertEquals(PlanFilter(status=PlanStatus.ALL),f.model.uiState.value.filter)
        assertEquals(1,f.repo.writes)
        assertNotNull(f.model.uiState.value.message)
    }
    @Test fun `scheduled item also completes and completed selection can undo`() = scenario { f ->
        f.model.setCompleted(2,true,DataGeneration(4)); advanceUntilIdle()
        f.model.onFilterChanged(PlanFilter(status=PlanStatus.COMPLETED),DataGeneration(4)); advanceUntilIdle()
        assertEquals(listOf(2L,3L),f.model.uiState.value.rows.map { it.plan.id })
        f.model.setCompleted(2,false,DataGeneration(4)); advanceUntilIdle()
        assertFalse(f.repo.rows.value.first { it.id==2L }.isCompleted)
        assertEquals(listOf(3L),f.model.uiState.value.rows.map { it.plan.id })
    }
    @Test fun `duplicate clicks stay blocked through reminder reconciliation`() = scenario { f ->
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        f.effect={_,_->entered.complete(Unit);release.await()}
        f.model.setCompleted(1,true,DataGeneration(4)); runCurrent(); entered.await()
        assertEquals(setOf(1L),f.model.uiState.value.pendingIds)
        f.model.setCompleted(1,true,DataGeneration(4)); runCurrent()
        assertEquals(1,f.repo.writes)
        release.complete(Unit); advanceUntilIdle()
        assertTrue(f.model.uiState.value.pendingIds.isEmpty())
    }
    @Test fun `alarm failure reports persisted success and retries no business write`() = scenario { f ->
        f.effect={_,_->error("synthetic alarm failure")}
        f.model.setCompleted(1,true,DataGeneration(4)); advanceUntilIdle()
        assertTrue(f.repo.rows.value.first { it.id==1L }.isCompleted)
        assertEquals(setOf(1L),f.model.uiState.value.reminderFailures.keys)
        f.effect={_,_->}
        f.model.retryReminders(1,DataGeneration(4)); advanceUntilIdle()
        assertTrue(f.model.uiState.value.reminderFailures.isEmpty())
        assertEquals(1,f.repo.writes)
    }
    @Test fun `post commit cancellation retains receipt for side effect only retry`() = scenario { f ->
        f.effect={_,_->throw CancellationException("effect cancelled")}
        f.model.setCompleted(1,true,DataGeneration(4)); advanceUntilIdle()
        assertTrue(f.repo.rows.value.first { it.id==1L }.isCompleted)
        assertEquals(setOf(1L),f.model.uiState.value.reminderFailures.keys)
        assertTrue(f.model.uiState.value.pendingIds.isEmpty())
        f.effect={_,_->}; f.model.retryReminders(1,DataGeneration(4)); advanceUntilIdle()
        assertEquals(1,f.repo.writes)
        assertTrue(f.model.uiState.value.reminderFailures.isEmpty())
    }
    @Test fun `database failure does not claim saved or discard the row`() = scenario { f ->
        f.repo.writeFailure=IllegalStateException("disk failure")
        f.model.setCompleted(1,true,DataGeneration(4)); advanceUntilIdle()
        assertFalse(f.repo.rows.value.first { it.id==1L }.isCompleted)
        assertEquals(listOf(1L,2L,3L),f.model.uiState.value.rows.map { it.plan.id })
        assertTrue(f.model.uiState.value.reminderFailures.isEmpty())
        assertNotNull(f.model.uiState.value.message)
    }
    @Test fun `read failure disables writes without pretending empty success and retry recovers`() = scenario { f ->
        f.repo.readFailure=true; f.model.refresh(); advanceUntilIdle()
        assertFalse(f.model.uiState.value.isAvailable)
        assertNotNull(f.model.uiState.value.readError)
        assertEquals(listOf(1L,2L,3L),f.model.uiState.value.rows.map { it.plan.id })
        f.model.setCompleted(1,true,DataGeneration(4)); advanceUntilIdle()
        assertEquals(0,f.repo.writes)
        f.repo.readFailure=false; f.model.retryRead(); advanceUntilIdle()
        assertTrue(f.model.uiState.value.isAvailable)
        assertNull(f.model.uiState.value.readError)
    }
    @Test fun `exception failure also disables writes rather than showing cancelled occurrences`() = scenario { f ->
        f.repo.exceptionFailure=true; f.model.refresh(); advanceUntilIdle()
        assertFalse(f.model.uiState.value.isAvailable)
        assertNotNull(f.model.uiState.value.readError)
    }
    @Test fun `maintenance rejects clicks and never replays them after release`() = scenario { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val maintenance=launch { f.coordinator.withSession { entered.complete(Unit);release.await() } }
        runCurrent();entered.await();runCurrent()
        assertTrue(f.model.uiState.value.isMaintaining)
        assertEquals(listOf(1L,2L,3L),f.model.uiState.value.rows.map { it.plan.id })
        f.model.setCompleted(1,true,DataGeneration(4));runCurrent()
        release.complete(Unit);maintenance.join();advanceUntilIdle()
        assertEquals(0,f.repo.writes)
        assertFalse(f.model.uiState.value.isMaintaining)
    }
    @Test fun `old rendered generation cannot complete a replacement with same identity`() = scenario { f ->
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
            f.generations.commit(session.generation) { f.repo.rows.value=listOf(f.repo.rows.value.first().copy(title="替换记录")) }
        } };advanceUntilIdle()
        assertEquals(DataGeneration(5),f.model.uiState.value.generation)
        f.model.setCompleted(1,true,DataGeneration(4));advanceUntilIdle()
        assertEquals(0,f.repo.writes)
        assertEquals("替换记录",f.model.uiState.value.rows.single().plan.title)
    }
    @Test fun `old filter callbacks cannot overwrite a fresh generation selection`() = scenario { f ->
        f.generations.commit(DataGeneration(4)) {};advanceUntilIdle()
        f.model.onFilterChanged(PlanFilter(query="new"),DataGeneration(5));advanceUntilIdle()
        f.model.onFilterChanged(PlanFilter(query="stale"),DataGeneration(4));advanceUntilIdle()
        assertEquals("new",f.model.uiState.value.filter.query)
    }
    @Test fun `generation replacement clears old alarm receipts and messages`() = scenario { f ->
        f.effect={_,_->error("alarm failure")};f.model.setCompleted(1,true,DataGeneration(4));advanceUntilIdle()
        assertFalse(f.model.uiState.value.reminderFailures.isEmpty())
        f.generations.commit(DataGeneration(4)) {};advanceUntilIdle()
        assertTrue(f.model.uiState.value.reminderFailures.isEmpty())
        assertNull(f.model.uiState.value.message)
        f.model.retryReminders(1,DataGeneration(4));advanceUntilIdle()
        assertEquals(1,f.repo.writes)
    }
    @Test fun `hidden or deleted row cannot be completed by an obsolete list callback`() = scenario { f ->
        f.model.onFilterChanged(PlanFilter(category=PlanCategory.SCHEDULE),DataGeneration(4));advanceUntilIdle()
        f.model.setCompleted(1,true,DataGeneration(4));advanceUntilIdle()
        assertEquals(0,f.repo.writes)
        f.repo.rows.value=emptyList();advanceUntilIdle()
        f.model.setCompleted(2,true,DataGeneration(4));advanceUntilIdle()
        assertEquals(0,f.repo.writes)
    }

    @Test fun `retry read restarts a failed generation observation`() = scenario(startFailure=true) { f ->
        assertFalse(f.model.uiState.value.isAvailable)
        assertNotNull(f.model.uiState.value.readError)
        f.observationFailure=false
        f.model.retryRead();advanceUntilIdle()
        assertTrue(f.model.uiState.value.isAvailable)
        assertNull(f.model.uiState.value.readError)
    }
    @Test fun `display zone refresh recomputes today instead of retaining old window`() = scenario { f ->
        f.repo.rows.value=listOf(f.repo.rows.value[1].copy(dueAt=Instant.parse("2026-10-12T18:00:00Z")))
        f.model.onFilterChanged(PlanFilter(date=PlanDateFilter.TODAY),DataGeneration(4));advanceUntilIdle()
        assertEquals(listOf(2L),f.model.uiState.value.rows.map { it.plan.id })
        f.model.refresh(ZoneId.of("UTC"));advanceUntilIdle()
        assertTrue(f.model.uiState.value.rows.isEmpty())
        assertEquals(PlanDateFilter.TODAY,f.model.uiState.value.filter.date)
        assertEquals(ZoneId.of("UTC"),f.model.uiState.value.displayZone)
    }
    @Test fun `metadata failure after commit never reports business change as unsaved`() = scenario { f ->
        f.effect={_,_->f.generations.nextCurrentFailures.add(IllegalStateException("publication check failure"))}
        f.model.setCompleted(1,true,DataGeneration(4));advanceUntilIdle()
        assertTrue(f.repo.rows.value.first { it.id==1L }.isCompleted)
        assertFalse(f.model.uiState.value.message.orEmpty().contains("未保存"))
        assertEquals(setOf(1L),f.model.uiState.value.reminderFailures.keys)
        assertEquals(1,f.repo.writes)
    }

    private fun scenario(startFailure:Boolean=false,block: suspend TestScope.(Fixture)->Unit) = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=Fixture(dispatcher,startFailure)
        try { advanceUntilIdle();block(f) } finally { f.model.viewModelScope.cancel();advanceUntilIdle();Dispatchers.resetMain() }
    }
    private class Fixture(dispatcher: CoroutineDispatcher,startFailure:Boolean) {
        val generations=TestGenerations(4);val coordinator=MaintenanceCoordinator(generations)
        val now=Instant.parse("2026-10-13T04:00:00Z")
        val repo=MemoryPlans(now)
        var observationFailure=startFailure
        val observedGenerations=object:DataGenerationRepository by generations {
            override fun observe()=generations.observe().map { check(!observationFailure);it }
        }
        var effect:suspend (Long,DataGeneration)->Unit={_,_->}
        val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects {
            override suspend fun reconcile(planId:Long,expected:DataGeneration)=effect(planId,expected)
        })
        val model=PlanViewModel(repo,cases,GenerationAccess(observedGenerations,coordinator),dispatcher,Clock.fixed(now,ZoneId.of("Asia/Shanghai")))
    }
    private class MemoryPlans(val now:Instant):PlanRepository {
        private fun p(id:Long,title:String)=Plan(id=id,title=title,timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
        val rows=MutableStateFlow(listOf(p(1,"长期目标"),p(2,"今天日程").copy(timeMode=PlanTimeMode.DEADLINE,dueAt=now),p(3,"长期已完成").copy(isCompleted=true,completedAt=now)))
        var writes=0;var readFailure=false;var exceptionFailure=false;var writeFailure:Exception?=null
        override fun observeAll()=rows.map { check(!readFailure);it }
        override fun observeTags()=MutableStateFlow(emptyList<Tag>())
        override suspend fun get(id:Long)=rows.value.find { it.id==id }
        override suspend fun getExceptions(id:Long):List<PlanException> { check(!exceptionFailure);return emptyList() }
        override suspend fun findLegacyPlan(source:PlanLegacySource,legacyId:Long):Long?=null
        override suspend fun save(plan:Plan,tagNames:List<String>,expected:DataGeneration):Long=error("not in E1 scope")
        override suspend fun setCompleted(id:Long,completed:Boolean,expected:DataGeneration) {
            writeFailure?.let { throw it };check(rows.value.any { it.id==id });writes++
            rows.value=rows.value.map { if(it.id==id) if(completed) PlanRules.complete(it,now) else PlanRules.undoCompletion(it,now) else it }
        }
        override suspend fun delete(id:Long,expected:DataGeneration)=error("not in E1 scope")
        override suspend fun saveException(value:PlanException,expected:DataGeneration)=error("not in E1 scope")
    }
}
