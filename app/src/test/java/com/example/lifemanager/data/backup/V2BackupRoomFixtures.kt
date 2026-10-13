package com.example.lifemanager.data.backup

import androidx.room.Room
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.data.plan.PlanMigrationTestDatabase
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

internal object V2BackupRoomFixtures {
    val order = listOf("tags", "plans", "plan_tag_cross_ref", "plan_exceptions", "plan_legacy_refs", "habits", "habit_records",
        "subscriptions", "subscription_payments", "subscription_reminders")
    val autoTables = listOf("plans", "tags", "habits", "habit_records", "subscriptions", "subscription_payments")
    fun full(): V2BackupPayload = V2BackupFixtures.full().let { value -> value.table("plans", listOf(
        V2BackupFixtures.plan(17, 9007199254740993L), V2BackupFixtures.plan(9007199254740993L)))
        .table("plan_legacy_refs", listOf(V2BackupFixtures.row("source" to "TODO", "legacyId" to Long.MIN_VALUE, "planId" to 17L),
            V2BackupFixtures.row("source" to "SCHEDULE", "legacyId" to Long.MAX_VALUE, "planId" to 9007199254740993L))) }
    fun withDatabase(block: suspend (PlanMigrationTestDatabase) -> Unit): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlanMigrationTestDatabase::class.java)
            .allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("INSERT INTO app_settings VALUES (1,'SYSTEM','YMD','CNY',1,1,0)")
        database.openHelper.writableDatabase.execSQL("INSERT INTO app_maintenance VALUES (1,5)")
        try { block(database) } finally { database.close() }
    }
    /** Independent SQL fixture setup: never uses the repository under test to seed expected data. */
    fun seed(database: PlanMigrationTestDatabase, payload: V2BackupPayload = full()) {
        val sqlite = database.openHelper.writableDatabase
        for (name in order) for (value in payload.tables.getValue(name)) {
            val fields = if (name == "plans") value.fields + ("parentId" to BackupValue.Null) else value.fields
            val sql = "INSERT INTO `$name` (${fields.keys.joinToString(",") { "`$it`" }}) VALUES (${fields.keys.joinToString(",") { "?" }})"
            sqlite.execSQL(sql, fields.values.map(::bind).toTypedArray())
        }
        payload.tables.getValue("plans").forEach {
            sqlite.execSQL("UPDATE plans SET parentId=? WHERE id=?", arrayOf(bind(it.fields.getValue("parentId")), bind(it.fields.getValue("id"))))
        }
        val settings = payload.settings
        val mask = settings.defaultReminderDays.sumOf { when(it) { 1 -> 1; 3 -> 2; 7 -> 4; else -> error("fixture") } }
        sqlite.execSQL("UPDATE app_settings SET theme=?,dateFormat=?,defaultCurrency=?,planReminders=?,subscriptionReminders=?,defaultReminderMask=? WHERE id=1",
            arrayOf<Any>(settings.theme.name, settings.dateFormat.name, settings.defaultCurrency, if(settings.planReminders) 1 else 0, if(settings.subscriptionReminders) 1 else 0, mask))
    }
    private fun bind(value: BackupValue): Any? = when(value) {
        BackupValue.Null -> null; is BackupValue.Integer -> value.value; is BackupValue.Text -> value.value; is BackupValue.Flag -> if(value.value) 1L else 0L
    }
    fun generation(db: PlanMigrationTestDatabase): Long = db.openHelper.writableDatabase.query("SELECT generation FROM app_maintenance WHERE id=1").use { it.moveToFirst(); it.getLong(0) }
    fun technical(db: PlanMigrationTestDatabase) = db.openHelper.writableDatabase.query("SELECT planId,generation,occurrenceDate,triggerAt,delivered FROM plan_reminder_state ORDER BY planId").use {
        buildList { while(it.moveToNext()) add((0..4).map(it::getLong)) }
    }
    fun inventory(db: PlanMigrationTestDatabase) = db.openHelper.writableDatabase.execSQL("INSERT INTO plan_reminder_state VALUES (17,4,21000,9223372036854775807,2)")
    fun scalar(db: PlanMigrationTestDatabase, sql: String): Long = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }
    fun sameRows(expected: V2BackupPayload, actual: V2BackupPayload) {
        kotlin.test.assertEquals(expected.settings, actual.settings)
        kotlin.test.assertEquals(expected.tables.keys, actual.tables.keys)
        for (name in order) kotlin.test.assertEquals(expected.tables.getValue(name).toSet(), actual.tables.getValue(name).toSet(), name)
    }

    suspend fun snapshot(db: PlanMigrationTestDatabase, limits: BackupLimits = BackupLimits(), dao: BackupSnapshotDao = db.backup()): V2BackupPayload =
        V2BackupRepositoryImpl(db, dao, limits).snapshot()
    suspend fun replace(db: PlanMigrationTestDatabase, payload: V2BackupPayload, expected: Long = 5, limits: BackupLimits = BackupLimits(), dao: BackupSnapshotDao = db.backup()): DataGeneration =
        V2BackupMutationRepositoryImpl(db, dao, limits).replace(DataGeneration(expected), payload)
    suspend fun clear(db: PlanMigrationTestDatabase, expected: Long = 5, dao: BackupSnapshotDao = db.backup()): DataGeneration =
        V2BackupMutationRepositoryImpl(db, dao).clearBusinessData(DataGeneration(expected))
}
