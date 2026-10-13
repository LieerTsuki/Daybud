package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class PlanDraftTest {
    private val p=Plan(id=7,title="原文",description=null,timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH,
        parentId=3,sortOrder=11,isCompleted=true,completedAt=Instant.parse("2026-10-10T00:00:00Z"),tagIds=setOf(4),tagNames=listOf(" 历史,标签 "),
        location=" ",participants=null,dueAt=Instant.parse("2026-11-01T00:00:00Z"),startAt=Instant.parse("2026-11-02T00:00:00Z"),endAt=Instant.parse("2026-11-02T01:00:00Z"),
        repeatRule=ScheduleRepeatRule.CUSTOM,repeatInterval=3,repeatDaysOfWeek=setOf(DayOfWeek.MONDAY),repeatEndDate=LocalDate.parse("2027-01-01"))
    private fun build(draft:PlanDraft)=PlanDraftRules.build(draft)
    @Test fun `untouched draft preserves nullable text hidden dates and relationships`() {
        assertEquals(p,build(PlanDraft(p)))
    }
    @Test fun `editing one field retains every unrelated historical value`() {
        assertEquals(p.copy(title="新版"),build(PlanDraft(p).copy(title="新版")))
    }
    @Test fun `field edit does not replace other draft fields`() {
        val draft=PlanDraft(p).copy(description="未保存备注")
        assertEquals(draft.copy(title="新标题"),PlanDraftRules.edit(draft,PlanField.TITLE,"新标题"))
    }
    @Test fun `blank title is rejected without silently repairing history`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p.copy(title=" "))) }
    }
    @Test fun `fixed offset history is preserved on display but requires registered zone to edit`() {
        val draft=PlanDraft(p.copy(timeZone="+08:00"))
        assertEquals("+08:00",draft.timeZone)
        assertFailsWith<IllegalArgumentException> { build(draft) }
        assertEquals("Asia/Shanghai",build(draft.copy(timeZone="Asia/Shanghai")).timeZone)
    }
    @Test fun `only date conversion does not invent a timed interval`() {
        val result=build(PlanDraft(p).copy(mode=PlanTimeMode.DEADLINE,dateOnly=true,dueDate="2026-10-20"))
        assertEquals(p.copy(timeMode=PlanTimeMode.DEADLINE,dueAt=null,dueDate=LocalDate.parse("2026-10-20")),result)
    }
    @Test fun `instant deadline clears alternate effective date but not other hidden fields`() {
        val result=build(PlanDraft(p.copy(dueDate=LocalDate.parse("2026-10-19"))).copy(mode=PlanTimeMode.DEADLINE,dateOnly=false,dueAt="2026-10-20T12:00+08:00"))
        assertEquals(Instant.parse("2026-10-20T04:00:00Z"),result.dueAt)
        assertNull(result.dueDate);assertEquals(p.startAt,result.startAt);assertEquals(7,result.id)
    }
    @Test fun `undating keeps every inactive date and repeating field`() {
        val original=p.copy(timeMode=PlanTimeMode.TIMED)
        assertEquals(original.copy(timeMode=PlanTimeMode.NONE),build(PlanDraft(original).copy(mode=PlanTimeMode.NONE)))
    }
    @Test fun `time interval requires end strictly after start`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(mode=PlanTimeMode.TIMED,endAt="2026-11-01T00:00+08:00")) }
    }
    @Test fun `all day same date remains an inclusive real day`() {
        val result=build(PlanDraft(p).copy(mode=PlanTimeMode.ALL_DAY,allDayStart="2026-10-20",allDayEnd="2026-10-20"))
        assertEquals(LocalDate.parse("2026-10-20"),result.allDayStartDate)
        assertEquals(LocalDate.parse("2026-10-20"),result.allDayEndDate)
    }
    @Test fun `all day reversed range is rejected`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(mode=PlanTimeMode.ALL_DAY,allDayStart="2026-10-21",allDayEnd="2026-10-20")) }
    }
    @Test fun `missing effective deadline is rejected`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(mode=PlanTimeMode.DEADLINE,dateOnly=true,dueDate="")) }
    }
    @Test fun `DST overlap retains the original later offset and milliseconds`() {
        val original=p.copy(timeZone="America/New_York",timeMode=PlanTimeMode.DEADLINE,dueAt=Instant.parse("2026-11-01T06:30:00.123Z"))
        assertEquals(original.copy(title="改标题"),build(PlanDraft(original).copy(title="改标题")))
    }
    @Test fun `new ambiguous local time requires an explicit valid offset`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(timeZone="America/New_York",mode=PlanTimeMode.DEADLINE,dueAt="2026-11-01T01:30")) }
    }
    @Test fun `DST gap is not silently moved forward`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(timeZone="America/New_York",mode=PlanTimeMode.DEADLINE,dueAt="2026-03-08T02:30")) }
    }
    @Test fun `explicit overlapping offset produces the selected instant`() {
        val result=build(PlanDraft(p).copy(timeZone="America/New_York",mode=PlanTimeMode.DEADLINE,dueAt="2026-11-01T01:30-05:00"))
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"),result.dueAt)
    }
    @Test fun `offset outside selected zone is rejected`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(mode=PlanTimeMode.DEADLINE,dueAt="2026-10-20T12:00+02:00")) }
    }
    @Test fun `submillisecond user input is rejected rather than truncated`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(mode=PlanTimeMode.DEADLINE,dueAt="2026-10-20T12:00:00.123456+08:00")) }
    }
    @Test fun `invalid numbers are not converted to defaults`() {
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(reminderMinutes="x")) }
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(reminderMinutes="-1")) }
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(repeatInterval="0")) }
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(repeatInterval="9999999999999999999999")) }
    }
    @Test fun `blank reminder preserves explicit null rather than defaulting to 15`() {
        assertNull(build(PlanDraft(p).copy(reminderMinutes="")).reminderMinutes)
    }
    @Test fun `raw linked tags remain exact while newly entered tag is normalized`() {
        val result=build(PlanDraft(p).copy(newTag=" 新标签 "))
        assertEquals(listOf(" 历史,标签 ","新标签"),result.tagNames)
    }
    @Test fun `color parser preserves signed ARGB and rejects malformed color`() {
        assertEquals(0xFFFFB69F.toInt(),build(PlanDraft(p).copy(color="FFFFB69F")).color)
        assertFailsWith<IllegalArgumentException> { build(PlanDraft(p).copy(color="garbage")) }
    }
    @Test fun `editing nullable fields only converts the intentionally changed value`() {
        val result=build(PlanDraft(p).copy(description="备注",location="",participants="新成员"))
        assertEquals("备注",result.description);assertEquals("",result.location);assertEquals("新成员",result.participants)
    }
    @Test fun `signed Long millisecond boundaries round trip without rounding`() {
        for(value in listOf(Long.MIN_VALUE,Long.MAX_VALUE)) {
            val original=p.copy(timeZone="UTC",timeMode=PlanTimeMode.DEADLINE,dueAt=Instant.ofEpochMilli(value))
            assertEquals(value,build(PlanDraft(original).copy(title="修改边界记录")).dueAt!!.toEpochMilli())
        }
    }
    @Test fun `one millisecond beyond either signed Long boundary cannot be saved`() {
        val lower=Instant.ofEpochMilli(Long.MIN_VALUE).minusMillis(1).atOffset(java.time.ZoneOffset.UTC).toString()
        val upper=Instant.ofEpochMilli(Long.MAX_VALUE).plusMillis(1).atOffset(java.time.ZoneOffset.UTC).toString()
        for(text in listOf(lower,upper)) {
            assertFailsWith<ArithmeticException> { build(PlanDraft(p.copy(timeZone="UTC")).copy(mode=PlanTimeMode.DEADLINE,dueAt=text)) }
        }
    }
    @Test fun `new text at both signed Long boundaries parses to exact milliseconds`() {
        val inputs=listOf("-292275055-05-16T16:47:04.192Z" to Long.MIN_VALUE,
            "+292278994-08-17T07:12:55.807Z" to Long.MAX_VALUE)
        for((text,want) in inputs) {
            val base=p.copy(timeZone="UTC",dueAt=null)
            assertEquals(want,build(PlanDraft(base).copy(mode=PlanTimeMode.DEADLINE,dueAt=text)).dueAt!!.toEpochMilli())
        }
    }
}
