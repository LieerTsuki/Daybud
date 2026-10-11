package com.example.lifemanager.data.local.migration

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.domain.backup.BackupKind
import com.example.lifemanager.domain.backup.BackupSchema
import com.example.lifemanager.domain.backup.checkBackupUnicode
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

/** Streams the fixed legacy table contract without SQLite numeric coercion. */
internal object LegacyPlanSqlRows {
    fun each(db: SupportSQLiteDatabase, table: String, action: (Cursor) -> Unit) {
        val contract = BackupSchema.tables.single { it.name == table }
        db.query("SELECT * FROM $table ORDER BY ${contract.primaryKey.joinToString(",")}").use { cursor ->
            require(cursor.columnNames.toSet() == contract.columns.map { it.name }.toSet()) { "Unexpected legacy columns: $table" }
            while (cursor.moveToNext()) {
                for (column in contract.columns) {
                    val index = cursor.getColumnIndexOrThrow(column.name)
                    if (cursor.isNull(index)) {
                        require(column.nullable) { "Missing $table.${column.name}" }
                        continue
                    }
                    when (column.kind) {
                        BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS, BackupKind.FLAG -> {
                            val value = cursor.sqlLong(column.name)
                            when (column.kind) {
                                BackupKind.INT -> require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                                BackupKind.DAY -> LocalDate.ofEpochDay(value)
                                BackupKind.MILLIS -> Instant.ofEpochMilli(value)
                                BackupKind.FLAG -> require(value == 0L || value == 1L)
                                else -> Unit
                            }
                        }
                        else -> {
                            val value = cursor.sqlText(column.name)
                            checkBackupUnicode(value)
                            require(column.choices == null || value in column.choices)
                            when (column.kind) {
                                BackupKind.ZONE -> ZoneId.of(value)
                                BackupKind.CURRENCY -> require(Currency.getInstance(value).defaultFractionDigits >= 0)
                                BackupKind.DAY_LIST -> require(value.isEmpty() || value.split(',').all { it.toIntOrNull() in 1..7 })
                                else -> Unit
                            }
                        }
                    }
                }
                action(cursor)
            }
        }
    }

    fun todo(c: Cursor) = TodoEntity(c.sqlLong("id"), c.sqlText("title"), c.sqlNullableText("description"),
        TodoPriority.valueOf(c.sqlText("priority")), c.sqlNullableLong("dueAt"), c.sqlFlag("isCompleted"),
        c.sqlNullableLong("completedAt"), c.sqlLong("createdAt"), c.sqlLong("updatedAt"),
        c.sqlNullableLong("parentId"), c.sqlInt("sortOrder"))

    fun schedule(c: Cursor) = ScheduleEntity(c.sqlLong("id"), c.sqlText("title"),
        c.sqlNullableLong("startAt"), c.sqlNullableLong("endAt"), c.sqlFlag("isAllDay"),
        c.sqlNullableLong("allDayStartDate"), c.sqlNullableLong("allDayEndDate"),
        c.sqlNullableText("location"), c.sqlNullableText("participants"), c.sqlNullableText("note"),
        c.sqlInt("color"), c.sqlNullableInt("reminderMinutes"), ScheduleRepeatRule.valueOf(c.sqlText("repeatRule")),
        c.sqlInt("repeatInterval"), c.sqlNullableText("repeatDaysOfWeek"), c.sqlNullableLong("repeatEndDate"),
        c.sqlText("timeZone"), c.sqlLong("createdAt"), c.sqlLong("updatedAt"))
}

internal fun Cursor.sqlLong(name: String): Long {
    val index = getColumnIndexOrThrow(name)
    require(getType(index) == Cursor.FIELD_TYPE_INTEGER) { "Expected SQLite integer: $name" }
    return getLong(index)
}
internal fun Cursor.sqlInt(name: String): Int = sqlLong(name).also {
    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Int overflow: $name" }
}.toInt()
internal fun Cursor.sqlText(name: String): String {
    val index = getColumnIndexOrThrow(name)
    require(getType(index) == Cursor.FIELD_TYPE_STRING) { "Expected SQLite text: $name" }
    return getString(index)
}
internal fun Cursor.sqlFlag(name: String): Boolean = sqlLong(name).also { require(it == 0L || it == 1L) }.let { it == 1L }
internal fun Cursor.sqlNullableLong(name: String): Long? = if (isNull(getColumnIndexOrThrow(name))) null else sqlLong(name)
internal fun Cursor.sqlNullableInt(name: String): Int? = if (isNull(getColumnIndexOrThrow(name))) null else sqlInt(name)
internal fun Cursor.sqlNullableText(name: String): String? = if (isNull(getColumnIndexOrThrow(name))) null else sqlText(name)
