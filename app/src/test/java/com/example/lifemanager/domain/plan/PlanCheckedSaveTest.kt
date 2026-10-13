package com.example.lifemanager.domain.plan

import com.example.lifemanager.ui.plan.EditorMemoryPlans
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.domain.maintenance.*
import java.time.Instant
import java.time.LocalDate
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class PlanCheckedSaveTest {
    private val now=Instant.parse("2026-10-14T04:00:00Z")
    private val token=DataGeneration(4)
    private fun p(id:Long)=Plan(id=id,title="计划$id",timeZone="Asia/Shanghai",timeMode=PlanTimeMode.TIMED,
        startAt=Instant.parse("2026-10-20T04:00:00Z"),endAt=Instant.parse("2026-10-20T05:00:00Z"),createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
    private class Fixture(now:Instant) {
        val repo=EditorMemoryPlans(now);val gen=TestGenerations(4);val coordinator=MaintenanceCoordinator(gen)
        val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects {override suspend fun reconcile(planId:Long,expected:DataGeneration) {}})
    }
    @Test fun `overlap requires explicit proof before any write`()=runTest {
        val f=Fixture(now);f.repo.rows.value=listOf(p(1))
        val error=assertFailsWith<PlanConflictConfirmationRequired> { f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {} }
        assertEquals(listOf(1L),error.proof.conflicts.map { it.id });assertEquals(0,f.repo.writes)
        assertEquals(Instant.parse("2026-10-19T16:00:00Z"),error.proof.window.startInclusive)
        assertEquals(Instant.parse("2026-11-18T16:00:00Z"),error.proof.window.endExclusive)
    }
    @Test fun `unchanged exact confirmation permits one save`()=runTest {
        val f=Fixture(now);f.repo.rows.value=listOf(p(1))
        val proof=assertFailsWith<PlanConflictConfirmationRequired>{f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {}}.proof
        f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null,proof)) {}
        assertEquals(1,f.repo.writes);assertEquals(2,f.repo.rows.value.size)
    }
    @Test fun `editing candidate invalidates previous confirmation`()=runTest {
        val f=Fixture(now);f.repo.rows.value=listOf(p(1))
        val proof=assertFailsWith<PlanConflictConfirmationRequired>{f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {}}.proof
        assertFailsWith<PlanConflictConfirmationRequired> { f.cases.saveChecked(p(0).copy(title="改表单"),emptyList(),token,PlanEditPolicy(null,proof)) {} }
        assertEquals(0,f.repo.writes)
    }
    @Test fun `same conflict ID with changed fields requires fresh confirmation`()=runTest {
        val f=Fixture(now);f.repo.rows.value=listOf(p(1))
        val proof=assertFailsWith<PlanConflictConfirmationRequired>{f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {}}.proof
        f.repo.rows.value=listOf(p(1).copy(title="已变化"))
        assertFailsWith<PlanConflictConfirmationRequired> { f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null,proof)) {} }
        assertEquals(0,f.repo.writes)
    }
    @Test fun `deadline and completed intervals do not block time`()=runTest {
        val f=Fixture(now);f.repo.rows.value=listOf(p(1))
        f.cases.saveChecked(p(0).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=LocalDate.parse("2026-10-20")),emptyList(),token,PlanEditPolicy(null)) {}
        f.repo.rows.value=listOf(p(1).copy(isCompleted=true,completedAt=now))
        f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {}
        assertEquals(2,f.repo.writes)
    }
    @Test fun `concurrent completion is preserved and not mistaken for a field edit`()=runTest {
        val f=Fixture(now);val original=p(7);f.repo.rows.value=listOf(original.copy(isCompleted=true,completedAt=now,updatedAt=now))
        f.cases.saveChecked(original.copy(title="新标题"),emptyList(),token,PlanEditPolicy(original)) {}
        assertTrue(f.repo.rows.value.single().isCompleted);assertEquals(now,f.repo.rows.value.single().completedAt)
    }
    @Test fun `concurrent content edit cannot be overwritten by old draft`()=runTest {
        val f=Fixture(now);val original=p(7);f.repo.rows.value=listOf(original.copy(description="新备注"))
        assertFailsWith<PlanEditChangedException> { f.cases.saveChecked(original.copy(title="旧草稿"),emptyList(),token,PlanEditPolicy(original)) {} }
        assertEquals("新备注",f.repo.rows.value.single().description);assertEquals(0,f.repo.writes)
    }
    @Test fun `cancelled repeat occurrences use the shared exception rules`()=runTest {
        val f=Fixture(now);val cancelled=p(1).copy(repeatRule=ScheduleRepeatRule.DAILY,repeatEndDate=LocalDate.parse("2026-10-20"))
        f.repo.rows.value=listOf(cancelled);f.repo.exceptions.value=listOf(PlanException(1,LocalDate.parse("2026-10-20"),true))
        f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {}
        assertEquals(1,f.repo.writes)
    }
    @Test fun `reactivating undated repeat honors its retained cancelled occurrence`()=runTest {
        val f=Fixture(now);val original=p(7).copy(timeMode=PlanTimeMode.NONE,repeatRule=ScheduleRepeatRule.DAILY,repeatEndDate=LocalDate.parse("2026-10-20"))
        f.repo.rows.value=listOf(original,p(1));f.repo.exceptions.value=listOf(PlanException(7,LocalDate.parse("2026-10-20"),true))
        f.cases.saveChecked(original.copy(timeMode=PlanTimeMode.TIMED),emptyList(),token,PlanEditPolicy(original)) {}
        assertEquals(1,f.repo.writes);assertEquals(PlanTimeMode.TIMED,f.repo.rows.value.single { it.id==7L }.timeMode)
    }
    @Test fun `changed retained candidate exception invalidates a conflict confirmation`()=runTest {
        val f=Fixture(now);val original=p(7).copy(timeMode=PlanTimeMode.NONE,repeatRule=ScheduleRepeatRule.DAILY,repeatEndDate=LocalDate.parse("2026-10-21"))
        val candidate=original.copy(timeMode=PlanTimeMode.TIMED)
        f.repo.rows.value=listOf(original,p(1));f.repo.exceptions.value=listOf(PlanException(7,LocalDate.parse("2026-10-21"),true))
        val proof=assertFailsWith<PlanConflictConfirmationRequired> { f.cases.saveChecked(candidate,emptyList(),token,PlanEditPolicy(original)) {} }.proof
        f.repo.exceptions.value=listOf(PlanException(7,LocalDate.parse("2026-10-21"),false))
        assertFailsWith<PlanConflictConfirmationRequired> { f.cases.saveChecked(candidate,emptyList(),token,PlanEditPolicy(original,proof)) {} }
        assertEquals(0,f.repo.writes)
    }
    @Test fun `checked save still rejects a stale generation`()=runTest {
        val f=Fixture(now);f.gen.commit(token) {}
        assertFailsWith<StaleGenerationException> { f.cases.saveChecked(p(0),emptyList(),token,PlanEditPolicy(null)) {} }
        assertEquals(0,f.repo.writes)
    }
}
