package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test
import kotlin.test.*

class PlanListQueryTest {
    private val now = Instant.parse("2026-10-13T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun plan(id: Long) = Plan(id = id, title = "目标$id", timeZone = "Asia/Shanghai",
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
    private fun deadline(id: Long, date: String) = plan(id).copy(timeMode = PlanTimeMode.DEADLINE, dueDate = LocalDate.parse(date))
    private fun timed(id: Long, start: String, end: String) = plan(id).copy(timeMode = PlanTimeMode.TIMED,
        startAt = Instant.parse(start), endAt = Instant.parse(end))
    private fun select(plans: List<Plan>, filter: PlanFilter = PlanFilter(), exceptions: Map<Long,List<PlanException>> = emptyMap()) =
        PlanListQuery.select(plans, filter, now, zone, exceptions)
    private fun ids(plans: List<Plan>, filter: PlanFilter = PlanFilter(), exceptions: Map<Long,List<PlanException>> = emptyMap()) =
        select(plans, filter, exceptions).map { it.plan.id }

    @Test fun `default includes both unfinished categories but not completed`() {
        assertEquals(listOf(1L, 2L), ids(listOf(plan(1), deadline(2,"2026-10-13"), plan(3).copy(isCompleted=true,completedAt=now))))
    }
    @Test fun `todo category ignores preserved inactive time fields`() {
        assertEquals(listOf(1L), ids(listOf(plan(1).copy(dueAt=now), deadline(2,"2026-10-13")), PlanFilter(category=PlanCategory.TODO)))
    }
    @Test fun `scheduled category includes each effective time mode`() {
        val allDay=plan(4).copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=LocalDate.parse("2026-10-13"),allDayEndDate=LocalDate.parse("2026-10-13"))
        assertEquals(listOf(2L,3L,4L),ids(listOf(plan(1),deadline(2,"2026-10-13"),timed(3,"2026-10-13T04:00:00Z","2026-10-13T05:00:00Z"),allDay),PlanFilter(category=PlanCategory.SCHEDULE)))
    }
    @Test fun `status filters are explicit`() {
        val plans=listOf(plan(1),plan(2).copy(isCompleted=true,completedAt=now))
        assertEquals(listOf(2L),ids(plans,PlanFilter(status=PlanStatus.COMPLETED)))
        assertEquals(listOf(1L,2L),ids(plans,PlanFilter(status=PlanStatus.ALL)))
    }
    @Test fun `search matches title and nullable description without case sensitivity`() {
        assertEquals(listOf(1L,2L),ids(listOf(plan(1).copy(title="Kotlin"),plan(2).copy(description="learn KOTLIN"),plan(3)),PlanFilter(query=" kotlin ")))
    }
    @Test fun `tag selection uses identity rather than ambiguous label`() {
        assertEquals(listOf(2L),ids(listOf(plan(1).copy(tagIds=setOf(3),tagNames=listOf("学习")),plan(2).copy(tagIds=setOf(4),tagNames=listOf("学习"))),PlanFilter(tagId=4)))
    }
    @Test fun `priority tag query and category compose with AND`() {
        val p=deadline(1,"2026-10-13").copy(priority=TodoPriority.HIGH,tagIds=setOf(4),description="学习")
        assertEquals(listOf(1L),ids(listOf(p,p.copy(id=2,priority=TodoPriority.LOW),p.copy(id=3,timeMode=PlanTimeMode.NONE)),PlanFilter(category=PlanCategory.SCHEDULE,priority=TodoPriority.HIGH,tagId=4,query="学习")))
    }
    @Test fun `today excludes undated and uses display zone instant boundaries`() {
        val start=deadline(2,"2026-10-12").copy(dueDate=null,dueAt=Instant.parse("2026-10-12T16:00:00Z"))
        val end=start.copy(id=3,dueAt=Instant.parse("2026-10-13T16:00:00Z"))
        assertEquals(listOf(2L),ids(listOf(plan(1),start,end),PlanFilter(date=PlanDateFilter.TODAY)))
    }
    @Test fun `todo reports date filter not applicable without hiding long term goals`() {
        assertEquals(listOf(1L),ids(listOf(plan(1)),PlanFilter(category=PlanCategory.TODO,date=PlanDateFilter.TODAY)))
    }
    @Test fun `tomorrow and ISO week windows distinguish next Monday`() {
        val plans=listOf(deadline(1,"2026-10-14"),deadline(2,"2026-10-18"),deadline(3,"2026-10-19"),deadline(4,"2026-10-12"))
        assertEquals(listOf(1L),ids(plans,PlanFilter(date=PlanDateFilter.TOMORROW)))
        assertEquals(listOf(1L,2L,4L),ids(plans,PlanFilter(date=PlanDateFilter.WEEK)))
    }
    @Test fun `intervals overlapping midnight are included but exclusive ends are not`() {
        val plans=listOf(timed(1,"2026-10-12T15:00:00Z","2026-10-12T17:00:00Z"),timed(2,"2026-10-12T14:00:00Z","2026-10-12T16:00:00Z"))
        assertEquals(listOf(1L),ids(plans,PlanFilter(date=PlanDateFilter.TODAY)))
    }
    @Test fun `all day last date remains inclusive in plan zone`() {
        val p=plan(1).copy(timeMode=PlanTimeMode.ALL_DAY,allDayStartDate=LocalDate.parse("2026-10-12"),allDayEndDate=LocalDate.parse("2026-10-13"))
        assertEquals(listOf(1L),ids(listOf(p),PlanFilter(date=PlanDateFilter.TODAY)))
    }
    @Test fun `recurring filter respects cancellation and has one row per series`() {
        val p=timed(1,"2026-10-01T04:00:00Z","2026-10-01T05:00:00Z").copy(repeatRule=ScheduleRepeatRule.DAILY)
        val exceptions=mapOf(1L to listOf(PlanException(1,LocalDate.parse("2026-10-13"),true)))
        assertTrue(ids(listOf(p),PlanFilter(date=PlanDateFilter.TODAY),exceptions).isEmpty())
        assertEquals(listOf(1L),ids(listOf(p),PlanFilter(date=PlanDateFilter.WEEK),exceptions))
    }
    @Test fun `completed recurring series is shown only at its actual completion`() {
        val p=timed(1,"2026-10-01T04:00:00Z","2026-10-01T05:00:00Z").copy(repeatRule=ScheduleRepeatRule.DAILY,isCompleted=true,completedAt=now)
        assertEquals(listOf(1L),ids(listOf(p),PlanFilter(status=PlanStatus.COMPLETED,date=PlanDateFilter.TODAY)))
        assertTrue(ids(listOf(p),PlanFilter(status=PlanStatus.COMPLETED,date=PlanDateFilter.TOMORROW)).isEmpty())
    }
    @Test fun `creation order is newest first and equal times use ascending identity`() {
        assertEquals(listOf(3L,1L,2L),ids(listOf(plan(2),plan(3).copy(createdAt=now),plan(1))))
    }
    @Test fun `priority order is highest first with stable identity ties`() {
        assertEquals(listOf(1L,3L,2L,4L),ids(listOf(plan(4),plan(3).copy(priority=TodoPriority.HIGH),plan(2).copy(priority=TodoPriority.LOW),plan(1).copy(priority=TodoPriority.HIGH)),PlanFilter(sort=PlanSort.PRIORITY)))
    }
    @Test fun `date sort places undated after dated regardless of hidden deadline`() {
        assertEquals(listOf(2L,3L,1L,4L),ids(listOf(plan(4).copy(dueAt=Instant.EPOCH),deadline(3,"2026-10-14"),plan(1),deadline(2,"2026-10-13")),PlanFilter(sort=PlanSort.DATE)))
    }
    @Test fun `date sort seeks current next recurrence rather than ancient anchor`() {
        val p=timed(1,"2020-10-01T04:00:00Z","2020-10-01T05:00:00Z").copy(repeatRule=ScheduleRepeatRule.DAILY)
        val exceptions=mapOf(1L to listOf(PlanException(1,LocalDate.parse("2026-10-13"),true)))
        val rows=select(listOf(p,deadline(2,"2026-10-13")),PlanFilter(sort=PlanSort.DATE),exceptions)
        assertEquals(listOf(2L,1L),rows.map { it.plan.id })
        assertEquals(LocalDate.parse("2026-10-14"),rows.last().occurrence!!.occurrenceDate)
    }
    @Test fun `historic blank title and fixed offset remain readable without mutation`() {
        val p=plan(1).copy(title=" ",timeZone="+08:00")
        assertEquals(p,select(listOf(p)).single().plan)
    }
    @Test fun `bad records fail explicitly even if hidden by status`() {
        assertFailsWith<IllegalArgumentException> { select(listOf(plan(1).copy(timeMode=PlanTimeMode.DEADLINE,isCompleted=true,completedAt=now))) }
    }
}
