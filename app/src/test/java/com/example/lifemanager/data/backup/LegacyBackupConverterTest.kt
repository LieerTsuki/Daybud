package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.time.ZoneId
import org.junit.Test
import kotlin.test.*

class LegacyBackupConverterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun document(payload: BackupPayload = BackupFixtures.fullPayload()) = BackupDocument("0.1.1", "2026-10-13T00:00:00Z", payload)
    private fun convert(source: BackupDocument = document(), limits: BackupLimits = BackupLimits(), injected: ZoneId = zone) =
        LegacyBackupConverter(limits).convert(source, injected)
    private fun target(result: BackupImportResult) = result.document
    private fun notice(result: BackupImportResult) = result.legacyTodoTimeZone
    private fun row(vararg fields: Pair<String, Any?>) = BackupRow(fields.associate { (key, value) -> key to when(value) {
        null -> BackupValue.Null; is Long -> BackupValue.Integer(value); is String -> BackupValue.Text(value); is Boolean -> BackupValue.Flag(value); else -> error("fixture")
    } })

    @Test fun `todo converts every raw field with exact defaults without editor normalization`() {
        val result = convert()
        assertEquals(row("id" to 1L, "title" to "  旧待办\n\"🏠\"  ", "description" to null, "priority" to "HIGH", "color" to -14390954L,
            "location" to null, "participants" to null, "timeMode" to "DEADLINE", "dueAt" to -1000L, "dueDate" to null,
            "startAt" to null, "endAt" to null, "allDayStartDate" to null, "allDayEndDate" to null, "timeZone" to "Asia/Shanghai",
            "isCompleted" to false, "completedAt" to null, "repeatRule" to "NONE", "repeatInterval" to 1L, "repeatDaysOfWeek" to null,
            "repeatEndDate" to null, "reminderEnabled" to false, "reminderMinutes" to 15L, "parentId" to null, "sortOrder" to -3L,
            "createdAt" to 7L, "updatedAt" to 8L), target(result).payload.tables.getValue("plans")[0])
        assertEquals("Asia/Shanghai", notice(result))
        assertEquals(BackupSourceFormat.V1, result.sourceFormat)
    }
    @Test fun `schedule converts all raw fields and original fixed offset without normalizing weekdays`() {
        val source = document().let { it.copy(payload = it.payload.withTable("schedules", listOf(it.payload.tables.getValue("schedules").single()
            .with("timeZone", BackupValue.Text("+08:00")).with("title", BackupValue.Text("  \n"))))) }
        assertEquals(row("id" to 2L, "title" to "  \n", "description" to "备注", "priority" to "NONE", "color" to -123L,
            "location" to "", "participants" to "甲,乙", "timeMode" to "TIMED", "dueAt" to null, "dueDate" to null,
            "startAt" to 1000L, "endAt" to 2000L, "allDayStartDate" to null, "allDayEndDate" to null, "timeZone" to "+08:00",
            "isCompleted" to false, "completedAt" to null, "repeatRule" to "NONE", "repeatInterval" to 1L, "repeatDaysOfWeek" to "7,1,1",
            "repeatEndDate" to -1L, "reminderEnabled" to true, "reminderMinutes" to 15L, "parentId" to null, "sortOrder" to 0L,
            "createdAt" to 7L, "updatedAt" to 8L), target(convert(source)).payload.tables.getValue("plans")[1])
    }
    @Test fun `signed extreme source ids sort independently with forward parents tags and exceptions`() {
        val old = BackupFixtures.fullPayload(); val todo = old.tables.getValue("todos").single(); val schedule = old.tables.getValue("schedules").single()
        val payload = old.withTable("todos", listOf(todo.with("id", BackupValue.Integer(Long.MAX_VALUE)),
            todo.with("id", BackupValue.Integer(Long.MIN_VALUE)).with("parentId", BackupValue.Integer(Long.MAX_VALUE))))
            .withTable("schedules", listOf(schedule.with("id", BackupValue.Integer(Long.MAX_VALUE)), schedule.with("id", BackupValue.Integer(Long.MIN_VALUE))))
            .withTable("todo_tag_cross_ref", listOf(row("todoId" to Long.MIN_VALUE, "tagId" to 4L)))
            .withTable("schedule_exceptions", listOf(row("scheduleId" to Long.MAX_VALUE, "occurrenceDate" to 21000L, "isCancelled" to false)))
        val tables = target(convert(document(payload))).payload.tables
        assertEquals(listOf(1L,2L,3L,4L).map(BackupValue::Integer), tables.getValue("plans").map { it.fields["id"] })
        assertEquals(BackupValue.Integer(2), tables.getValue("plans")[0].fields["parentId"])
        assertEquals(listOf(row("planId" to 1L, "tagId" to 4L)), tables.getValue("plan_tag_cross_ref"))
        assertEquals(listOf(row("planId" to 4L, "occurrenceDate" to 21000L, "isCancelled" to false)), tables.getValue("plan_exceptions"))
        assertEquals(listOf(row("source" to "TODO", "legacyId" to Long.MIN_VALUE, "planId" to 1L), row("source" to "TODO", "legacyId" to Long.MAX_VALUE, "planId" to 2L),
            row("source" to "SCHEDULE", "legacyId" to Long.MIN_VALUE, "planId" to 3L), row("source" to "SCHEDULE", "legacyId" to Long.MAX_VALUE, "planId" to 4L)), tables.getValue("plan_legacy_refs"))
    }
    @Test fun `unchanged six tables settings and metadata retain independently constructed originals`() {
        val result = target(convert())
        for (name in listOf("tags", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")) {
            assertEquals(BackupFixtures.fullPayload().tables.getValue(name), result.payload.tables.getValue(name))
        }
        assertEquals(V2BackupSettings(com.example.lifemanager.domain.model.ThemeMode.DARK, com.example.lifemanager.domain.model.DateFormat.DMY, "EUR", true, false, setOf(1,7)), result.payload.settings)
        assertEquals("0.1.1", result.appVersion); assertEquals("2026-10-13T00:00:00Z", result.exportedAt)
    }
    @Test fun `all four reminder combinations preserve per item flags and OR global preference`() {
        for ((todo, schedule) in listOf(false to false, false to true, true to false, true to true)) {
            val old = BackupFixtures.fullPayload(); val result = target(convert(document(old.copy(settings = old.settings.copy(todoReminders = todo, scheduleReminders = schedule)))))
            assertEquals(todo || schedule, result.payload.settings.planReminders)
            assertEquals(BackupValue.Flag(todo), result.payload.tables.getValue("plans")[0].fields["reminderEnabled"])
            assertEquals(BackupValue.Flag(schedule), result.payload.tables.getValue("plans")[1].fields["reminderEnabled"])
        }
    }
    @Test fun `all day inclusive dates inactive instants and minutes survive without enabling reminder`() {
        val old = BackupFixtures.fullPayload(); val schedule = old.tables.getValue("schedules").single().with("isAllDay", BackupValue.Flag(true))
            .with("allDayStartDate", BackupValue.Integer(10)).with("allDayEndDate", BackupValue.Integer(10))
        val plan = target(convert(document(old.withTable("schedules", listOf(schedule))))).payload.tables.getValue("plans")[1]
        assertEquals(BackupValue.Text("ALL_DAY"), plan.fields["timeMode"]); assertEquals(BackupValue.Integer(10), plan.fields["allDayEndDate"])
        assertEquals(BackupValue.Integer(1000), plan.fields["startAt"]); assertEquals(BackupValue.Integer(15), plan.fields["reminderMinutes"])
        assertEquals(BackupValue.Flag(false), plan.fields["reminderEnabled"])
    }
    @Test fun `custom repeat and inactive dates keep raw values through source DTO adaptation`() {
        val old = BackupFixtures.fullPayload()
        val schedule = old.tables.getValue("schedules").single().with("repeatRule", BackupValue.Text("CUSTOM"))
            .with("repeatInterval", BackupValue.Integer(Int.MAX_VALUE.toLong())).with("repeatDaysOfWeek", BackupValue.Text("7,01,1"))
            .with("startAt", BackupValue.Integer(Long.MIN_VALUE)).with("endAt", BackupValue.Integer(Long.MAX_VALUE))
            .with("allDayStartDate", BackupValue.Integer(10)).with("allDayEndDate", BackupValue.Integer(12))
        val plan = target(convert(document(old.withTable("schedules", listOf(schedule))))).payload.tables.getValue("plans")[1]
        assertEquals(BackupValue.Text("CUSTOM"), plan.fields["repeatRule"])
        assertEquals(BackupValue.Integer(2147483647), plan.fields["repeatInterval"])
        assertEquals(BackupValue.Text("7,01,1"), plan.fields["repeatDaysOfWeek"])
        assertEquals(BackupValue.Integer(-1), plan.fields["repeatEndDate"])
        assertEquals(BackupValue.Integer(Long.MIN_VALUE), plan.fields["startAt"])
        assertEquals(BackupValue.Integer(Long.MAX_VALUE), plan.fields["endAt"])
        assertEquals(BackupValue.Integer(10), plan.fields["allDayStartDate"])
        assertEquals(BackupValue.Integer(12), plan.fields["allDayEndDate"])
    }
    @Test fun `undated blank todo preserves historical completion mismatch and audit extrema`() {
        val old = BackupFixtures.fullPayload(); val todo = old.tables.getValue("todos").single().with("title", BackupValue.Text(" \n"))
            .with("dueAt", BackupValue.Null).with("completedAt", BackupValue.Integer(Long.MAX_VALUE)).with("createdAt", BackupValue.Integer(Long.MIN_VALUE))
        val plan = target(convert(document(old.withTable("todos", listOf(todo))))).payload.tables.getValue("plans")[0]
        assertEquals(BackupValue.Text("NONE"), plan.fields["timeMode"]); assertEquals(BackupValue.Null, plan.fields["dueAt"])
        assertEquals(BackupValue.Text(" \n"), plan.fields["title"]); assertEquals(BackupValue.Flag(false), plan.fields["isCompleted"])
        assertEquals(BackupValue.Integer(Long.MAX_VALUE), plan.fields["completedAt"]); assertEquals(BackupValue.Integer(Long.MIN_VALUE), plan.fields["createdAt"])
    }
    @Test fun `null schedule minutes remain null and absent todo never claims injected timezone`() {
        val old = BackupFixtures.fullPayload().withTable("todos", emptyList()).withTable("todo_tag_cross_ref", emptyList())
        val result = convert(document(old.withTable("schedules", listOf(old.tables.getValue("schedules").single().with("reminderMinutes", BackupValue.Null)))))
        assertNull(notice(result)); assertEquals(BackupValue.Null, target(result).payload.tables.getValue("plans").single().fields["reminderMinutes"])
    }
    @Test fun `empty legacy source produces empty v2 and no timezone notice`() {
        val result = convert(document(BackupPayload(BackupFixtures.emptyTables, com.example.lifemanager.domain.model.AppSettings())))
        assertTrue(target(result).payload.tables.values.all { it.isEmpty() }); assertNull(notice(result))
    }
    @Test fun `caller supplied zone controls todo only and source stays unchanged`() {
        val original = document(); val result = target(convert(original, injected = ZoneId.of("Europe/Paris")))
        assertEquals(BackupValue.Text("Europe/Paris"), result.payload.tables.getValue("plans")[0].fields["timeZone"])
        assertEquals(BackupValue.Text("UTC"), result.payload.tables.getValue("plans")[1].fields["timeZone"])
        assertEquals(document(), original)
    }
    @Test fun `source malformed types unknown keys orphan and cycle rejected before conversion`() {
        val old = BackupFixtures.fullPayload(); val todo = old.tables.getValue("todos").single()
        for (payload in listOf(old.withTable("todos", listOf(todo.with("id", BackupValue.Text("41")))),
            old.copy(tables = old.tables + ("app_maintenance" to emptyList())),
            old.withTable("todos", listOf(todo.with("parentId", BackupValue.Integer(999)))),
            old.withTable("todos", listOf(todo.with("parentId", BackupValue.Integer(41)))))) {
            assertFailsWith<BackupValidationException> { convert(document(payload)) }
        }
    }
    @Test fun `old structurally valid but invalid active schedule is rejected by target validation`() {
        val old = BackupFixtures.fullPayload(); val schedule = old.tables.getValue("schedules").single()
        for (bad in listOf(schedule.with("endAt", BackupValue.Integer(1000)), schedule.with("repeatInterval", BackupValue.Integer(0)), schedule.with("reminderMinutes", BackupValue.Integer(-1)))) {
            val source = document(old.withTable("schedules", listOf(bad)))
            BackupValidator.validate(source)
            assertFailsWith<BackupValidationException> { convert(source) }
        }
    }
    @Test fun `converted source mappings count against exact aggregate row boundary`() {
        val result = convert(limits = BackupLimits(maxRows = 12))
        assertEquals(12, target(result).payload.tables.values.sumOf { it.size })
        val counts = result.rowCounts
        assertEquals(mapOf("plans" to 2,"tags" to 1,"plan_tag_cross_ref" to 1,"plan_exceptions" to 1,"plan_legacy_refs" to 2,
            "habits" to 1,"habit_records" to 1,"subscriptions" to 1,"subscription_payments" to 1,"subscription_reminders" to 1), counts)
        assertFailsWith<BackupValidationException> { convert(limits = BackupLimits(maxRows = 11)) }
    }
    @Test fun `converted canonical envelope checks exact byte boundary without truncation`() {
        val target = target(convert()); val size = V2BackupJsonCodec().encode(target).size
        assertEquals(target, target(convert(limits = BackupLimits(maxBytes = size))))
        assertFailsWith<BackupValidationException> { convert(limits = BackupLimits(maxBytes = size - 1)) }
    }
    @Test fun `real 100k converted row budget accepts 50k plans and rejects one more mapping`() {
        val base = BackupFixtures.fullPayload().tables.getValue("todos").single().with("dueAt", BackupValue.Null)
        fun source(count: Int) = document(BackupPayload(BackupFixtures.emptyTables + ("todos" to List(count) { base.with("id", BackupValue.Integer(it.toLong())) }), com.example.lifemanager.domain.model.AppSettings()))
        val result = target(convert(source(50_000)))
        assertEquals(100_000, result.payload.tables.values.sumOf { it.size })
        assertFailsWith<BackupValidationException> { convert(source(50_001)) }
    }
}
