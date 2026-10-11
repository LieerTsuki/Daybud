package com.example.lifemanager.data.plan

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.migration.PlanMigration
import com.example.lifemanager.data.local.migration.PlanV6Schema
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PlanMigrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val migration = PlanMigration { ZoneId.of("Asia/Shanghai") }
    @Test fun historyV1() = chain(1)
    @Test fun historyV2() = chain(2)
    @Test fun historyV3() = chain(3)
    @Test fun historyV4() = chain(4)
    @Test fun historyV5() = chain(5)

    private fun chain(version: Int) = fixture(version) { name, before ->
        val db = target(name)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            for (table in immutable) if (table in before) assertEquals(before[table], sqlite.query("SELECT * FROM $table ORDER BY 1,2").use(::typedRows), table)
            assertEquals(listOf(listOf("1", "2"), listOf("2", null)), rows(sqlite, "SELECT id,parentId FROM plans WHERE id<=2 ORDER BY id"))
            assertEquals(listOf(listOf(if (version == 5) "8" else "1")), rows(sqlite, "SELECT generation FROM app_maintenance"))
            assertEquals(listOf(listOf("  子项🌱  ", "9007199254740993", "0", "8")),
                rows(sqlite, "SELECT title,dueAt,isCompleted,completedAt FROM plans WHERE id=1"))
            assertEquals(listOf(listOf("1", "4")), rows(sqlite, "SELECT * FROM plan_tag_cross_ref"))
            assertTrue(rows(sqlite, "PRAGMA foreign_key_check").isEmpty())
        } finally { db.close() }
    }

    @Test fun completeV5FieldsAndMapping() = fixture(5) { name, before ->
        val db = target(name)
        try {
            val sqlite = db.openHelper.writableDatabase
            immutable.forEach { assertEquals(before[it], sqlite.query("SELECT * FROM $it ORDER BY 1,2").use(::typedRows), it) }
            assertEquals(listOf(listOf("TODO", Long.MIN_VALUE.toString(), "1"),
                listOf("TODO", Long.MAX_VALUE.toString(), "2"),
                listOf("SCHEDULE", Long.MAX_VALUE.toString(), "3")),
                rows(sqlite, "SELECT source,legacyId,planId FROM plan_legacy_refs ORDER BY planId"))
            assertEquals(listOf(listOf("ALL_DAY", "100", "200", "20000", "20002", "America/New_York",
                "2,1,2", "0", "30", " 地点 ", "", "")),
                rows(sqlite, "SELECT timeMode,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,repeatDaysOfWeek,reminderEnabled,reminderMinutes,location,participants,description FROM plans WHERE id=3"))
            assertEquals(listOf(listOf("3", "20000", "1")), rows(sqlite, "SELECT * FROM plan_exceptions"))
            assertEquals(listOf(listOf("1", "DARK", "DMY", "USD", "1", "0", "7")), rows(sqlite, "SELECT * FROM app_settings"))
        } finally { db.close() }
    }

    @Test fun finalSchemaFailureRestoresV5() = fail(5)
    @Test fun finalSchemaFailureRestoresV4AndRemovesNewMetadata() = fail(4)
    private fun fail(version: Int) = fixture(version) { name, before ->
        var reached = false
        val broken = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration.migrate(db)
                assertEquals(listOf(listOf(if (version == 5) "8" else "1")), rows(db, "SELECT generation FROM app_maintenance"))
                assertTrue(rows(db, "SELECT name FROM sqlite_master WHERE name IN ('todos','schedules','todo_tag_cross_ref','schedule_exceptions','app_settings_v5')").isEmpty())
                assertEquals(listOf(listOf("3")), rows(db, "SELECT COUNT(*) FROM plans"))
                reached = true
                db.execSQL("DROP INDEX index_plans_parentId")
            }
        }
        val db = target(name, broken)
        try {
            val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("Migration didn't properly handle: plans") == true }, "unexpected failure: $failure")
        }
        finally { db.close() }
        assertTrue(reached, "fault point reached after generation advancement and old table removal")
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(version, it.version)
            assertEquals(before, rawSnapshot(it))
        }
    }

    @Test fun missingGenerationRejectsUpgradeWithoutReset() = fixture(5, listOf("DELETE FROM app_maintenance")) { name, before ->
        val db = target(name)
        try { assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) } finally { db.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(5, it.version); assertEquals(before, rawSnapshot(it))
        }
    }

    @Test fun newInstallationUsesV6DefaultsWithoutBusinessExamples() {
        val db = Room.inMemoryDatabaseBuilder(context, PlanMigrationDeviceDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            assertEquals(listOf(listOf("1", "SYSTEM", "YMD", "CNY", "1", "1", "0")), rows(sqlite, "SELECT * FROM app_settings"))
            assertEquals(listOf(listOf("1", "0")), rows(sqlite, "SELECT * FROM app_maintenance"))
            assertTrue(rows(sqlite, "SELECT * FROM plans").isEmpty())
        } finally { db.close() }
    }

    private fun target(name: String, last: Migration = migration) =
        Room.databaseBuilder(context, PlanMigrationDeviceDatabase::class.java, name)
            .addMigrations(*LifeManagerDatabase.MIGRATIONS, last).allowMainThreadQueries().build()

    private fun fixture(version: Int, faults: List<String> = emptyList(), block: (String, Map<String,List<List<String?>>>) -> Unit) {
        val name = "v1-plan-device-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val before = SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val asset = "com.example.lifemanager.data.local.LifeManagerDatabase/${maxOf(2,version)}.json"
                val entities = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().use { it.readText() })
                    .getJSONObject("database").getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    // v1 has no exported asset; the v2 todo/tag table set is unchanged, as in existing tests.
                    if (version == 1 && table !in listOf("todos","tags","todo_tag_cross_ref")) continue
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                fixtures.filter { when {
                    it.contains("app_") -> version >= 5
                    it.contains("subscription") -> version >= 4
                    it.contains("habit") -> version >= 3
                    it.contains("schedule") -> version >= 2
                    else -> true
                } }.forEach(sqlite::execSQL)
                faults.forEach(sqlite::execSQL)
                sqlite.version = version
                rawSnapshot(sqlite)
            }
            block(name, before)
        } finally { context.deleteDatabase(name) }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun rawSnapshot(db: SQLiteDatabase): Map<String,List<List<String?>>> {
        val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='room_master_table' ORDER BY name",null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return names.associateWith { table -> db.rawQuery("SELECT * FROM $table ORDER BY ${if (table == "android_metadata") "1" else "1,2"}",null).use(::typedRows) } +
            ("__schema" to db.rawQuery("SELECT name,sql FROM sqlite_master WHERE name!='room_master_table' ORDER BY name",null).use(::typedRows))
    }

    private fun typedRows(c: android.database.Cursor): List<List<String?>> = buildList {
        while (c.moveToNext()) add((0 until c.columnCount).map { i ->
            val type = c.getType(i)
            val value = when (type) {
                android.database.Cursor.FIELD_TYPE_NULL -> ""
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
                android.database.Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.toHexString(c.getDouble(i))
                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString(",")
                else -> c.getString(i)
            }
            "$type:$value"
        })
    }

    private val immutable = listOf("tags","habits","habit_records","subscriptions","subscription_payments","subscription_reminders")
    private val fixtures = listOf(
        "INSERT INTO todos VALUES (${Long.MIN_VALUE}, '  子项🌱  ', '', 'HIGH', 9007199254740993, 0, 8, 7, 9, ${Long.MAX_VALUE}, -3)",
        "INSERT INTO todos VALUES (${Long.MAX_VALUE}, '父项', NULL, 'NONE', NULL, 1, NULL, 1, 2, NULL, 0)",
        "INSERT INTO tags VALUES (4,' 工作 ',123,7)",
        "INSERT INTO todo_tag_cross_ref VALUES (${Long.MIN_VALUE},4)",
        "INSERT INTO schedules VALUES (${Long.MAX_VALUE}, '全天', 100, 200, 1, 20000, 20002, ' 地点 ', '', '', -1, 30, 'CUSTOM', 3, '2,1,2', NULL, 'America/New_York', 3, 4)",
        "INSERT INTO schedule_exceptions VALUES (${Long.MAX_VALUE},20000,1)",
        "INSERT INTO habits VALUES (5,'旧习惯','book',123,'DAILY',1,NULL,20000,'备注',7,8)",
        "INSERT INTO habit_records VALUES (9,5,19999,7)",
        "INSERT INTO subscriptions VALUES (8,'旧订阅',9007199254740993,'CNY','MONTHLY',20000,19900,'工具','备注',1,NULL,7,8)",
        "INSERT INTO subscription_payments VALUES (3,8,9876543210,'CNY',19999,'历史扣费')",
        "INSERT INTO subscription_reminders VALUES (8,3)",
        "INSERT INTO app_settings VALUES (1,'DARK','DMY','USD',1,0,0,7)",
        "INSERT INTO app_maintenance VALUES (1,7)",
    )
}
