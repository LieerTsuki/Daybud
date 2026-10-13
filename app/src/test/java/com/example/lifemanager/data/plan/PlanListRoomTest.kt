package com.example.lifemanager.data.plan

import android.app.Application
import androidx.room.Room
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.PlanViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PlanListRoomTest {
    private val now=Instant.parse("2026-10-13T04:00:00Z")
    private val clock=Clock.fixed(now,ZoneId.of("Asia/Shanghai"))
    private fun p(title:String)=Plan(title=title,timeZone="Asia/Shanghai",createdAt=now,updatedAt=now)

    @Test fun `list completion and undo use the same actual Room plan and tags`() = fixture { f ->
        val id=f.cases.save(p("长期目标"),listOf("学习"),DataGeneration(0)) {}.receipt.planId
        f.start();f.await { it.isAvailable && it.rows.size==1 }
        withContext(Dispatchers.Main) { f.model.setCompleted(id,true,DataGeneration(0)) }
        f.await { it.rows.isEmpty() && it.pendingIds.isEmpty() && it.message!=null }
        assertTrue(f.repo.get(id)!!.isCompleted)
        assertEquals(listOf("学习"),f.repo.get(id)!!.tagNames)
        withContext(Dispatchers.Main) { f.model.onFilterChanged(PlanFilter(status=PlanStatus.COMPLETED),DataGeneration(0)) }
        f.await { it.filter.status==PlanStatus.COMPLETED && it.rows.size==1 }
        withContext(Dispatchers.Main) { f.model.setCompleted(id,false,DataGeneration(0)) }
        f.await { it.rows.isEmpty() && it.pendingIds.isEmpty() }
        assertFalse(f.repo.get(id)!!.isCompleted)
        assertEquals(listOf(id),f.db.plans().allEntities().map { it.id })
    }
    @Test fun `exception table invalidation changes the real today list`() = fixture { f ->
        val id=f.cases.save(p("每日计划").copy(timeMode=PlanTimeMode.ALL_DAY,
            allDayStartDate=LocalDate.parse("2026-10-01"),allDayEndDate=LocalDate.parse("2026-10-01"),repeatRule=ScheduleRepeatRule.DAILY),emptyList(),DataGeneration(0)) {}.receipt.planId
        f.start();f.await { it.isAvailable }
        withContext(Dispatchers.Main) { f.model.onFilterChanged(PlanFilter(date=PlanDateFilter.TODAY),DataGeneration(0)) }
        f.await { it.filter.date==PlanDateFilter.TODAY && it.rows.size==1 }
        f.cases.saveException(PlanException(id,LocalDate.parse("2026-10-13"),true),DataGeneration(0)) {}
        f.await { it.rows.isEmpty() && it.isAvailable }
        assertEquals(listOf(PlanException(id,LocalDate.parse("2026-10-13"),true)),f.repo.getExceptions(id))
    }
    @Test fun `alarm failure still publishes persisted state and retry never writes completion again`() = fixture { f ->
        val id=f.cases.save(p("目标"),emptyList(),DataGeneration(0)) {}.receipt.planId
        f.start();f.await { it.isAvailable }
        f.failReminder=true
        withContext(Dispatchers.Main) { f.model.setCompleted(id,true,DataGeneration(0)) }
        f.await { it.reminderFailures.containsKey(id) && it.pendingIds.isEmpty() }
        val saved=f.repo.get(id)!!
        assertTrue(saved.isCompleted);assertEquals(now,saved.completedAt)
        f.failReminder=false
        withContext(Dispatchers.Main) { f.model.retryReminders(id,DataGeneration(0)) }
        f.await { it.reminderFailures.isEmpty() && it.pendingIds.isEmpty() }
        assertEquals(saved,f.repo.get(id));assertEquals(DataGeneration(0),f.generations.current())
    }

    @Test fun `series completion and undo preserve real cancellation and anchor`() = fixture { f ->
        val id=f.cases.save(p("重复系列").copy(timeMode=PlanTimeMode.ALL_DAY,
            allDayStartDate=LocalDate.parse("2026-10-01"),allDayEndDate=LocalDate.parse("2026-10-01"),repeatRule=ScheduleRepeatRule.DAILY),emptyList(),DataGeneration(0)) {}.receipt.planId
        val cancellation=PlanException(id,LocalDate.parse("2026-10-13"),true)
        f.cases.saveException(cancellation,DataGeneration(0)) {}
        f.start();f.await { it.isAvailable && it.rows.size==1 }
        withContext(Dispatchers.Main) { f.model.setCompleted(id,true,DataGeneration(0)) }
        f.await { it.rows.isEmpty() && it.pendingIds.isEmpty() }
        withContext(Dispatchers.Main) { f.model.onFilterChanged(PlanFilter(status=PlanStatus.COMPLETED),DataGeneration(0)) }
        f.await { it.rows.size==1 && it.filter.status==PlanStatus.COMPLETED }
        assertEquals(PlanOccurrenceTime.Completion(now),f.model.uiState.value.rows.single().occurrence!!.time)
        withContext(Dispatchers.Main) { f.model.setCompleted(id,false,DataGeneration(0)) }
        f.await { it.rows.isEmpty() && it.pendingIds.isEmpty() }
        withContext(Dispatchers.Main) { f.model.onFilterChanged(PlanFilter(date=PlanDateFilter.TODAY),DataGeneration(0)) }
        f.await { it.isAvailable && !it.isLoading && it.filter.date==PlanDateFilter.TODAY }
        assertTrue(f.model.uiState.value.rows.isEmpty())
        withContext(Dispatchers.Main) { f.model.onFilterChanged(PlanFilter(date=PlanDateFilter.TOMORROW),DataGeneration(0)) }
        f.await { it.rows.size==1 && it.filter.date==PlanDateFilter.TOMORROW }
        assertEquals(LocalDate.parse("2026-10-14"),f.model.uiState.value.rows.single().occurrence!!.occurrenceDate)
        assertEquals(listOf(cancellation),f.repo.getExceptions(id))
        assertEquals(LocalDate.parse("2026-10-01"),f.repo.get(id)!!.allDayStartDate)
    }

    @Test fun `real SQL generation replacement suppresses in flight receipt and reused ID callback`() = fixture { f ->
        coroutineScope {
            val id=f.cases.save(p("旧计划"),emptyList(),DataGeneration(0)) {}.receipt.planId
            f.start();f.await { it.isAvailable && it.rows.size==1 }
            val effectEntered=CompletableDeferred<Unit>();val effectRelease=CompletableDeferred<Unit>()
            f.effectHook={effectEntered.complete(Unit);effectRelease.await()}
            withContext(Dispatchers.Main) { f.model.setCompleted(id,true,DataGeneration(0)) }
            withTimeout(15_000) { effectEntered.await() }
            val maintenance=async(start=CoroutineStart.UNDISPATCHED) {
                f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) {
                    f.generations.commit(session.generation) {
                        val original=f.db.plans().findEntity(id)!!
                        f.db.plans().delete(id)
                        f.db.plans().insert(original.copy(title="替换记录",isCompleted=false,completedAt=null))
                    }
                } }
            }
            assertEquals(MaintenanceState.DRAINING,f.coordinator.state.value)
            effectRelease.complete(Unit);maintenance.await()
            f.await { it.generation==DataGeneration(1) && it.isAvailable && it.rows.singleOrNull()?.plan?.title=="替换记录" && it.pendingIds.isEmpty() }
            withContext(Dispatchers.Main) { f.model.setCompleted(id,true,DataGeneration(0)) }
            assertFalse(f.repo.get(id)!!.isCompleted)
            assertTrue(f.model.uiState.value.reminderFailures.isEmpty())
            assertNull(f.model.uiState.value.message)
            assertEquals(DataGeneration(1),f.generations.current())
        }
    }

    private inner class Fixture(val db:PlanMigrationTestDatabase) {
        val repo=PlanRepositoryImpl(db,db.plans(),clock)
        val generations=object:DataGenerationRepository {
            override fun observe()=callbackFlow {
                val observer=object:InvalidationTracker.Observer("app_maintenance") {
                    override fun onInvalidated(tables:Set<String>) { trySend(Unit) }
                }
                db.invalidationTracker.addObserver(observer);trySend(Unit)
                awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.map { current() }.distinctUntilChanged()
            override suspend fun current()=db.withTransaction {
                db.plans().read(SimpleSQLiteQuery("SELECT generation FROM app_maintenance WHERE id=1")).use { check(it.moveToFirst());DataGeneration(it.getLong(0)) }
            }
            override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit):DataGeneration=db.withTransaction {
                if(current()!=expected) throw StaleGenerationException()
                val next=DataGeneration(Math.addExact(expected.value,1))
                mutation();check(current()==expected)
                db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1 AND generation=?",arrayOf(next.value,expected.value))
                check(current()==next);next
            }
        }
        val coordinator=MaintenanceCoordinator(generations)
        @Volatile var failReminder=false
        var effectHook:suspend ()->Unit={}
        val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects {
            override suspend fun reconcile(planId:Long,expected:DataGeneration) { check(!failReminder);effectHook();repo.get(planId) }
        })
        lateinit var model:PlanViewModel
        fun start() { model=PlanViewModel(repo,cases,GenerationAccess(generations,coordinator),Dispatchers.IO,clock) }
        suspend fun await(predicate:(com.example.lifemanager.ui.plan.PlanUiState)->Boolean)=withTimeout(15_000) { model.uiState.first(predicate) }
        suspend fun close() { if(::model.isInitialized) model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun fixture(block:suspend (Fixture)->Unit)=runBlocking {
        val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(ui)
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),PlanMigrationTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).build()
        val f=Fixture(db)
        try { block(f) } finally {
            f.close()
            db.close();Dispatchers.resetMain();ui.close()
        }
    }
}
