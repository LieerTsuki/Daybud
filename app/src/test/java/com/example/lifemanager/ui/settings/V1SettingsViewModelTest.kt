package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.V1SettingsRepository
import com.example.lifemanager.ui.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class V1SettingsViewModelTest {
    @Test fun `fresh saved preferences publish all fields without two legacy switches`()=scenario { f ->
        assertEquals(V1AppSettings(),f.model.uiState.value.settings);assertTrue(f.model.uiState.value.isAvailable)
        f.model.setTheme(ThemeMode.DARK,f.token);advanceUntilIdle()
        f.model.setDateFormat(DateFormat.DMY,f.token);advanceUntilIdle()
        f.model.setCurrency(" usd ",f.token);advanceUntilIdle()
        f.model.setReminderDay(3,true,f.token);advanceUntilIdle()
        assertEquals(V1AppSettings(theme=ThemeMode.DARK,dateFormat=DateFormat.DMY,defaultCurrency="USD",defaultReminderDays=setOf(3)),f.repo.rows.value)
    }
    @Test fun `plan switch does not change subscription switch or other preferences`()=scenario { f ->
        f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token);advanceUntilIdle()
        assertFalse(f.repo.rows.value.planReminders);assertTrue(f.repo.rows.value.subscriptionReminders)
        assertEquals(1,f.effects.applied);assertEquals(1,f.effects.requested)
        f.model.setReminderEnabled(V1ReminderModule.SUBSCRIPTION,false,f.token);advanceUntilIdle()
        assertFalse(f.model.uiState.value.settings!!.subscriptionReminders);assertFalse(f.repo.rows.value.planReminders)
    }
    @Test fun `invalid currency and reminder days retain stored settings`()=scenario { f ->
        f.model.setCurrency("not currency",f.token);advanceUntilIdle();assertEquals("CNY",f.repo.rows.value.defaultCurrency)
        f.model.setReminderDay(2,true,f.token);advanceUntilIdle();assertTrue(f.repo.rows.value.defaultReminderDays.isEmpty())
        assertNotNull(f.model.uiState.value.errorMessage);assertEquals(0,f.repo.writes)
    }
    @Test fun `read failure disables changes and explicit retry subscribes again`()=scenario { f ->
        f.repo.failRead=true;f.repo.signal.update { it+1 };advanceUntilIdle()
        assertFalse(f.model.uiState.value.isAvailable)
        f.model.setTheme(ThemeMode.DARK,f.token);advanceUntilIdle();assertEquals(0,f.repo.writes)
        f.repo.failRead=false;f.model.retry();advanceUntilIdle();assertTrue(f.model.uiState.value.isAvailable)
    }
    @Test fun `reminder failure reports committed preference and retries only effects`()=scenario { f ->
        f.effects.failure=IllegalStateException("alarm failed")
        f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token);advanceUntilIdle()
        assertFalse(f.repo.rows.value.planReminders);assertEquals(1,f.repo.writes)
        assertTrue(f.model.uiState.value.remindersPending);assertTrue(f.model.uiState.value.errorMessage!!.contains("已保存"))
        f.effects.failure=null;f.model.retryReminders(f.token);advanceUntilIdle()
        assertFalse(f.model.uiState.value.remindersPending);assertEquals(1,f.repo.writes)
    }
    @Test fun `committed read failure is not misreported as uncommitted save`()=scenario { f ->
        f.repo.afterWrite={ f.repo.failRead=true }
        f.model.setTheme(ThemeMode.DARK,f.token);advanceUntilIdle()
        assertEquals(ThemeMode.DARK,f.repo.rows.value.theme);assertFalse(f.model.uiState.value.isAvailable)
        assertTrue(f.model.uiState.value.errorMessage!!.contains("已保存"));assertFalse(f.model.uiState.value.isSaving)
    }
    @Test fun `maintenance rejects new changes without replay and cancel keeps preferences`()=scenario { f ->
        val release=CompletableDeferred<Unit>();val task=launch { f.coordinator.withSession { release.await() } };runCurrent()
        f.model.setTheme(ThemeMode.DARK,f.token);runCurrent();assertEquals(0,f.repo.writes)
        release.complete(Unit);task.join();advanceUntilIdle();assertEquals(ThemeMode.SYSTEM,f.repo.rows.value.theme)
    }
    @Test fun `replacement clears old preference and rejects retained callbacks`()=scenario { f ->
        f.generations.commit(f.token) { f.repo.rows.value=V1AppSettings(defaultCurrency="EUR") };advanceUntilIdle()
        assertEquals(DataGeneration(5),f.model.uiState.value.generation)
        f.model.setCurrency("USD",f.token);advanceUntilIdle()
        assertEquals("EUR",f.repo.rows.value.defaultCurrency);assertEquals(0,f.repo.writes)
    }
    @Test fun `cancellation immediately after commit keeps saved fact and effect only retry`()=scenario { f ->
        f.repo.afterWrite={ currentCoroutineContext()[com.example.lifemanager.domain.repository.SettingsWriteCancellation]!!.writer.cancel() }
        f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token);advanceUntilIdle()
        assertFalse(f.repo.rows.value.planReminders);assertEquals(1,f.repo.writes)
        assertTrue(f.model.uiState.value.committed);assertTrue(f.model.uiState.value.remindersPending)
        assertFalse(f.model.uiState.value.isSaving);assertTrue(f.model.uiState.value.errorMessage!!.contains("已保存"))
        f.repo.afterWrite={};f.model.retryReminders(f.token);advanceUntilIdle();assertEquals(1,f.repo.writes)
        assertFalse(f.model.uiState.value.remindersPending)
    }
    @Test fun `broken metadata during reminder retry disables controls without coroutine escape`()=scenario { f ->
        f.effects.failure=IllegalStateException("alarm failed")
        f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token);advanceUntilIdle()
        f.generations.currentFailure=IllegalStateException("metadata unavailable")
        f.model.retryReminders(f.token);advanceUntilIdle()
        assertFalse(f.model.uiState.value.isAvailable);assertFalse(f.model.uiState.value.isSaving)
        assertEquals(1,f.repo.writes);assertTrue(f.model.uiState.value.remindersPending)
        assertEquals(1,f.effects.requested) // Rejected retry must not enqueue a new global effect.
    }
    @Test fun `cancel during suspended reminder effects retains commit and retries no preference write`()=scenario { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.effects.beforeReconcile={ entered.complete(Unit);release.await() }
        val previous=f.model.viewModelScope.coroutineContext.job.children.toSet()
        f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token)
        val operation=f.model.viewModelScope.coroutineContext.job.children.first { it !in previous }
        runCurrent();assertTrue(entered.isCompleted);assertFalse(f.repo.rows.value.planReminders)
        operation.cancel();advanceUntilIdle()
        assertTrue(f.model.uiState.value.committed);assertTrue(f.model.uiState.value.remindersPending)
        assertFalse(f.model.uiState.value.isSaving);assertEquals(1,f.repo.writes)
        release.complete(Unit);f.effects.beforeReconcile={};f.model.retryReminders(f.token);advanceUntilIdle()
        assertFalse(f.model.uiState.value.remindersPending);assertEquals(1,f.repo.writes)
    }
    @Test fun `cancel while Main handoff is paused still publishes committed setting`()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val gate=HoldingMain(dispatcher);Dispatchers.setMain(gate)
        val f=World(dispatcher);var writer:Job?=null
        try {
            advanceUntilIdle();f.repo.afterWrite={ writer=currentCoroutineContext()[com.example.lifemanager.domain.repository.SettingsWriteCancellation]!!.writer }
            gate.hold=true;f.model.setReminderEnabled(V1ReminderModule.PLAN,false,f.token);runCurrent()
            assertFalse(f.repo.rows.value.planReminders);assertTrue(gate.hasPending());assertNotNull(writer)
            writer.cancel();gate.release();advanceUntilIdle()
            assertFalse(f.model.uiState.value.settings!!.planReminders);assertTrue(f.model.uiState.value.committed)
            assertFalse(f.model.uiState.value.isSaving);assertTrue(f.model.uiState.value.remindersPending)
            assertEquals(1,f.repo.writes)
        } finally { gate.release();f.model.viewModelScope.cancel();runCurrent();Dispatchers.resetMain() }
    }
    /** Test-only UI dispatch gate; production receives no test hooks. */
    private class HoldingMain(private val target:CoroutineDispatcher):CoroutineDispatcher() {
        var hold=false
        private val pending=ArrayDeque<Pair<CoroutineContext,Runnable>>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {
            if(hold)pending.addLast(context to block)else target.dispatch(context,block)
        }
        fun hasPending()=pending.isNotEmpty()
        fun release() { hold=false;while(pending.isNotEmpty()) { val (context,block)=pending.removeFirst();target.dispatch(context,block) } }
    }
    private fun scenario(block:suspend TestScope.(World)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=World(dispatcher)
        try { advanceUntilIdle();block(f) } finally { f.model.viewModelScope.cancel();Dispatchers.resetMain() }
    }
    class World(dispatcher:CoroutineDispatcher) {
        val token=DataGeneration(4);val generations=TestGenerations(4);val coordinator=MaintenanceCoordinator(generations)
        val repo=MemoryV1Settings();val effects=SettingsEffects()
        val model=V1SettingsViewModel(repo,GenerationAccess(generations,coordinator),effects,dispatcher)
    }
}

class MemoryV1Settings:V1SettingsRepository {
    val rows=MutableStateFlow(V1AppSettings());val signal=MutableStateFlow(0)
    var failRead=false;var writes=0;var afterWrite:suspend ()->Unit={}
    override fun observe()=combine(rows,signal) { value,_->check(!failRead);value }
    override suspend fun update(expected:DataGeneration,transform:(V1AppSettings)->V1AppSettings):V1AppSettings {
        check(!failRead);val next=transform(rows.value);require(V1SettingsRules.validate(next)==null)
        rows.value=next;writes++;afterWrite();return next
    }
}
class SettingsEffects:MaintenanceReminderEffects {
    var applied=0;var requested=0;var failure:Exception?=null
    var beforeReconcile:suspend ()->Unit={}
    override suspend fun clearPrevious(identities:ReminderIdentities) { error("not used by preferences") }
    override suspend fun reconcile() { applied++;beforeReconcile();failure?.let { throw it } }
    override fun requestReconciliation() { requested++ }
}
