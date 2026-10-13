package com.example.lifemanager.domain.backup

import com.example.lifemanager.data.backup.V2BackupFixtures as F
import com.example.lifemanager.data.backup.table
import com.example.lifemanager.data.backup.with
import org.junit.Test
import kotlin.test.*

class V2BackupValidatorTest {
    @Test fun `accepts raw historical values and all four valid active modes without rewriting`() {
        val full = F.full(); val original = F.full() // independent maps/rows; normalization cannot alter the expected snapshot
        V2BackupValidator.validate(F.document(full)); assertEquals(original, full)
        for (mode in listOf("NONE", "DEADLINE", "TIMED", "ALL_DAY")) {
            val row = F.plan().with("timeMode", BackupValue.Text(mode)).with("dueDate", BackupValue.Null)
            V2BackupValidator.validate(F.document(F.empty().table("plans", listOf(row))))
        }
    }
    @Test fun `rejects missing unknown and technical tables`() {
        for (tables in listOf(F.empty().tables - "plans", F.empty().tables + ("todos" to emptyList()),
            F.empty().tables + ("app_maintenance" to emptyList()), F.empty().tables + ("plan_reminder_state" to emptyList()))) {
            assertFailsWith<BackupValidationException> { V2BackupValidator.validate(F.document(F.empty().copy(tables = tables))) }
        }
    }
    @Test fun `rejects missing nullable fields unknown fields and raw type mismatches`() {
        for (row in listOf(F.plan().copy(fields = F.plan().fields - "description"),
            F.plan().with("extra", BackupValue.Integer(1)), F.plan().with("id", BackupValue.Text("17")),
            F.plan().with("isCompleted", BackupValue.Integer(0)), F.plan().with("title", BackupValue.Null))) bad(row)
    }
    @Test fun `rejects nonpositive plan IDs but permits complete signed legacy IDs`() {
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) bad(F.plan(id))
        V2BackupValidator.validate(F.document(F.full()))
    }
    @Test fun `rejects invalid active deadline interval and all day combinations`() {
        val changes = listOf(
            mapOf("timeMode" to BackupValue.Text("DEADLINE")), // both due values
            mapOf("timeMode" to BackupValue.Text("DEADLINE"), "dueAt" to BackupValue.Null, "dueDate" to BackupValue.Null),
            mapOf("timeMode" to BackupValue.Text("TIMED"), "startAt" to BackupValue.Null),
            mapOf("timeMode" to BackupValue.Text("TIMED"), "endAt" to BackupValue.Integer(1000)),
            mapOf("timeMode" to BackupValue.Text("ALL_DAY"), "allDayEndDate" to BackupValue.Null),
            mapOf("timeMode" to BackupValue.Text("ALL_DAY"), "allDayEndDate" to BackupValue.Integer(19999)))
        changes.forEach { bad(F.plan().copy(fields = F.plan().fields + it)) }
    }
    @Test fun `rejects invalid retained dates weekdays zones configuration and enums`() {
        for ((name, value) in listOf("dueDate" to BackupValue.Integer(Long.MAX_VALUE), "repeatDaysOfWeek" to BackupValue.Text("1,8"),
            "timeZone" to BackupValue.Text("bad/zone"), "repeatInterval" to BackupValue.Integer(0),
            "reminderMinutes" to BackupValue.Integer(-1), "sortOrder" to BackupValue.Integer(Long.MAX_VALUE),
            "timeMode" to BackupValue.Text("UNKNOWN"), "repeatRule" to BackupValue.Text("bad"), "priority" to BackupValue.Text("bad"))) bad(F.plan().with(name, value))
    }
    @Test fun `rejects duplicate plan and relationship keys`() {
        badPayload(F.empty().table("plans", listOf(F.plan(), F.plan())))
        val full = F.full()
        for (table in listOf("plan_tag_cross_ref", "plan_exceptions", "plan_legacy_refs", "subscription_reminders")) {
            badPayload(full.table(table, full.tables.getValue(table) + full.tables.getValue(table).first()))
        }
    }
    @Test fun `rejects reused mapping target invalid source and orphan target`() {
        for (row in listOf(F.row("source" to "SCHEDULE", "legacyId" to 1L, "planId" to 17L),
            F.row("source" to "bad", "legacyId" to 1L, "planId" to Long.MAX_VALUE),
            F.row("source" to "SCHEDULE", "legacyId" to 1L, "planId" to 99L))) {
            badPayload(F.full().table("plan_legacy_refs", listOf(F.full().tables.getValue("plan_legacy_refs").first(), row)))
        }
    }
    @Test fun `rejects parent tag exception and unchanged module orphan references`() {
        bad(F.plan(parent = 99))
        for ((table, field) in listOf("plan_tag_cross_ref" to "tagId", "plan_exceptions" to "planId", "habit_records" to "habitId", "subscription_payments" to "subscriptionId")) {
            val full = F.full(); badPayload(full.table(table, listOf(full.tables.getValue(table).first().with(field, BackupValue.Integer(99)))))
        }
    }
    @Test fun `rejects self and multi node parent cycles`() {
        bad(F.plan(parent = 17))
        badPayload(F.empty().table("plans", listOf(F.plan(1, 2), F.plan(2, 1))))
    }
    @Test fun `counts source mappings in aggregate row budget and accepts exact bound`() {
        val payload = F.full(); val count = payload.tables.values.sumOf { it.size }
        V2BackupValidator.validate(F.document(payload), BackupLimits(maxRows = count))
        assertFailsWith<BackupValidationException> { V2BackupValidator.validate(F.document(payload), BackupLimits(maxRows = count - 1)) }
    }
    @Test fun `checks settings document metadata and unicode`() {
        for (settings in listOf(F.empty().settings.copy(defaultCurrency = "bad"), F.empty().settings.copy(defaultReminderDays = setOf(2)))) {
            badPayload(F.empty().copy(settings = settings))
        }
        for (document in listOf(F.document().copy(appVersion = " "), F.document().copy(exportedAt = "bad"))) {
            assertFailsWith<BackupValidationException> { V2BackupValidator.validate(document) }
        }
        bad(F.plan().with("description", BackupValue.Text("\uD800")))
    }
    @Test fun `100k parent chain is validated iteratively and overflow row is rejected`() {
        val base = F.plan().fields
        val rows = (1L..100_000L).map { BackupRow(base + mapOf("id" to BackupValue.Integer(it),
            "parentId" to if (it == 100_000L) BackupValue.Null else BackupValue.Integer(it + 1))) }
        val payload = F.empty().table("plans", rows)
        V2BackupValidator.validate(F.document(payload))
        badPayload(payload.table("plans", rows + F.plan(100_001)))
        badPayload(payload.table("plans", rows.dropLast(1) + F.plan(100_000, 1)))
    }
    private fun bad(row: BackupRow) = badPayload(F.empty().table("plans", listOf(row)))
    private fun badPayload(payload: V2BackupPayload) {
        assertFailsWith<BackupValidationException> { V2BackupValidator.validate(F.document(payload)) }
    }
}
