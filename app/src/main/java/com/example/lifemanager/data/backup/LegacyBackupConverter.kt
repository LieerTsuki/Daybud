package com.example.lifemanager.data.backup

import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef
import com.example.lifemanager.data.plan.*
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.ZoneId

/** Pure bounded conversion; both source and target are validated even when called directly. */
class LegacyBackupConverter(private val limits: BackupLimits = BackupLimits()) {
    fun convert(source: BackupDocument, todoZone: ZoneId): BackupImportResult {
        BackupValidator.validate(source, limits)
        val tables = source.payload.tables
        val planCount = tables.getValue("todos").size.toLong() + tables.getValue("schedules").size
        checkBackup(tables.values.sumOf { it.size.toLong() } + planCount <= limits.maxRows,
            "转换后的备份超过 ${limits.maxRows} 行限制（包含来源映射）")
        val preferences = LegacyReminderPreferences(source.payload.settings.todoReminders, source.payload.settings.scheduleReminders)
        val todos = tables.getValue("todos").map(::todo).sortedBy { it.id }
        val schedules = tables.getValue("schedules").map(::schedule).sortedBy { it.id }
        // Source IDs are never used arithmetically; maxRows bounds this positive sequence below overflow.
        var nextId = 0L
        val converted = todos.map { LegacyPlanConverter.todo(it, ++nextId, todoZone, preferences) } +
            schedules.map { LegacyPlanConverter.schedule(it, ++nextId, preferences) }
        val references = LegacyPlanReferences(converted.map { it.reference })
        val plans = converted.mapIndexed { index, value ->
            raw(value.row.copy(parentId = if (index < todos.size) LegacyPlanRelations.parent(todos[index], references) else null))
        }
        val tags = tables.getValue("todo_tag_cross_ref").map {
            val link = LegacyPlanRelations.tag(TodoTagCrossRef(it.long("todoId"), it.long("tagId")), references)
            row("planId" to link.planId, "tagId" to link.tagId)
        }
        val exceptions = tables.getValue("schedule_exceptions").map {
            val value = LegacyPlanRelations.exception(ScheduleExceptionEntity(it.long("scheduleId"), it.long("occurrenceDate"), it.flag("isCancelled")), references)
            row("planId" to value.planId, "occurrenceDate" to value.occurrenceDate, "isCancelled" to value.isCancelled)
        }
        val targetTables = linkedMapOf("plans" to plans, "plan_tag_cross_ref" to tags, "plan_exceptions" to exceptions,
            "plan_legacy_refs" to converted.map { row("source" to it.reference.source.name, "legacyId" to it.reference.legacyId, "planId" to it.reference.planId) })
        for (name in listOf("tags", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")) {
            targetTables[name] = tables.getValue(name)
        }
        val settings = source.payload.settings
        val target = V2BackupDocument(source.appVersion, source.exportedAt, V2BackupPayload(targetTables,
            V2BackupSettings(settings.theme, settings.dateFormat, settings.defaultCurrency, preferences.planEnabled,
                settings.subscriptionReminders, settings.defaultReminderDays)))
        V2BackupValidator.validate(target, limits)
        // Includes escaping, keys, hash and metadata without allocating encoded output.
        CanonicalBackupJson.byteCount(V2BackupJsonMapping.envelope(target), limits.maxBytes)
        return BackupImportResult(target, BackupSourceFormat.V1, todoZone.id.takeIf { todos.isNotEmpty() })
    }

    private fun todo(row: BackupRow) = TodoEntity(id = row.long("id"), title = row.text("title"), description = row.nullableText("description"),
        priority = TodoPriority.valueOf(row.text("priority")), dueAt = row.nullableLong("dueAt"), isCompleted = row.flag("isCompleted"),
        completedAt = row.nullableLong("completedAt"), createdAt = row.long("createdAt"), updatedAt = row.long("updatedAt"),
        parentId = row.nullableLong("parentId"), sortOrder = row.long("sortOrder").toInt())

    private fun schedule(row: BackupRow) = ScheduleEntity(id = row.long("id"), title = row.text("title"), startAt = row.nullableLong("startAt"),
        endAt = row.nullableLong("endAt"), isAllDay = row.flag("isAllDay"), allDayStartDate = row.nullableLong("allDayStartDate"),
        allDayEndDate = row.nullableLong("allDayEndDate"), location = row.nullableText("location"), participants = row.nullableText("participants"),
        note = row.nullableText("note"), color = row.long("color").toInt(), reminderMinutes = row.nullableLong("reminderMinutes")?.toInt(),
        repeatRule = ScheduleRepeatRule.valueOf(row.text("repeatRule")), repeatInterval = row.long("repeatInterval").toInt(),
        repeatDaysOfWeek = row.nullableText("repeatDaysOfWeek"), repeatEndDate = row.nullableLong("repeatEndDate"), timeZone = row.text("timeZone"),
        createdAt = row.long("createdAt"), updatedAt = row.long("updatedAt"))

    private fun raw(value: RawPlanRow) = row("id" to value.id, "title" to value.title, "description" to value.description,
        "priority" to value.priority.name, "color" to value.color, "location" to value.location, "participants" to value.participants,
        "timeMode" to value.timeMode.name, "dueAt" to value.dueAt, "dueDate" to value.dueDate, "startAt" to value.startAt, "endAt" to value.endAt,
        "allDayStartDate" to value.allDayStartDate, "allDayEndDate" to value.allDayEndDate, "timeZone" to value.timeZone,
        "isCompleted" to value.isCompleted, "completedAt" to value.completedAt, "repeatRule" to value.repeatRule.name,
        "repeatInterval" to value.repeatInterval, "repeatDaysOfWeek" to value.repeatDaysOfWeek, "repeatEndDate" to value.repeatEndDate,
        "reminderEnabled" to value.reminderEnabled, "reminderMinutes" to value.reminderMinutes, "parentId" to value.parentId,
        "sortOrder" to value.sortOrder, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)

    private fun row(vararg values: Pair<String, Any?>) = BackupRow(values.associate { (key, value) -> key to when(value) {
        null -> BackupValue.Null; is Long -> BackupValue.Integer(value); is Int -> BackupValue.Integer(value.toLong())
        is String -> BackupValue.Text(value); is Boolean -> BackupValue.Flag(value); else -> error("Unsupported internal backup value")
    } })
    // Access only after the original static schema/type validation, never a permissive fallback cast.
    private fun BackupRow.long(key: String) = (fields.getValue(key) as BackupValue.Integer).value
    private fun BackupRow.nullableLong(key: String) = if (fields.getValue(key) == BackupValue.Null) null else long(key)
    private fun BackupRow.text(key: String) = (fields.getValue(key) as BackupValue.Text).value
    private fun BackupRow.nullableText(key: String) = if (fields.getValue(key) == BackupValue.Null) null else text(key)
    private fun BackupRow.flag(key: String) = (fields.getValue(key) as BackupValue.Flag).value
}
