package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.*

internal object V2BackupFixtures {
    val names = listOf("plans", "tags", "plan_tag_cross_ref", "plan_exceptions", "plan_legacy_refs",
        "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")
    fun empty() = V2BackupPayload(names.associateWith { emptyList<BackupRow>() }, V2BackupSettings())
    fun plan(id: Long = 17, parent: Long? = null) = row(
        "id" to id, "title" to "  \n", "description" to "原文\n\"🌱\"", "priority" to "HIGH", "color" to -123L,
        "location" to "", "participants" to null, "timeMode" to "NONE", "dueAt" to Long.MIN_VALUE, "dueDate" to -1L,
        "startAt" to 1000L, "endAt" to 2000L, "allDayStartDate" to 20000L, "allDayEndDate" to 20001L,
        "timeZone" to "+08:00", "isCompleted" to false, "completedAt" to 7L, "repeatRule" to "CUSTOM",
        "repeatInterval" to 2L, "repeatDaysOfWeek" to "7,01,1", "repeatEndDate" to -1L,
        "reminderEnabled" to true, "reminderMinutes" to 0L, "parentId" to parent, "sortOrder" to -3L,
        "createdAt" to 7L, "updatedAt" to 8L)
    fun full(): V2BackupPayload {
        val old = BackupFixtures.fullPayload()
        val unchanged = old.tables.filterKeys { it in setOf("tags", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders") }
        return empty().copy(tables = empty().tables + unchanged + mapOf(
            "plans" to listOf(plan(), plan(Long.MAX_VALUE, 17)),
            "plan_tag_cross_ref" to listOf(row("planId" to 17L, "tagId" to 4L)),
            "plan_exceptions" to listOf(row("planId" to 17L, "occurrenceDate" to 20000L, "isCancelled" to false)),
            "plan_legacy_refs" to listOf(row("source" to "TODO", "legacyId" to Long.MIN_VALUE, "planId" to 17L),
                row("source" to "SCHEDULE", "legacyId" to Long.MIN_VALUE, "planId" to Long.MAX_VALUE))),
            settings = V2BackupSettings(ThemeMode.DARK, DateFormat.DMY, "EUR", false, true, setOf(7, 1)))
    }
    fun document(payload: V2BackupPayload = full()) = V2BackupDocument("1.0.0", "2026-10-13T00:00:00Z", payload)
    fun row(vararg values: Pair<String, Any?>) = BackupRow(values.associate { (key, value) -> key to when (value) {
        null -> BackupValue.Null
        is Long -> BackupValue.Integer(value)
        is String -> BackupValue.Text(value)
        is Boolean -> BackupValue.Flag(value)
        else -> error("Bad synthetic fixture")
    } })
}
internal fun V2BackupPayload.table(name: String, rows: List<BackupRow>) = copy(tables = tables + (name to rows))
