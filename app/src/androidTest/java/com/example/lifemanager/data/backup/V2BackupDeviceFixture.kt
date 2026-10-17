package com.example.lifemanager.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.lifemanager.data.local.V6DeviceFixture
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.Dispatchers

/** Independent SQL fixtures; all real v2 adapters, generation and capture share the factory DB. */
internal class V2BackupDeviceFixture : Closeable {
    private val owner = V6DeviceFixture()
    val database = owner.database
    val generations = com.example.lifemanager.data.repository.DataGenerationRepositoryImpl(database)
    val coordinator = MaintenanceCoordinator(generations)
    val snapshots = V2BackupRepositoryImpl(database, database.backupSnapshotDao())
    val mutations = V2BackupMutationRepositoryImpl(database, database.backupSnapshotDao())
    val codec = VersionedBackupJsonCodec()
    val store = AndroidBackupFileStore(context, Dispatchers.IO)
    val files = V2BackupFileUseCases(snapshots, codec, store, coordinator, Dispatchers.IO)
    // Platform notification enumeration/effects are the only substituted ports.
    val capture = V2BackupReminderCaptureImpl(database, database.backupSnapshotDao(), BackupPlanNotificationSource { emptyList() })
    val effects = object : MaintenanceReminderEffects {
        override suspend fun clearPrevious(identities: ReminderIdentities) { cleared = identities }
        override suspend fun reconcile() { check(coordinator.state.value == MaintenanceState.IDLE) }
        override fun requestReconciliation() = Unit
    }
    var cleared: ReminderIdentities? = null
    val workflow = V2BackupMaintenanceWorkflow(snapshots, mutations, files, generations, coordinator, capture, effects, Dispatchers.IO)

    fun seed() {
        val sqlite = database.openHelper.writableDatabase
        for (sql in listOf(
            "INSERT INTO plans (id,title,description,priority,color,location,participants,timeMode,dueAt,dueDate,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,isCompleted,completedAt,repeatRule,repeatInterval,repeatDaysOfWeek,repeatEndDate,reminderEnabled,reminderMinutes,parentId,sortOrder,createdAt,updatedAt) VALUES (42,'parent',NULL,'NONE',-123,NULL,'','NONE',NULL,NULL,NULL,NULL,NULL,NULL,'UTC',1,NULL,'NONE',1,NULL,NULL,0,NULL,NULL,0,7,8)",
            "INSERT INTO plans (id,title,description,priority,color,location,participants,timeMode,dueAt,dueDate,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,isCompleted,completedAt,repeatRule,repeatInterval,repeatDaysOfWeek,repeatEndDate,reminderEnabled,reminderMinutes,parentId,sortOrder,createdAt,updatedAt) VALUES (41,'  合成计划🏠  ','原文','HIGH',-123,'',NULL,'NONE',-9223372036854775808,-1,1000,2000,20000,20001,'+08:00',0,7,'CUSTOM',2,'7,01,1',-1,1,0,42,-3,7,8)",
            "INSERT INTO tags VALUES (4,'合成标签',-123,7)",
            "INSERT INTO plan_tag_cross_ref VALUES (41,4)",
            "INSERT INTO plan_exceptions VALUES (41,21000,0)",
            "INSERT INTO plan_legacy_refs VALUES ('TODO',-9223372036854775808,41)",
            "INSERT INTO plan_legacy_refs VALUES ('SCHEDULE',9223372036854775807,42)",
            "INSERT INTO habits VALUES (5,'合成习惯','book',-123,'CUSTOM',1,'7,1,1',20000,NULL,7,8)",
            "INSERT INTO habit_records VALUES (9,5,19999,7)",
            "INSERT INTO habit_trees VALUES (31,5,'MAPLE',7)",
            "INSERT INTO habit_waterings VALUES (9,31)",
            "INSERT INTO subscriptions VALUES (8,'合成订阅',9223372036854775807,'CNY','MONTHLY',20000,19900,NULL,'',0,20001,7,8)",
            "INSERT INTO subscription_payments VALUES (3,8,9007199254740993,'EUR',19999,'合成历史')",
            "INSERT INTO subscription_reminders VALUES (8,3)",
            "UPDATE app_settings SET theme='DARK',dateFormat='DMY',defaultCurrency='EUR',planReminders=0,subscriptionReminders=1,defaultReminderMask=7 WHERE id=1",
            "UPDATE app_maintenance SET generation=73 WHERE id=1",
            "INSERT INTO plan_reminder_state VALUES (41,72,21000,9223372036854775807,2)",
        )) sqlite.execSQL(sql)
    }

    fun rows(tables: List<String> = businessTables): Map<String, Set<Map<String, String?>>> =
        tables.associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { c ->
                buildSet { while (c.moveToNext()) add(c.columnNames.associateWith { name ->
                    val index = c.getColumnIndexOrThrow(name)
                    if (c.isNull(index)) null else c.getString(index)
                }) }
            }
        }

    fun assertPayloadRows(expected: Map<String, Set<Map<String, String?>>>, actual: V2BackupPayload) {
        kotlin.test.assertEquals(expected.keys, actual.tables.keys)
        for ((table, rows) in actual.tables) {
            val cells = rows.map { row -> row.fields.mapValues { (_, value) ->
                when (value) {
                    BackupValue.Null -> null
                    is BackupValue.Integer -> value.value.toString()
                    is BackupValue.Text -> value.value
                    is BackupValue.Flag -> if (value.value) "1" else "0"
                }
            } }.toSet()
            kotlin.test.assertEquals(expected.getValue(table), cells, table)
        }
    }

    override fun close() = owner.close()

    companion object {
        val context: Context get() = ApplicationProvider.getApplicationContext()
        val businessTables = listOf("plans","tags","plan_tag_cross_ref","plan_exceptions","plan_legacy_refs",
            "habits","habit_records","subscriptions","subscription_payments","subscription_reminders","habit_trees","habit_waterings")
        val technicalTables = listOf("app_maintenance", "plan_reminder_state", "sqlite_sequence")
        fun newDocument(): Uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "e6-synthetic-${UUID.randomUUID()}.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/E6-tests")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
    }
}
