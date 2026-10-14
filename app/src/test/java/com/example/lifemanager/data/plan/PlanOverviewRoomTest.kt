package com.example.lifemanager.data.plan

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import java.time.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PlanOverviewRoomTest {
    private val now=Instant.parse("2026-10-14T04:00:00Z")
    private val day=LocalDate.parse("2026-10-14")
    private val clock=Clock.fixed(now,ZoneId.of("Asia/Shanghai"))
    private val token=DataGeneration(0)
    private fun p(title:String="计划")=Plan(title=title,timeZone=clock.zone.id,createdAt=now,updatedAt=now)

    @Test fun `real exception invalidation and series completion update independent facts`()=fixture { f ->
        val goal=f.save(p("长期目标"));f.cases.setCompleted(goal,true,token) {}
        val series=f.save(p("重复系列").copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day,allDayEndDate=day,repeatRule=ScheduleRepeatRule.DAILY))
        val cutoff=f.save(p("截止日程").copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day));f.cases.setCompleted(cutoff,true,token) {}
        f.start();f.stats { it.isAvailable && it.todayScheduledCount==2 };f.awaitCalendar { it.isAvailable && it.days.getValue(day).size==1 }
        assertEquals(PlanStatistics(3,2,1,2,0,2.0/3),f.statistics.uiState.value.statistics)
        f.cases.saveException(PlanException(series,day,true),token) {}
        f.stats { it.todayScheduledCount==1 };f.awaitCalendar { it.days.getValue(day).isEmpty() }
        f.cases.setCompleted(series,true,token) {};f.stats { it.statistics?.completedCount==3 }
        f.main { f.calendar.showCompleted(true,token) }
        f.awaitCalendar { it.includeCompleted && !it.isLoading && it.days.getValue(day).size==2 }
        assertEquals(1,f.calendar.uiState.value.days.values.flatten().count { it.occurrence!!.time is PlanOccurrenceTime.Completion })
        assertEquals(1,f.statistics.uiState.value.todayScheduledCount)
        assertEquals(listOf(PlanException(series,day,true)),f.repo.getExceptions(series))
        assertEquals(setOf(goal,series,cutoff),f.db.plans().allEntities().map { it.id }.toSet())
    }
    @Test fun `same Room identity conversion updates calendar without changing full denominator`()=fixture { f ->
        val id=f.save(p("长期目标"));f.start();f.stats { it.isAvailable && it.statistics?.totalCount==1 };f.awaitCalendar { it.isAvailable }
        val original=f.repo.get(id)!!
        f.cases.save(original.copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day),emptyList(),token) {}
        f.awaitCalendar { it.days.getValue(day).singleOrNull()?.plan?.id==id };f.stats { it.todayScheduledCount==1 }
        f.cases.save(f.repo.get(id)!!.copy(timeMode=PlanTimeMode.NONE),emptyList(),token) {}
        f.awaitCalendar { it.days.getValue(day).isEmpty() };f.stats { it.todayScheduledCount==0 }
        assertEquals(listOf(id),f.db.plans().allEntities().map { it.id });assertEquals(day,f.repo.get(id)!!.dueDate)
        assertEquals(1,f.statistics.uiState.value.statistics!!.totalCount)
    }
    @Test fun `bad SQL value disables statistics and calendar instead of faking zeros`()=fixture { f ->
        val id=f.save(p().copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day));f.start()
        f.stats { it.isAvailable };f.awaitCalendar { it.isAvailable }
        f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET timeZone='not/a-zone' WHERE id=?",arrayOf(id))
        f.db.invalidationTracker.refreshVersionsAsync()
        f.stats { !it.isAvailable && it.readError!=null };f.awaitCalendar { !it.isAvailable && it.readError!=null }
        assertNull(f.statistics.uiState.value.statistics);assertNull(f.statistics.uiState.value.todayScheduledCount)
        f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET timeZone='Asia/Shanghai' WHERE id=?",arrayOf(id))
        f.main { f.calendar.retryRead();f.statistics.retryRead() }
        f.awaitCalendar { it.isAvailable };f.stats { it.isAvailable && it.statistics?.totalCount==1 }
    }
    @Test fun `SQL replacement keeps new identities out of retained calendar callbacks`()=fixture { f ->
        val id=f.save(p("旧记录").copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day));f.start()
        f.awaitCalendar { it.isAvailable };f.stats { it.isAvailable }
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) { f.generations.commit(session.generation) {
            val original=f.db.plans().findEntity(id)!!;f.db.plans().delete(id);f.db.plans().insert(original.copy(title="替换记录"))
        } } }
        f.awaitCalendar { it.generation==DataGeneration(1) && it.isAvailable && it.days.getValue(day).singleOrNull()?.plan?.title=="替换记录" }
        f.stats { it.generation==DataGeneration(1) && it.isAvailable }
        f.main { assertFalse(f.calendar.canNavigate(token));assertFalse(f.statistics.canNavigate(token));f.calendar.selectDate(day.plusDays(10),token) }
        assertEquals(day,f.calendar.uiState.value.selectedDate);assertFalse(f.repo.get(id)!!.isCompleted)
    }
    @Test fun `cancelled SQL maintenance preserves selection and has no deferred navigation`()=fixture { f ->
        f.start();f.awaitCalendar { it.isAvailable };f.stats { it.isAvailable }
        f.main { f.calendar.selectDate(day.plusDays(7),token) };f.awaitCalendar { it.selectedDate==day.plusDays(7) && !it.isLoading }
        f.coordinator.withSession {
            f.awaitCalendar { it.isMaintaining };f.stats { it.isMaintaining }
            f.main { f.calendar.movePeriod(1,token);assertFalse(f.calendar.canNavigate(token)) }
        }
        f.awaitCalendar { !it.isMaintaining && it.isAvailable && !it.isLoading }
        assertEquals(day.plusDays(7),f.calendar.uiState.value.selectedDate);assertEquals(token,f.generations.current())
    }

    private inner class Fixture(val db:PlanMigrationTestDatabase) {
        val repo=PlanRepositoryImpl(db,db.plans(),clock)
        val generations=object:DataGenerationRepository {
            override fun observe()=callbackFlow {
                val observer=object:InvalidationTracker.Observer("app_maintenance") { override fun onInvalidated(tables:Set<String>) { trySend(Unit) } }
                db.invalidationTracker.addObserver(observer);trySend(Unit);awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.map { current() }.distinctUntilChanged()
            override suspend fun current()=db.withTransaction {
                db.plans().read(SimpleSQLiteQuery("SELECT generation FROM app_maintenance WHERE id=1")).use { check(it.moveToFirst());DataGeneration(it.getLong(0)) }
            }
            override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit)=db.withTransaction {
                if(current()!=expected) throw StaleGenerationException()
                val next=DataGeneration(Math.addExact(expected.value,1));mutation();check(current()==expected)
                db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1",arrayOf(next.value));next
            }
        }
        val coordinator=MaintenanceCoordinator(generations)
        val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects { override suspend fun reconcile(planId:Long,expected:DataGeneration) { repo.get(planId) } })
        lateinit var calendar:PlanCalendarViewModel
        lateinit var statistics:PlanStatisticsViewModel
        fun start() {
            val access=GenerationAccess(generations,coordinator)
            calendar=PlanCalendarViewModel(repo,access,Dispatchers.IO,clock);statistics=PlanStatisticsViewModel(repo,access,Dispatchers.IO,clock)
        }
        suspend fun save(plan:Plan)=cases.save(plan,emptyList(),token) {}.receipt.planId
        suspend fun main(operation:()->Unit)=withContext(Dispatchers.Main) { operation() }
        suspend fun awaitCalendar(predicate:(PlanCalendarState)->Boolean)=withTimeout(15_000) { calendar.uiState.first(predicate) }
        suspend fun stats(predicate:(PlanStatisticsState)->Boolean)=withTimeout(15_000) { statistics.uiState.first(predicate) }
        suspend fun close() { if(::calendar.isInitialized) calendar.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            if(::statistics.isInitialized) statistics.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun fixture(block:suspend (Fixture)->Unit)=runBlocking {
        val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher();Dispatchers.setMain(ui)
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),PlanMigrationTestDatabase::class.java).addCallback(PlanV6Schema.INITIALIZE).build()
        val f=Fixture(db)
        try { block(f) } finally { f.close();db.close();Dispatchers.resetMain();ui.close() }
    }
}
