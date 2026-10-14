package com.example.lifemanager.ui.plan

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanOverviewViewModelTest {
    private val day=LocalDate.parse("2026-10-14")
    private fun p(f:EditorTestWorld,id:Long=1)=Plan(id=id,title="计划$id",timeZone="Asia/Shanghai",createdAt=f.now,updatedAt=f.now)
    private fun scenario(block:suspend TestScope.(EditorTestWorld,PlanCalendarViewModel,PlanStatisticsViewModel)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=EditorTestWorld(dispatcher)
        val access=GenerationAccess(f.generations,f.coordinator)
        val clock=Clock.fixed(f.now,ZoneId.of("Asia/Shanghai"))
        val calendar=PlanCalendarViewModel(f.repo,access,dispatcher,clock)
        val stats=PlanStatisticsViewModel(f.repo,access,dispatcher,clock)
        try { advanceUntilIdle();block(f,calendar,stats) } finally {
            calendar.viewModelScope.cancel();stats.viewModelScope.cancel();f.model.viewModelScope.cancel()
            advanceUntilIdle();Dispatchers.resetMain()
        }
    }
    @Test fun `initial real empty database is available with empty calendar and zero statistics`()=scenario { f,c,s ->
        assertTrue(c.uiState.value.isAvailable);assertEquals(f.token,c.uiState.value.generation)
        assertEquals(day,c.uiState.value.selectedDate);assertEquals(31,c.uiState.value.days.size)
        assertEquals(0,s.uiState.value.statistics!!.totalCount);assertEquals(0,s.uiState.value.todayScheduledCount)
    }
    @Test fun `statistics reads all plans independently of list search and completed status`()=scenario { f,c,s ->
        f.repo.rows.value=listOf(p(f,1),p(f,2).copy(isCompleted=true,completedAt=f.now),p(f,3).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day))
        val list=PlanViewModel(f.repo,f.cases,GenerationAccess(f.generations,f.coordinator),StandardTestDispatcher(testScheduler),Clock.fixed(f.now,ZoneId.of("Asia/Shanghai")))
        try {
            advanceUntilIdle();list.onFilterChanged(PlanFilter(query="不存在",status=PlanStatus.COMPLETED),f.token);advanceUntilIdle()
            assertTrue(list.uiState.value.rows.isEmpty());assertEquals(3,s.uiState.value.statistics!!.totalCount)
            assertEquals(1.0/3.0,s.uiState.value.statistics!!.completionRate);assertEquals(1,s.uiState.value.statistics!!.todayCompletedCount)
            assertEquals(1,s.uiState.value.todayScheduledCount);assertEquals(listOf(3L),c.uiState.value.days.getValue(day).map { it.plan.id })
        } finally { list.viewModelScope.cancel() }
    }
    @Test fun `calendar selection and period navigation retain mode and completion preference`()=scenario { f,c,_ ->
        c.selectMode(PlanCalendarMode.WEEK,f.token);advanceUntilIdle()
        c.selectDate(day.plusDays(2),f.token);advanceUntilIdle();c.showCompleted(true,f.token);advanceUntilIdle()
        c.movePeriod(1,f.token);advanceUntilIdle()
        assertEquals(day.plusDays(9),c.uiState.value.selectedDate);assertEquals(PlanCalendarMode.WEEK,c.uiState.value.mode)
        assertTrue(c.uiState.value.includeCompleted)
        c.today(f.token);advanceUntilIdle();assertEquals(day,c.uiState.value.selectedDate)
    }
    @Test fun `repeated exceptions and completion update both read models without duplicate series totals`()=scenario { f,c,s ->
        f.repo.rows.value=listOf(p(f).copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day,allDayEndDate=day,repeatRule=ScheduleRepeatRule.DAILY))
        advanceUntilIdle();assertEquals(1,s.uiState.value.todayScheduledCount)
        f.repo.exceptions.value=listOf(PlanException(1,day,true));c.retryRead();s.retryRead();advanceUntilIdle()
        assertTrue(c.uiState.value.days.getValue(day).isEmpty());assertEquals(0,s.uiState.value.todayScheduledCount)
        f.cases.setCompleted(1,true,f.token) {};advanceUntilIdle()
        c.showCompleted(true,f.token);advanceUntilIdle()
        assertEquals(1,c.uiState.value.days.values.flatten().size)
        assertEquals(1,s.uiState.value.statistics!!.completedCount);assertEquals(1,s.uiState.value.statistics!!.todayCompletedCount)
        assertEquals(0,s.uiState.value.todayScheduledCount)
    }
    @Test fun `read failure hides successful facts and retry genuinely reloads`()=scenario { f,c,s ->
        f.repo.readsFail=true;c.retryRead();s.retryRead();advanceUntilIdle()
        assertFalse(c.uiState.value.isAvailable);assertNotNull(c.uiState.value.readError)
        assertFalse(s.uiState.value.isAvailable);assertNull(s.uiState.value.statistics);assertNull(s.uiState.value.todayScheduledCount)
        f.repo.readsFail=false;c.retryRead();s.retryRead();advanceUntilIdle()
        assertTrue(c.uiState.value.isAvailable);assertTrue(s.uiState.value.isAvailable)
    }
    @Test fun `invalid historical active data is an error not zero statistics`()=scenario { f,c,s ->
        f.repo.rows.value=listOf(p(f).copy(timeMode=PlanTimeMode.DEADLINE,dueAt=f.now,dueDate=day));advanceUntilIdle()
        assertFalse(c.uiState.value.isAvailable);assertFalse(s.uiState.value.isAvailable)
        assertNull(s.uiState.value.statistics)
    }
    @Test fun `maintenance cancel preserves selected date and rejects queued navigation`()=scenario { f,c,s ->
        c.selectDate(day.plusDays(8),f.token);advanceUntilIdle()
        f.coordinator.withSession {
            runCurrent();assertTrue(c.uiState.value.isMaintaining);assertTrue(s.uiState.value.isMaintaining)
            c.selectMode(PlanCalendarMode.DAY,f.token);c.movePeriod(1,f.token);c.showCompleted(true,f.token)
            assertFalse(c.canNavigate(f.token));assertFalse(s.canNavigate(f.token))
        }
        advanceUntilIdle();assertEquals(day.plusDays(8),c.uiState.value.selectedDate)
        assertEquals(PlanCalendarMode.MONTH,c.uiState.value.mode);assertFalse(c.uiState.value.includeCompleted)
        assertTrue(c.uiState.value.isAvailable)
    }
    @Test fun `generation replacement clears old facts and rejects retained callbacks`()=scenario { f,c,s ->
        f.repo.rows.value=listOf(p(f).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day));advanceUntilIdle()
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) { f.generations.commit(session.generation) { f.repo.rows.value=emptyList() } } }
        advanceUntilIdle();assertEquals(DataGeneration(5),c.uiState.value.generation);assertTrue(c.uiState.value.days.values.flatten().isEmpty())
        assertEquals(0,s.uiState.value.statistics!!.totalCount);assertFalse(c.canNavigate(f.token));assertFalse(s.canNavigate(f.token))
        c.selectDate(day.plusDays(99),f.token);advanceUntilIdle();assertEquals(day,c.uiState.value.selectedDate)
    }
    @Test fun `zone refresh publishes new date context even with identical empty rows`()=scenario { _,c,s ->
        c.refresh(ZoneId.of("UTC"));s.refresh(ZoneId.of("UTC"));advanceUntilIdle()
        assertEquals(ZoneId.of("UTC"),c.uiState.value.displayZone);assertEquals(ZoneId.of("UTC"),s.uiState.value.displayZone)
    }
    @Test fun `clock refresh changes today completion and overdue without database invalidation`()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=EditorTestWorld(dispatcher);var instant=f.now
        val clock=object:Clock() {
            override fun getZone()=ZoneId.of("Asia/Shanghai")
            override fun withZone(zone:ZoneId)=Clock.fixed(instant,zone)
            override fun instant()=instant
        }
        f.repo.rows.value=listOf(p(f,1).copy(isCompleted=true,completedAt=f.now),p(f,2).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day))
        val s=PlanStatisticsViewModel(f.repo,GenerationAccess(f.generations,f.coordinator),dispatcher,clock)
        try {
            advanceUntilIdle();assertEquals(1,s.uiState.value.statistics!!.todayCompletedCount);assertEquals(0,s.uiState.value.statistics!!.overdueCount)
            instant=Instant.parse("2026-10-14T16:00:00Z");s.refresh();advanceUntilIdle()
            assertEquals(0,s.uiState.value.statistics!!.todayCompletedCount);assertEquals(1,s.uiState.value.statistics!!.overdueCount)
            assertEquals(LocalDate.parse("2026-10-15"),s.uiState.value.today)
        } finally { s.viewModelScope.cancel();f.model.viewModelScope.cancel();advanceUntilIdle();Dispatchers.resetMain() }
    }
    @Test fun `metadata observation failure recovers only through fresh retry`()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val f=EditorTestWorld(dispatcher);var fail=true
        val metadata=object:DataGenerationRepository by f.generations { override fun observe()=f.generations.observe().map { check(!fail);it } }
        val access=GenerationAccess(metadata,f.coordinator)
        val c=PlanCalendarViewModel(f.repo,access,dispatcher,Clock.fixed(f.now,ZoneId.of("Asia/Shanghai")))
        val s=PlanStatisticsViewModel(f.repo,access,dispatcher,Clock.fixed(f.now,ZoneId.of("Asia/Shanghai")))
        try {
            advanceUntilIdle();assertFalse(c.uiState.value.isAvailable);assertNotNull(c.uiState.value.readError);assertNull(s.uiState.value.statistics)
            fail=false;c.retryRead();s.retryRead();advanceUntilIdle();assertTrue(c.uiState.value.isAvailable);assertTrue(s.uiState.value.isAvailable)
        } finally { c.viewModelScope.cancel();s.viewModelScope.cancel();f.model.viewModelScope.cancel();advanceUntilIdle();Dispatchers.resetMain() }
    }
}
