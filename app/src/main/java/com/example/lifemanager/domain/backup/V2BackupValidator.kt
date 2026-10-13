package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanRules
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.util.Currency

object V2BackupValidator {
    fun validate(document: V2BackupDocument, limits: BackupLimits = BackupLimits()) {
        BackupValidator.validateMetadata(document.appVersion, document.exportedAt)
        validate(document.payload, limits)
    }

    fun validate(payload: V2BackupPayload, limits: BackupLimits = BackupLimits()) {
        checkBackup(payload.tables.keys == V2BackupSchema.tables.map { it.name }.toSet(), "v2备份业务表缺失或包含未知表")
        checkBackup(payload.tables.values.sumOf { it.size.toLong() } <= limits.maxRows, "备份超过 ${limits.maxRows} 行限制")
        validateSettings(payload.settings)
        BackupValidator.validateTables(payload.tables, V2BackupSchema.tables, "plans")
        payload.tables.getValue("plans").forEach { row ->
            validatePlan(row)
        }
    }

    internal fun validateSettings(settings: V2BackupSettings) {
        val currencyValid = try { Currency.getInstance(settings.defaultCurrency).defaultFractionDigits >= 0 } catch (_: IllegalArgumentException) { false }
        checkBackup(currencyValid && settings.defaultReminderDays.all { it in setOf(1, 3, 7) }, "备份设置无效")
    }

    private fun validatePlan(row: BackupRow) {
        val values = row.fields
        fun number(key: String) = (values.getValue(key) as? BackupValue.Integer)?.value
        fun text(key: String) = (values.getValue(key) as BackupValue.Text).value
        fun flag(key: String) = (values.getValue(key) as BackupValue.Flag).value
        checkBackup(checkNotNull(number("id")) > 0 && (number("parentId") == null || checkNotNull(number("parentId")) > 0), "计划主键必须为正整数")
        // Construct only a temporary read model; the raw payload/string representation is untouched.
        val plan = Plan(id = checkNotNull(number("id")), title = text("title"),
            description = (values.getValue("description") as? BackupValue.Text)?.value,
            priority = TodoPriority.valueOf(text("priority")), color = checkNotNull(number("color")).toInt(),
            location = (values.getValue("location") as? BackupValue.Text)?.value,
            participants = (values.getValue("participants") as? BackupValue.Text)?.value,
            timeMode = PlanTimeMode.valueOf(text("timeMode")), timeZone = text("timeZone"),
            dueAt = number("dueAt")?.let(Instant::ofEpochMilli), dueDate = number("dueDate")?.let(LocalDate::ofEpochDay),
            startAt = number("startAt")?.let(Instant::ofEpochMilli), endAt = number("endAt")?.let(Instant::ofEpochMilli),
            allDayStartDate = number("allDayStartDate")?.let(LocalDate::ofEpochDay), allDayEndDate = number("allDayEndDate")?.let(LocalDate::ofEpochDay),
            isCompleted = flag("isCompleted"), completedAt = number("completedAt")?.let(Instant::ofEpochMilli),
            repeatRule = ScheduleRepeatRule.valueOf(text("repeatRule")), repeatInterval = checkNotNull(number("repeatInterval")).toInt(),
            repeatDaysOfWeek = (values.getValue("repeatDaysOfWeek") as? BackupValue.Text)?.value?.takeIf { it.isNotEmpty() }
                ?.split(',')?.map { DayOfWeek.of(it.toInt()) }?.toSet().orEmpty(),
            repeatEndDate = number("repeatEndDate")?.let(LocalDate::ofEpochDay), reminderEnabled = flag("reminderEnabled"),
            reminderMinutes = number("reminderMinutes")?.toInt(), parentId = number("parentId"), sortOrder = checkNotNull(number("sortOrder")).toInt(),
            createdAt = Instant.ofEpochMilli(checkNotNull(number("createdAt"))), updatedAt = Instant.ofEpochMilli(checkNotNull(number("updatedAt"))))
        checkBackup(PlanRules.validateForRead(plan) == null, "计划有效时间或配置无效")
    }
}
