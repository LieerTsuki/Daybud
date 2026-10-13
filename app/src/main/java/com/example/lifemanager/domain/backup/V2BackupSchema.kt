package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.domain.plan.PlanLegacySource

/** Isolated format 2 / schema 6 whitelist. Never infer external names from Room or input. */
object V2BackupSchema {
    private fun c(name: String, kind: BackupKind, nullable: Boolean = false) = BackupColumn(name, kind, nullable)
    private fun e(name: String, choices: Set<String>) = BackupColumn(name, BackupKind.TEXT, choices = choices)
    val tables = listOf(
        BackupTable("plans", listOf(c("id", BackupKind.LONG), c("title", BackupKind.TEXT), c("description", BackupKind.TEXT, true),
            e("priority", TodoPriority.entries.map { it.name }.toSet()), c("color", BackupKind.INT), c("location", BackupKind.TEXT, true),
            c("participants", BackupKind.TEXT, true), e("timeMode", PlanTimeMode.entries.map { it.name }.toSet()),
            c("dueAt", BackupKind.MILLIS, true), c("dueDate", BackupKind.DAY, true), c("startAt", BackupKind.MILLIS, true),
            c("endAt", BackupKind.MILLIS, true), c("allDayStartDate", BackupKind.DAY, true), c("allDayEndDate", BackupKind.DAY, true),
            c("timeZone", BackupKind.ZONE), c("isCompleted", BackupKind.FLAG), c("completedAt", BackupKind.MILLIS, true),
            e("repeatRule", ScheduleRepeatRule.entries.map { it.name }.toSet()), c("repeatInterval", BackupKind.INT),
            c("repeatDaysOfWeek", BackupKind.DAY_LIST, true), c("repeatEndDate", BackupKind.DAY, true), c("reminderEnabled", BackupKind.FLAG),
            c("reminderMinutes", BackupKind.INT, true), c("parentId", BackupKind.LONG, true), c("sortOrder", BackupKind.INT),
            c("createdAt", BackupKind.MILLIS), c("updatedAt", BackupKind.MILLIS)), listOf("id"), references = listOf(BackupReference("parentId", "plans"))),
        BackupTable("plan_tag_cross_ref", listOf(c("planId", BackupKind.LONG), c("tagId", BackupKind.LONG)), listOf("planId", "tagId"),
            references = listOf(BackupReference("planId", "plans"), BackupReference("tagId", "tags"))),
        BackupTable("plan_exceptions", listOf(c("planId", BackupKind.LONG), c("occurrenceDate", BackupKind.DAY), c("isCancelled", BackupKind.FLAG)),
            listOf("planId", "occurrenceDate"), references = listOf(BackupReference("planId", "plans"))),
        BackupTable("plan_legacy_refs", listOf(e("source", PlanLegacySource.entries.map { it.name }.toSet()), c("legacyId", BackupKind.LONG), c("planId", BackupKind.LONG)),
            listOf("source", "legacyId"), listOf(listOf("planId")), listOf(BackupReference("planId", "plans"))),
    ) + listOf("tags", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders").map { name ->
        BackupSchema.tables.single { it.name == name }
    }
    internal val maxRowMembers = tables.maxOf { it.columns.size }
}
