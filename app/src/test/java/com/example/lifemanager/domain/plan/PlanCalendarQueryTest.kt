package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import org.junit.Test

class PlanCalendarQueryTest {
    private val day=LocalDate.parse("2026-10-14")
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-10-14T04:00:00Z")
    private fun p(id:Long=1)=Plan(id=id,title="计划$id",timeZone=zone.id,createdAt=now,updatedAt=now)
    private fun query(plans:List<Plan>,mode:PlanCalendarMode=PlanCalendarMode.DAY,
        selected:LocalDate=day,completed:Boolean=false,exceptions:Map<Long,List<PlanException>> = emptyMap())=
        PlanCalendarQuery.select(plans,selected,mode,zone,exceptions,completed)

    @Test fun `undated plans with retained dates never enter calendar`() {
        assertTrue(query(listOf(p().copy(dueDate=day,startAt=now,endAt=now.plusSeconds(1)))).values.flatten().isEmpty())
    }
    @Test fun `deadline date stays a date even across display zones`() {
        val values=listOf(p().copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day,timeZone="Pacific/Kiritimati"))
        val result=PlanCalendarQuery.select(values,day,PlanCalendarMode.DAY,ZoneId.of("America/Los_Angeles"))
        assertEquals(listOf(1L),result.getValue(day).map { it.plan.id })
        assertEquals(PlanOccurrenceTime.DeadlineDate(day),result.getValue(day).single().occurrence!!.time)
    }
    @Test fun `instant cutoff groups by displayed local date not plan anchor date`() {
        val value=p().copy(timeMode=PlanTimeMode.DEADLINE,dueAt=Instant.parse("2026-10-13T18:00:00Z"),timeZone="UTC")
        assertEquals(listOf(1L),query(listOf(value)).getValue(day).map { it.plan.id })
    }
    @Test fun `timed interval crossing midnight appears on each intersecting day`() {
        val value=p().copy(timeMode=PlanTimeMode.TIMED,startAt=Instant.parse("2026-10-13T15:00:00Z"),endAt=Instant.parse("2026-10-14T01:00:00Z"))
        val result=query(listOf(value),PlanCalendarMode.WEEK)
        assertEquals(listOf(LocalDate.parse("2026-10-13"),day),result.filterValues { it.isNotEmpty() }.keys.toList())
        assertEquals(setOf(1L),result.values.flatten().map { it.plan.id }.toSet())
    }
    @Test fun `exclusive timed end at midnight never adds following date`() {
        val value=p().copy(timeMode=PlanTimeMode.TIMED,startAt=Instant.parse("2026-10-13T15:00:00Z"),endAt=Instant.parse("2026-10-13T16:00:00Z"))
        assertTrue(query(listOf(value)).getValue(day).isEmpty())
    }
    @Test fun `all day end is inclusive without shifting saved dates`() {
        val value=p().copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day.minusDays(1),allDayEndDate=day,timeZone="America/Los_Angeles")
        val result=query(listOf(value),PlanCalendarMode.WEEK)
        assertEquals(listOf(day.minusDays(1),day),result.filterValues { it.isNotEmpty() }.keys.toList())
    }
    @Test fun `week starts Monday and month includes its actual last date`() {
        assertEquals(LocalDate.parse("2026-10-12"),query(emptyList(),PlanCalendarMode.WEEK).keys.first())
        val month=query(emptyList(),PlanCalendarMode.MONTH)
        assertEquals(31,month.size);assertEquals(LocalDate.parse("2026-10-31"),month.keys.last())
    }
    @Test fun `cancelled repeated occurrence stays absent and retains later ones`() {
        val value=p().copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day,allDayEndDate=day,repeatRule=ScheduleRepeatRule.DAILY)
        val result=query(listOf(value),PlanCalendarMode.WEEK,exceptions=mapOf(1L to listOf(PlanException(1,day,true))))
        assertTrue(result.getValue(day).isEmpty());assertEquals(listOf(1L),result.getValue(day.plusDays(1)).map { it.plan.id })
    }
    @Test fun `completed series has one completion marker not expanded history`() {
        val value=p().copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day.minusDays(10),allDayEndDate=day.minusDays(10),
            repeatRule=ScheduleRepeatRule.DAILY,isCompleted=true,completedAt=now)
        assertTrue(query(listOf(value),PlanCalendarMode.MONTH).values.flatten().isEmpty())
        val visible=query(listOf(value),PlanCalendarMode.MONTH,completed=true).values.flatten()
        assertEquals(1,visible.size);assertEquals(PlanOccurrenceTime.Completion(now),visible.single().occurrence!!.time)
    }
    @Test fun `completed single stays on actual deadline instead of completion day`() {
        val value=p().copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day.minusDays(1),isCompleted=true,completedAt=now)
        val result=query(listOf(value),PlanCalendarMode.WEEK,completed=true)
        assertEquals(listOf(1L),result.getValue(day.minusDays(1)).map { it.plan.id });assertTrue(result.getValue(day).isEmpty())
    }
    @Test fun `same time ties use stable plan identity`() {
        val values=listOf(9L,3L).map { p(it).copy(timeMode=PlanTimeMode.DEADLINE,dueAt=now) }
        assertEquals(listOf(3L,9L),query(values).getValue(day).map { it.plan.id })
    }
    @Test fun `invalid plan fails whole query rather than manufacturing empty success`() {
        assertFailsWith<IllegalArgumentException> { query(listOf(p().copy(timeZone="bad/zone"))) }
    }
    @Test fun `candidate budget is shared across plans and does not truncate`() {
        val values=(1L..3L).map { p(it).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day) }
        assertFailsWith<PlanExpansionLimitException> { PlanCalendarQuery.select(values,day,PlanCalendarMode.DAY,zone,maxCandidates=2) }
    }
    @Test fun `today scheduled count deduplicates series and excludes completion markers and undated goals`() {
        val series=p(1).copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=day.minusDays(2),allDayEndDate=day,repeatRule=ScheduleRepeatRule.DAILY)
        val ended=series.copy(id=2,isCompleted=true,completedAt=now)
        val single=p(3).copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day,isCompleted=true,completedAt=now)
        assertEquals(2,PlanCalendarQuery.todayScheduledCount(listOf(series,ended,single,p(4)),now,zone))
    }
    @Test fun `date calendar admits distant zones without conflating real today statistics`() {
        val values=listOf(p().copy(timeMode=PlanTimeMode.DEADLINE,dueDate=day,timeZone="Pacific/Kiritimati"))
        val distant=ZoneId.of("Etc/GMT+12")
        assertEquals(listOf(1L),PlanCalendarQuery.select(values,day,PlanCalendarMode.DAY,distant).getValue(day).map { it.plan.id })
        assertEquals(0,PlanCalendarQuery.todayScheduledCount(values,Instant.parse("2026-10-15T00:00:00Z"),distant))
    }
    @Test fun `date event at month edge remains on its saved month in distant display zone`() {
        val end=LocalDate.parse("2026-10-31")
        val values=listOf(p().copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=end,allDayEndDate=end,timeZone="Pacific/Kiritimati"))
        val result=PlanCalendarQuery.select(values,day,PlanCalendarMode.MONTH,ZoneId.of("Etc/GMT+12"))
        assertEquals(listOf(end),result.filterValues { it.isNotEmpty() }.keys.toList())
    }
}
