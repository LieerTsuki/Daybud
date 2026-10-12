package com.example.lifemanager.data.plan

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.migration.PlanMigration
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.PlanReminderState
import com.example.lifemanager.domain.plan.PlanReminderToken
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlanMigrationTest {
    private val conversion = PlanMigration { ZoneId.of("Asia/Shanghai") }
    private val immutable = listOf("tags", "habits", "habit_records", "subscriptions",
        "subscription_payments", "subscription_reminders")

    @Test fun `all source fields relationships and unrelated tables survive actual Room upgrade`() = fixture(5) { name, before ->
        upgrade(name) { db ->
            assertEquals(6, db.version)
            assertEquals(before.filterKeys { it in immutable }, snapshot(db).filterKeys { it in immutable })
            assertEquals(listOf(
                listOf("1", "TODO", Long.MIN_VALUE.toString()),
                listOf("2", "TODO", Long.MAX_VALUE.toString()),
                listOf("3", "SCHEDULE", "5"),
                listOf("4", "SCHEDULE", Long.MAX_VALUE.toString())),
                rows(db, "SELECT planId, source, legacyId FROM plan_legacy_refs ORDER BY planId"))
            assertEquals(listOf(listOf("1", "2"), listOf("2", null), listOf("3", null), listOf("4", null)),
                rows(db, "SELECT id, parentId FROM plans ORDER BY id"))
            assertEquals(listOf(listOf("1", "4")), rows(db, "SELECT * FROM plan_tag_cross_ref"))
            assertEquals(listOf(listOf("4", "20000", "1")), rows(db, "SELECT * FROM plan_exceptions"))
            assertEquals(listOf(listOf("1", "  子项🌱  ", "", "HIGH", "-14390954", null, null, "DEADLINE",
                "9007199254740993", null, null, null, null, null, "Asia/Shanghai", "0", "8", "NONE", "1",
                null, null, "1", "15", "2", "-3", "7", "9")),
                rows(db, "SELECT * FROM plans WHERE id=1"))
            assertEquals(listOf(listOf("3", " 日程 ", "", "NONE", "123", " 地点 ", "", "TIMED", null, null,
                "1000", "2000", "1", "2", "+08:00", "0", null, "CUSTOM", "3", "2,1,2", "30000",
                "0", null, null, "0", "11", "12")),
                rows(db, "SELECT * FROM plans WHERE id=3"))
            assertEquals(listOf(listOf("2", "父项", null, "NONE", "-14390954", null, null, "NONE",
                null, null, null, null, null, null, "Asia/Shanghai", "1", null, "NONE", "1",
                null, null, "1", "15", null, "0", "1", "2")), rows(db, "SELECT * FROM plans WHERE id=2"))
            assertEquals(listOf(listOf("4", "全天", null, "NONE", "-1", null, null, "ALL_DAY",
                null, null, "100", "200", "20000", "20002", "America/New_York", "0", null,
                "DAILY", "1", null, null, "0", "30", null, "0", "3", "4")), rows(db, "SELECT * FROM plans WHERE id=4"))
            assertEquals(listOf(listOf("1", "DARK", "DMY", "USD", "1", "0", "7")),
                rows(db, "SELECT * FROM app_settings"))
            assertEquals(listOf(listOf("1", "8")), rows(db, "SELECT * FROM app_maintenance"))
            assertFalse(snapshot(db).keys.any { it in listOf("todos", "schedules", "todo_tag_cross_ref", "schedule_exceptions") })
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun `SQL conversion preserves signed Long and Int limits without narrowing`() = fixture(5, listOf(
        "UPDATE todos SET dueAt=${Long.MIN_VALUE}, completedAt=${Long.MAX_VALUE}, createdAt=${Long.MIN_VALUE}, updatedAt=${Long.MAX_VALUE}, sortOrder=${Int.MIN_VALUE} WHERE id=${Long.MIN_VALUE}",
        "UPDATE todos SET sortOrder=${Int.MAX_VALUE} WHERE id=${Long.MAX_VALUE}",
        "UPDATE schedules SET startAt=${Long.MIN_VALUE}, endAt=${Long.MAX_VALUE}, color=${Int.MIN_VALUE}, createdAt=${Long.MIN_VALUE}, updatedAt=${Long.MAX_VALUE} WHERE id=5",
        "UPDATE schedules SET color=${Int.MAX_VALUE} WHERE id=${Long.MAX_VALUE}"
    )) { name, _ ->
        upgrade(name) { db ->
            assertEquals(listOf(listOf(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(),
                Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), Int.MIN_VALUE.toString())),
                rows(db, "SELECT dueAt,completedAt,createdAt,updatedAt,sortOrder FROM plans WHERE id=1"))
            assertEquals(listOf(listOf(Int.MAX_VALUE.toString())), rows(db, "SELECT sortOrder FROM plans WHERE id=2"))
            assertEquals(listOf(listOf(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(),
                Int.MIN_VALUE.toString(), Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())),
                rows(db, "SELECT startAt,endAt,color,createdAt,updatedAt FROM plans WHERE id=3"))
            assertEquals(listOf(listOf(Int.MAX_VALUE.toString())), rows(db, "SELECT color FROM plans WHERE id=4"))
        }
    }

    @Test fun `legacy reminder combinations retain per item eligibility`() {
        for (todo in 0..1) for (schedule in 0..1) fixture(5, listOf(
            "UPDATE app_settings SET todoReminders=$todo, scheduleReminders=$schedule")) { name, _ ->
            upgrade(name) { db ->
                assertEquals(listOf(listOf((todo or schedule).toString())),
                    rows(db, "SELECT planReminders FROM app_settings"))
                assertEquals(listOf(listOf(todo.toString()), listOf(todo.toString()),
                    listOf(schedule.toString()), listOf("0")),
                    rows(db, "SELECT reminderEnabled FROM plans ORDER BY id"))
            }
        }
    }

    @Test fun `historical blank titles and all completion combinations remain unchanged`() = fixture(5,
        listOf("DELETE FROM todo_tag_cross_ref", "DELETE FROM todos") +
            listOf("0,NULL", "0,8", "1,NULL", "1,8").mapIndexed { index, completion ->
                "INSERT INTO todos VALUES (${index - 1}, ' ', NULL, 'NONE', NULL, $completion, 1, 2, NULL, 0)"
            }) { name, _ ->
        upgrade(name) { db ->
            assertEquals(listOf(listOf(" ", "0", null), listOf(" ", "0", "8"),
                listOf(" ", "1", null), listOf(" ", "1", "8")),
                rows(db, "SELECT title,isCompleted,completedAt FROM plans WHERE timeMode='NONE' ORDER BY id"))
        }
    }

    @Test fun `history versions one through five use same complete migration chain`() {
        for (version in 1..5) fixture(version) { name, _ ->
            upgrade(name) { db ->
                assertEquals(6, db.version)
                assertEquals(listOf(listOf("  子项🌱  ", "9007199254740993", "2")),
                    rows(db, "SELECT title,dueAt,parentId FROM plans WHERE id=1"))
                assertEquals(listOf(listOf(if (version == 5) "8" else "1")),
                    rows(db, "SELECT generation FROM app_maintenance"))
                assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
            }
        }
    }

    @Test fun `fresh v6 creates persisted defaults without legacy tables`() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlanMigrationTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).allowMainThreadQueries().build()
        try {
            val opened = runCatching { db.openHelper.writableDatabase }
            assertTrue(opened.isSuccess, "new v6 must open: ${opened.exceptionOrNull()}")
            val sqlite = opened.getOrThrow()
            assertEquals(listOf(listOf("1", "SYSTEM", "YMD", "CNY", "1", "1", "0")),
                rows(sqlite, "SELECT * FROM app_settings"))
            assertEquals(listOf(listOf("1", "0")), rows(sqlite, "SELECT * FROM app_maintenance"))
            assertTrue(rows(sqlite, "SELECT * FROM plans").isEmpty())
        } finally { db.close() }
    }

    @Test fun `every historical upgrade creates empty cleanable reminder inventory`() {
        for (version in 1..5) fixture(version) { name, _ ->
            upgrade(name) { db -> assertReminderInventory(db) }
        }
    }

    @Test fun `fresh target includes the same empty reminder inventory`() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlanMigrationTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).allowMainThreadQueries().build()
        try { assertReminderInventory(db.openHelper.writableDatabase) } finally { db.close() }
    }

    @Test fun `inventory DDL failure restores historical source and leaves no target tables`() {
        for (version in listOf(4, 5)) fixture(version) { name, before ->
            var reached = false
            rollback(name, before, object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    conversion.migrate(object : SupportSQLiteDatabase by db {
                        override fun execSQL(sql: String) {
                            if (sql.startsWith("CREATE TABLE plan_reminder_state ")) {
                                assertEquals(listOf(listOf("0")), rows(db, "SELECT COUNT(*) FROM plans"))
                                reached = true
                                throw IllegalStateException("synthetic inventory DDL failure")
                            }
                            db.execSQL(sql)
                        }
                    })
                }
            }, originalVersion = version, expectedMessage = "synthetic inventory DDL failure")
            assertTrue(reached, "inventory DDL fault reached after preceding target tables exist")
        }
    }

    private fun assertReminderInventory(db: SupportSQLiteDatabase) {
        assertEquals(listOf(listOf("plan_reminder_state")), rows(db,
            "SELECT name FROM sqlite_master WHERE type='table' AND name='plan_reminder_state'"))
        assertTrue(rows(db, "SELECT * FROM plan_reminder_state").isEmpty())
        assertEquals(listOf(
            listOf("0", "planId", "INTEGER", "1", null, "1"),
            listOf("1", "generation", "INTEGER", "1", null, "0"),
            listOf("2", "occurrenceDate", "INTEGER", "1", null, "0"),
            listOf("3", "triggerAt", "INTEGER", "1", null, "0"),
            listOf("4", "delivered", "INTEGER", "1", null, "0")),
            rows(db, "PRAGMA table_info(plan_reminder_state)"))
        assertTrue(rows(db, "PRAGMA foreign_key_list(plan_reminder_state)").isEmpty(),
            "cleanup identity must survive business deletion")
    }

    @Test fun `migrated Room inventory preserves full tokens across deletion and file reopen`() = fixture(5) { name, _ ->
        val first = PlanReminderToken(1, DataGeneration(8), LocalDate.ofEpochDay(20454), Instant.ofEpochMilli(Long.MAX_VALUE))
        val other = PlanReminderToken(Long.MAX_VALUE, DataGeneration(8), LocalDate.ofEpochDay(20454), Instant.ofEpochMilli(Long.MIN_VALUE))
        val db = target(name)
        try {
            assertReminderInventory(db.openHelper.writableDatabase)
            runBlocking {
                val store = RoomPlanReminderStore(db.reminders())
                store.remember(first)
                store.remember(other)
                assertFalse(store.markDelivered(first.copy(generation = DataGeneration(7))))
                assertTrue(store.markDelivered(first))
                assertFalse(store.markDelivered(first))
                db.openHelper.writableDatabase.execSQL("DELETE FROM plans WHERE id=1")
                assertEquals(PlanReminderState(first, true), store.get(1))
                assertEquals(PlanReminderState(other, false), store.get(Long.MAX_VALUE))
            }
        } finally { db.close() }
        val reopened = target(name)
        try {
            runBlocking {
                val store = RoomPlanReminderStore(reopened.reminders())
                assertEquals(listOf(PlanReminderState(first, true), PlanReminderState(other, false)), store.all())
                assertTrue(rows(reopened.openHelper.writableDatabase, "SELECT id FROM plans WHERE id=1").isEmpty())
                assertEquals(listOf(listOf("8")), rows(reopened.openHelper.writableDatabase, "SELECT generation FROM app_maintenance"))
                store.forget(1)
                assertNull(store.get(1))
                assertEquals(PlanReminderState(other, false), store.get(Long.MAX_VALUE))
            }
        } finally { reopened.close() }
    }

    @Test fun `inventory schema failure after source drops restores complete v4 and v5`() {
        for (version in listOf(4, 5)) fixture(version) { name, before ->
            var reached = false
            rollback(name, before, object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    conversion.migrate(db)
                    assertLateState(db, if (version == 5) "8" else "1")
                    assertReminderInventory(db)
                    reached = true
                    db.execSQL("ALTER TABLE plan_reminder_state ADD COLUMN unexpected INTEGER")
                }
            }, originalVersion = version, expectedMessage = "Migration didn't properly handle: plan_reminder_state")
            assertTrue(reached, "inventory schema fault reached after generation and source removal")
        }
    }

    @Test fun `bad settings fail closed and retain complete v5`() {
        for (sql in listOf("DELETE FROM app_settings", "UPDATE app_settings SET id=2",
            "UPDATE app_settings SET theme='BAD'", "UPDATE app_settings SET dateFormat='BAD'",
            "UPDATE app_settings SET defaultCurrency='BAD'", "UPDATE app_settings SET todoReminders=2",
            "UPDATE app_settings SET scheduleReminders=1.5", "UPDATE app_settings SET defaultReminderMask=8")) {
            fixture(5, listOf(sql)) { name, before -> rollback(name, before) }
        }
    }

    @Test fun `missing malformed or exhausted generation never resets old data`() {
        for (sql in listOf("DELETE FROM app_maintenance", "UPDATE app_maintenance SET id=2",
            "UPDATE app_maintenance SET generation=-1", "UPDATE app_maintenance SET generation=1.5",
            "UPDATE app_maintenance SET generation='bad'", "UPDATE app_maintenance SET generation=${Long.MAX_VALUE}")) {
            fixture(5, listOf(sql)) { name, before -> rollback(name, before) }
        }
    }

    @Test fun `bad plan fields are not coerced or normalized`() {
        for (sql in listOf("UPDATE todos SET isCompleted=2", "UPDATE todos SET sortOrder=2147483648",
            "UPDATE todos SET title=x'ABCD'", "UPDATE todos SET priority='BAD'",
            "UPDATE schedules SET repeatDaysOfWeek='1,bad'", "UPDATE schedules SET repeatInterval=0",
            "UPDATE schedules SET timeZone='bad/zone'", "UPDATE schedules SET endAt=startAt WHERE id=5",
            "UPDATE schedules SET allDayEndDate=9223372036854775807 WHERE id=5")) {
            fixture(5, listOf(sql)) { name, before -> rollback(name, before) }
        }
    }

    @Test fun `orphans cycles and malformed exception dates roll back`() {
        for (sql in listOf("UPDATE todos SET parentId=999", "UPDATE todos SET parentId=id",
            "UPDATE todos SET parentId=${Long.MIN_VALUE} WHERE id=${Long.MAX_VALUE}",
            "UPDATE todo_tag_cross_ref SET tagId=999", "UPDATE todo_tag_cross_ref SET todoId=999",
            "UPDATE schedule_exceptions SET scheduleId=999",
            "UPDATE schedule_exceptions SET occurrenceDate=9223372036854775807",
            "UPDATE schedule_exceptions SET isCancelled=2")) {
            fixture(5, listOf(sql)) { name, before -> rollback(name, before) }
        }
    }

    @Test fun `SQL failure after copy rolls back target source settings and generation`() = fixture(5, listOf(
        "CREATE TRIGGER fail_generation BEFORE UPDATE ON app_maintenance BEGIN SELECT RAISE(ABORT,'synthetic failure'); END"
    )) { name, before ->
        var reached = false
        rollback(name, before, object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                conversion.migrate(object : SupportSQLiteDatabase by db {
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql.startsWith("UPDATE app_maintenance")) {
                            assertEquals(listOf(listOf("4")), rows(db, "SELECT COUNT(*) FROM plans"))
                            reached = true
                        }
                        db.execSQL(sql, bindArgs)
                    }
                })
            }
        }, expectedMessage = "synthetic failure")
        assertTrue(reached, "generation fault was reached after copying plans")
    }

    @Test fun `late generation trigger mutation of unrelated data is rejected and rolled back`() = fixture(5, listOf(
        "CREATE TRIGGER corrupt_habit AFTER UPDATE ON app_maintenance BEGIN UPDATE habits SET note='changed by trigger'; END"
    )) { name, before -> rollback(name, before, expectedMessage = "Unrelated data changed: habits") }

    @Test fun `target insert failure rolls back all schema and copied rows`() = fixture(5) { name, before ->
        var reached = false
        rollback(name, before, object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                conversion.migrate(object : SupportSQLiteDatabase by db {
                    override fun execSQL(sql: String) {
                        db.execSQL(sql)
                        if (sql.startsWith("CREATE TABLE plans ")) db.execSQL(
                            "CREATE TRIGGER fail_plan BEFORE INSERT ON plans WHEN NEW.id=2 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql.startsWith("INSERT INTO plans ") && bindArgs[0] == 2L) {
                            assertEquals(listOf(listOf("1")), rows(db, "SELECT COUNT(*) FROM plans"))
                            reached = true
                        }
                        db.execSQL(sql, bindArgs)
                    }
                })
            }
        }, expectedMessage = "synthetic failure")
        assertTrue(reached, "second insert fault was actually reached")
    }

    @Test fun `Room target schema validation failure after drops restores entire v5`() = fixture(5) { name, before ->
        var reached = false
        rollback(name, before, object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                conversion.migrate(db)
                assertLateState(db, "8")
                reached = true
                db.execSQL("DROP INDEX index_plans_parentId")
            }
        }, expectedMessage = "Migration didn't properly handle: plans")
        assertTrue(reached, "schema fault was reached after generation and drops")
    }

    @Test fun `late failure across four five six restores original v4 without new metadata`() = fixture(4) { name, before ->
        var reached = false
        rollback(name, before, object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                conversion.migrate(db)
                assertLateState(db, "1")
                reached = true
                db.execSQL("DROP INDEX index_plans_parentId")
            }
        }, originalVersion = 4, expectedMessage = "Migration didn't properly handle: plans")
        assertTrue(reached, "v4 chain reached final schema validation")
    }

    private fun assertLateState(db: SupportSQLiteDatabase, generation: String) {
        assertEquals(listOf(listOf(generation)), rows(db, "SELECT generation FROM app_maintenance"))
        assertTrue(rows(db, "SELECT name FROM sqlite_master WHERE name IN ('todos','schedules','todo_tag_cross_ref','schedule_exceptions','app_settings_v5')").isEmpty())
        assertEquals(listOf(listOf("4")), rows(db, "SELECT COUNT(*) FROM plans"))
    }

    private fun fixture(version: Int, faults: List<String> = emptyList(), block: (String, Map<String, List<List<String?>>>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val name = "v1-plan-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val before = SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                val entities = JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/${maxOf(2, version)}.json").readText())
                    .getJSONObject("database").getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    if (version == 1 && table !in listOf("todos", "tags", "todo_tag_cross_ref")) continue
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                fixtures.filter { sql ->
                    when {
                        sql.contains("app_") -> version >= 5
                        sql.contains("subscription") -> version >= 4
                        sql.contains("habit") -> version >= 3
                        sql.contains("schedule") -> version >= 2
                        else -> true
                    }
                }.forEach(db::execSQL)
                faults.forEach(db::execSQL)
                db.version = version
                rawSnapshot(db)
            }
            block(name, before)
        } finally { context.deleteDatabase(name) }
    }

    private fun upgrade(name: String, last: Migration = conversion, block: (SupportSQLiteDatabase) -> Unit) {
        val db = target(name, last)
        try {
            val result = runCatching { db.openHelper.writableDatabase }
            assertTrue(result.isSuccess, "actual upgrade must succeed: ${result.exceptionOrNull()}")
            block(result.getOrThrow())
        } finally { db.close() }
    }

    private fun rollback(name: String, before: Map<String, List<List<String?>>>, last: Migration = conversion, originalVersion: Int = 5, expectedMessage: String? = null) {
        val db = target(name, last)
        try {
            val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertNotNull(failure, "bad source must be rejected")
            if (expectedMessage != null) assertTrue(generateSequence(failure) { it.cause }
                .any { it.message?.contains(expectedMessage) == true }, "unexpected failure: $failure")
        }
        finally { db.close() }
        SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { original ->
            assertEquals(originalVersion, original.version)
            assertEquals(before, rawSnapshot(original))
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }
    private fun target(name: String, last: Migration = conversion) =
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), PlanMigrationTestDatabase::class.java, name)
            .addMigrations(*LifeManagerDatabase.MIGRATIONS, last).allowMainThreadQueries().build()
    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        rows(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='room_master_table' ORDER BY name")
            .associate { row -> row.single()!! to db.query("SELECT * FROM ${row.single()} ORDER BY ${if (row.single() == "android_metadata") "1" else "1,2"}").use(::typedRows) }
    private fun rawSnapshot(db: SQLiteDatabase): Map<String, List<List<String?>>> {
        val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='room_master_table' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return names.associateWith { table -> db.rawQuery("SELECT * FROM $table ORDER BY ${if (table == "android_metadata") "1" else "1,2"}", null).use(::typedRows) } +
            ("__schema" to db.rawQuery("SELECT name,sql FROM sqlite_master WHERE name!='room_master_table' ORDER BY name", null).use(::typedRows))
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

    private val fixtures = listOf(
        "INSERT INTO todos VALUES (${Long.MIN_VALUE}, '  子项🌱  ', '', 'HIGH', 9007199254740993, 0, 8, 7, 9, ${Long.MAX_VALUE}, -3)",
        "INSERT INTO todos VALUES (${Long.MAX_VALUE}, '父项', NULL, 'NONE', NULL, 1, NULL, 1, 2, NULL, 0)",
        "INSERT INTO tags VALUES (4, ' 工作 ', 123, 7)",
        "INSERT INTO todo_tag_cross_ref VALUES (${Long.MIN_VALUE}, 4)",
        "INSERT INTO schedules VALUES (5, ' 日程 ', 1000, 2000, 0, 1, 2, ' 地点 ', '', '', 123, NULL, 'CUSTOM', 3, '2,1,2', 30000, '+08:00', 11, 12)",
        "INSERT INTO schedules VALUES (${Long.MAX_VALUE}, '全天', 100, 200, 1, 20000, 20002, NULL, NULL, NULL, -1, 30, 'DAILY', 1, NULL, NULL, 'America/New_York', 3, 4)",
        "INSERT INTO schedule_exceptions VALUES (${Long.MAX_VALUE}, 20000, 1)",
        "INSERT INTO habits VALUES (5, '旧习惯', 'book', 123, 'DAILY', 1, NULL, 20000, '备注', 7, 8)",
        "INSERT INTO habit_records VALUES (9, 5, 19999, 7)",
        "INSERT INTO subscriptions VALUES (8, '旧订阅', 9007199254740993, 'CNY', 'MONTHLY', 20000, 19900, '工具', '备注', 1, NULL, 7, 8)",
        "INSERT INTO subscription_payments VALUES (3, 8, 9876543210, 'CNY', 19999, '历史扣费')",
        "INSERT INTO subscription_reminders VALUES (8, 3)",
        "INSERT INTO app_settings VALUES (1,'DARK','DMY','USD',1,0,0,7)",
        "INSERT INTO app_maintenance VALUES (1,7)",
    )
}
