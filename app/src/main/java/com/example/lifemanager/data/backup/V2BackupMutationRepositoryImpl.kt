package com.example.lifemanager.data.backup

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteStatement
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pure target-store mutations; maintenance authorization/files/system cleanup belong to D4/E. */
class V2BackupMutationRepositoryImpl(private val database: RoomDatabase, private val dao: BackupSnapshotDao,
    private val limits: BackupLimits = BackupLimits()) : V2BackupMutationRepository {
    override suspend fun replace(expected: DataGeneration, payload: V2BackupPayload): DataGeneration {
        currentCoroutineContext().ensureActive()
        V2BackupValidator.validate(payload, limits)
        // Own the validated source across transaction suspension; callers may hold mutable Maps/Sets.
        val owned = payload.copy(tables = payload.tables.mapValues { (_, rows) -> rows.map { BackupRow(it.fields.toMap()) } },
            settings = payload.settings.copy(defaultReminderDays = payload.settings.defaultReminderDays.toSet()))
        V2BackupSql.validatePayload(owned, limits)
        V2BackupSql.validateRestoreIds(owned)
        currentCoroutineContext().ensureActive()
        return commit(expected, owned)
    }
    override suspend fun clearBusinessData(expected: DataGeneration): DataGeneration = commit(expected, null)

    private suspend fun commit(expected: DataGeneration, replacement: V2BackupPayload?): DataGeneration {
        // Room may run the transaction in another coroutine; cancellation of its caller still
        // forbids a commit, even before cancellation reaches Room's execution context.
        val incoming = currentCoroutineContext()
        val owner = incoming[V2BackupWriteCancellation]?.let { incoming + it.writer } ?: incoming
        val sql = V2BackupSql(dao, limits, owner)
        suspend fun checkActive() { owner.ensureActive(); currentCoroutineContext().ensureActive() }
        checkActive()
        return database.withTransaction {
            checkActive()
            if (sql.generation() != expected) throw StaleGenerationException()
            check(expected.value < Long.MAX_VALUE) { "本机世代已耗尽，不能安全更换数据" }
            val settings = sql.settings()
            val sequences = sql.sequences()
            val inventory = sql.inventoryFingerprint()
            val sqlite = database.openHelper.writableDatabase
            for (name in V2BackupSql.INSERT_ORDER.asReversed()) {
                checkActive()
                sqlite.execSQL("DELETE FROM `$name`")
            }
            if (replacement != null) {
                for (name in V2BackupSql.INSERT_ORDER) {
                    checkActive()
                    val table = V2BackupSchema.tables.single { it.name == name }
                    val columns = table.columns.joinToString(",") { "`${it.name}`" }
                    val placeholders = table.columns.joinToString(",") { "?" }
                    sqlite.compileStatement("INSERT INTO `$name` ($columns) VALUES ($placeholders)").use { statement ->
                        for (row in replacement.tables.getValue(name)) {
                            checkActive(); statement.clearBindings()
                            table.columns.forEachIndexed { index, column ->
                                statement.bind(index + 1, if (name == "plans" && column.name == "parentId") BackupValue.Null else row.fields.getValue(column.name))
                            }
                            statement.executeInsert()
                        }
                    }
                }
                sqlite.compileStatement("UPDATE plans SET parentId=? WHERE id=?").use { statement ->
                    for (row in replacement.tables.getValue("plans")) {
                        checkActive(); statement.clearBindings()
                        statement.bind(1, row.fields.getValue("parentId")); statement.bind(2, row.fields.getValue("id"))
                        checkBackup(statement.executeUpdateDelete() == 1, "恢复父关系失败")
                    }
                }
                val saved = replacement.settings
                val mask = saved.defaultReminderDays.sumOf { when(it) { 1 -> 1; 3 -> 2; 7 -> 4; else -> error("Validated reminder day") } }
                sqlite.compileStatement("UPDATE app_settings SET theme=?,dateFormat=?,defaultCurrency=?,planReminders=?,subscriptionReminders=?,defaultReminderMask=? WHERE id=1").use {
                    it.bindString(1, saved.theme.name); it.bindString(2, saved.dateFormat.name); it.bindString(3, saved.defaultCurrency)
                    it.bindLong(4, if(saved.planReminders) 1 else 0); it.bindLong(5, if(saved.subscriptionReminders) 1 else 0); it.bindLong(6, mask.toLong())
                    checkBackup(it.executeUpdateDelete() == 1, "恢复设置失败")
                }
            }
            checkBackup(sql.generation() == expected, "维护事务不应改写本机世代")
            val next = DataGeneration(expected.value + 1)
            sqlite.compileStatement("UPDATE app_maintenance SET generation=? WHERE id=1 AND generation=?").use {
                it.bindLong(1, next.value); it.bindLong(2, expected.value)
                checkBackup(it.executeUpdateDelete() == 1, "推进本机世代失败")
            }
            checkBackup(sql.generation() == next, "本机世代推进结果无效")
            val actual = sql.payload()
            if (replacement == null) {
                checkBackup(actual.tables.values.all { it.isEmpty() } && actual.settings == settings, "清空结果不一致")
            } else {
                checkBackup(actual.settings == replacement.settings && V2BackupSchema.tables.all {
                    actual.tables.getValue(it.name).toSet() == replacement.tables.getValue(it.name).toSet()
                }, "恢复后的原始字段或设置不一致")
            }
            val expectedSequences = sequences.mapValues { (name, previous) ->
                val rows = replacement?.tables?.getValue(name).orEmpty()
                if (previous == null && rows.isEmpty()) null else maxOf(previous ?: 0,
                    rows.maxOfOrNull { (it.fields.getValue("id") as BackupValue.Integer).value }?.coerceAtLeast(0) ?: 0)
            }
            checkBackup(sql.sequences() == expectedSequences, "恢复不应重置或改写自增序列")
            checkBackup(MessageDigest.isEqual(sql.inventoryFingerprint(), inventory), "维护事务不应改写提醒技术库存")
            checkActive()
            next
        }
    }
    private fun SupportSQLiteStatement.bind(index: Int, value: BackupValue) = when(value) {
        BackupValue.Null -> bindNull(index); is BackupValue.Integer -> bindLong(index, value.value)
        is BackupValue.Text -> bindString(index, value.value); is BackupValue.Flag -> bindLong(index, if(value.value) 1 else 0)
    }
}
