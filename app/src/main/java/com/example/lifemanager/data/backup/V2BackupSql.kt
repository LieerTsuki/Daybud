package com.example.lifemanager.data.backup

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fixed identifiers only; raw SQLite values never pass through editor normalization. */
internal class V2BackupSql(private val dao: BackupSnapshotDao, private val limits: BackupLimits,
    private val owner: CoroutineContext? = null) {
    private suspend fun checkActive() {
        owner?.ensureActive()
        currentCoroutineContext().ensureActive()
    }
    suspend fun generation(): DataGeneration {
        checkActive()
        val result = dao.read(SimpleSQLiteQuery("SELECT id,generation FROM app_maintenance")).use {
            checkBackup(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L,
                "本机世代记录缺失或损坏")
            checkBackup(it.getType(1) == Cursor.FIELD_TYPE_INTEGER, "本机世代类型无效")
            val value = it.getLong(1)
            checkBackup(value >= 0 && !it.moveToNext(), "本机世代单行记录无效")
            DataGeneration(value)
        }
        checkActive()
        return result
    }

    suspend fun settings(): V2BackupSettings {
        checkActive()
        dao.read(SimpleSQLiteQuery("SELECT COUNT(*) FROM app_settings")).use {
            checkBackup(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L, "设置单行记录无效")
        }
        textPreflight(listOf("app_settings" to SETTINGS_COLUMNS))
        val rows = rows("app_settings", SETTINGS_COLUMNS, listOf("id"))
        checkBackup(rows.size == 1 && rows.single().fields.getValue("id") == BackupValue.Integer(1), "设置单行记录无效")
        val fields = rows.single().fields
        fun text(key: String) = (fields.getValue(key) as BackupValue.Text).value
        fun flag(key: String) = (fields.getValue(key) as BackupValue.Flag).value
        val mask = (fields.getValue("defaultReminderMask") as BackupValue.Integer).value
        checkBackup(mask in 0L..7L, "默认提醒组合位掩码无效")
        val theme = ThemeMode.entries.singleOrNull { it.name == text("theme") } ?: throw BackupValidationException("已保存主题无效")
        val date = DateFormat.entries.singleOrNull { it.name == text("dateFormat") } ?: throw BackupValidationException("已保存日期格式无效")
        return V2BackupSettings(theme, date, text("defaultCurrency"), flag("planReminders"), flag("subscriptionReminders"),
            listOf(1 to 1L, 3 to 2L, 7 to 4L).filter { mask and it.second != 0L }.map { it.first }.toSet())
            .also(V2BackupValidator::validateSettings)
    }

    suspend fun payload(): V2BackupPayload {
        generation() // Health check only; local generation is never exported.
        var count = 0L
        for (table in V2BackupSchema.tables) {
            checkActive()
            val value = dao.read(SimpleSQLiteQuery("SELECT COUNT(*) FROM `${table.name}`")).use {
                checkBackup(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER, "无法读取业务行数")
                it.getLong(0)
            }
            checkBackup(value >= 0 && value <= limits.maxRows, "备份业务行数超限")
            count += value
            checkBackup(count <= limits.maxRows, "备份超过 ${limits.maxRows} 行限制")
        }
        textPreflight(V2BackupSchema.tables.map { it.name to it.columns } + ("app_settings" to SETTINGS_COLUMNS))
        val savedSettings = settings()
        val tables = V2BackupSchema.tables.associate { it.name to rows(it.name, it.columns, it.primaryKey) }
        return V2BackupPayload(tables, savedSettings).also { validatePayload(it, limits) }
    }

    private suspend fun textPreflight(tables: List<Pair<String, List<BackupColumn>>>) {
        var total = 0L
        for ((name, columns) in tables) {
            checkActive()
            val text = columns.filter { it.kind in TEXT_KINDS }
            if (text.isEmpty()) continue
            val expression = text.joinToString("+") { "COALESCE(length(CAST(`${it.name}` AS BLOB)),0)" }
            val bytes = dao.read(SimpleSQLiteQuery("SELECT COALESCE(SUM($expression),0) FROM `$name`")).use {
                checkBackup(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER, "无法读取备份文本字节数")
                it.getLong(0)
            }
            checkBackup(bytes in 0L..limits.maxBytes.toLong(), "备份文本超过 ${limits.maxBytes} 字节限制")
            total += bytes
            checkBackup(total <= limits.maxBytes, "备份文本超过 ${limits.maxBytes} 字节限制")
        }
    }

    private suspend fun rows(name: String, columns: List<BackupColumn>, key: List<String>): List<BackupRow> {
        checkActive()
        val select = columns.joinToString(",") { "`${it.name}`" }
        val order = key.joinToString(",") { "`$it`" }
        return dao.read(SimpleSQLiteQuery("SELECT $select FROM `$name` ORDER BY $order")).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                checkActive()
                add(BackupRow(columns.mapIndexed { index, column -> column.name to readValue(cursor, index, column) }.toMap()))
            }
        } }
    }

    private fun readValue(cursor: Cursor, index: Int, column: BackupColumn): BackupValue {
        if (cursor.isNull(index)) {
            checkBackup(column.nullable, "${column.name} 已保存值不允许为空")
            return BackupValue.Null
        }
        return when (column.kind) {
            BackupKind.FLAG -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(index) in 0L..1L, "${column.name} 已保存布尔值无效")
                BackupValue.Flag(cursor.getLong(index) == 1L)
            }
            BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER, "${column.name} 已保存整数类型无效")
                BackupValue.Integer(cursor.getLong(index))
            }
            else -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_STRING, "${column.name} 已保存字符串类型无效")
                BackupValue.Text(cursor.getString(index))
            }
        }
    }

    /** Distinguishes no sequence from zero, and rejects corrupt/lowered/duplicate/exhausted state. */
    suspend fun sequences(): Map<String, Long?> {
        checkActive()
        val found = mutableMapOf<String, Long>()
        val placeholders = AUTO_TABLES.joinToString(",") { "?" }
        dao.read(SimpleSQLiteQuery("SELECT name,seq FROM sqlite_sequence WHERE name IN ($placeholders)", AUTO_TABLES.toTypedArray())).use {
            while (it.moveToNext()) {
                checkActive()
                checkBackup(it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getType(1) == Cursor.FIELD_TYPE_INTEGER, "自增序列类型无效")
                val name = it.getString(0); val value = it.getLong(1)
                checkBackup(name !in found && value >= 0 && value < Long.MAX_VALUE, "$name 自增序列损坏或已耗尽，不能安全更换数据")
                found[name] = value
            }
        }
        for (name in AUTO_TABLES) {
            checkActive()
            dao.read(SimpleSQLiteQuery("SELECT MAX(id) FROM `$name`")).use {
                checkBackup(it.moveToFirst(), "无法读取主键高水位")
                if (!it.isNull(0)) {
                    checkBackup(it.getType(0) == Cursor.FIELD_TYPE_INTEGER, "$name 主键类型无效")
                    checkBackup(found[name] != null && found.getValue(name) >= it.getLong(0).coerceAtLeast(0), "$name 自增序列缺失或低于当前主键")
                }
            }
        }
        return AUTO_TABLES.associateWith { found[it] }
    }

    /** Technical state is neither exported nor replaced. Stream fixed-width, framed integer rows. */
    suspend fun inventoryFingerprint(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(8)
        dao.read(SimpleSQLiteQuery("SELECT planId,generation,occurrenceDate,triggerAt,delivered FROM plan_reminder_state ORDER BY planId")).use {
            while (it.moveToNext()) {
                checkActive()
                digest.update(0x1e.toByte())
                for (index in 0..4) {
                    checkBackup(it.getType(index) == Cursor.FIELD_TYPE_INTEGER, "提醒技术库存类型无效")
                    buffer.clear(); buffer.putLong(it.getLong(index)); digest.update(buffer.array())
                }
            }
        }
        checkActive()
        return digest.digest()
    }

    companion object {
        val AUTO_TABLES = listOf("plans", "tags", "habits", "habit_records", "subscriptions", "subscription_payments")
        val INSERT_ORDER = listOf("tags", "plans", "plan_tag_cross_ref", "plan_exceptions", "plan_legacy_refs", "habits", "habit_records",
            "subscriptions", "subscription_payments", "subscription_reminders")
        private val TEXT_KINDS = setOf(BackupKind.TEXT, BackupKind.CURRENCY, BackupKind.ZONE, BackupKind.DAY_LIST)
        private val SETTINGS_COLUMNS = listOf(BackupColumn("id", BackupKind.INT), BackupColumn("theme", BackupKind.TEXT),
            BackupColumn("dateFormat", BackupKind.TEXT), BackupColumn("defaultCurrency", BackupKind.TEXT),
            BackupColumn("planReminders", BackupKind.FLAG), BackupColumn("subscriptionReminders", BackupKind.FLAG), BackupColumn("defaultReminderMask", BackupKind.INT))
        fun validatePayload(payload: V2BackupPayload, limits: BackupLimits) {
            V2BackupValidator.validate(payload, limits)
            // Payload-only boundary. The codec still checks the larger COMPLETE envelope at export/import.
            CanonicalBackupJson.byteCount(V2BackupJsonMapping.toJson(payload), limits.maxBytes)
        }
        fun validateRestoreIds(payload: V2BackupPayload) {
            for (name in AUTO_TABLES) checkBackup(payload.tables.getValue(name).none {
                it.fields.getValue("id") == BackupValue.Integer(Long.MAX_VALUE)
            }, "$name 自增主键已达上限，不能安全恢复；原数据未修改")
        }
    }
}
