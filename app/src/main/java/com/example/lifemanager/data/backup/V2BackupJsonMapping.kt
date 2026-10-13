package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import kotlinx.serialization.json.*

internal object V2BackupJsonMapping {
    private val settingsKeys = setOf("theme", "dateFormat", "defaultCurrency", "planReminders", "subscriptionReminders", "defaultReminderDays")
    fun toJson(payload: V2BackupPayload) = buildJsonObject {
        for (table in V2BackupSchema.tables) put(table.name, JsonArray(payload.tables.getValue(table.name).map { row ->
            JsonObject(row.fields.mapValues { (_, value) -> when (value) {
                is BackupValue.Integer -> JsonPrimitive(value.value)
                is BackupValue.Text -> JsonPrimitive(value.value)
                is BackupValue.Flag -> JsonPrimitive(value.value)
                BackupValue.Null -> JsonNull
            } })
        }))
        put("settings", buildJsonObject {
            put("theme", payload.settings.theme.name); put("dateFormat", payload.settings.dateFormat.name)
            put("defaultCurrency", payload.settings.defaultCurrency); put("planReminders", payload.settings.planReminders)
            put("subscriptionReminders", payload.settings.subscriptionReminders)
            put("defaultReminderDays", JsonArray(payload.settings.defaultReminderDays.sorted().map(::JsonPrimitive)))
        })
    }
    fun fromJson(value: JsonElement): V2BackupPayload {
        val payload = value.objectWithKeys(V2BackupSchema.tables.map { it.name }.toSet() + "settings")
        val tables = V2BackupSchema.tables.associate { table -> table.name to payload.getValue(table.name).arrayValue().map { entry ->
            val row = entry.objectWithKeys(table.columns.map { it.name }.toSet())
            BackupRow(table.columns.associate { column -> column.name to row.getValue(column.name).let { item ->
                if (item == JsonNull) BackupValue.Null else when (column.kind) {
                    BackupKind.FLAG -> BackupValue.Flag(item.flagValue())
                    BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS -> BackupValue.Integer(item.integerValue())
                    else -> BackupValue.Text(item.textValue())
                }
            } })
        } }
        val settings = payload.getValue("settings").objectWithKeys(settingsKeys)
        val days = settings.getValue("defaultReminderDays").arrayValue().map {
            val number = it.integerValue(); checkBackup(number in setOf(1L, 3L, 7L), "默认提醒天数无效"); number.toInt()
        }
        checkBackup(days.size == days.toSet().size, "默认提醒天数重复")
        val theme = ThemeMode.entries.singleOrNull { it.name == settings.getValue("theme").textValue() }
            ?: throw BackupValidationException("主题枚举无效")
        val date = DateFormat.entries.singleOrNull { it.name == settings.getValue("dateFormat").textValue() }
            ?: throw BackupValidationException("日期格式枚举无效")
        return V2BackupPayload(tables, V2BackupSettings(theme, date, settings.getValue("defaultCurrency").textValue(),
            settings.getValue("planReminders").flagValue(), settings.getValue("subscriptionReminders").flagValue(), days.toSet()))
    }
}
