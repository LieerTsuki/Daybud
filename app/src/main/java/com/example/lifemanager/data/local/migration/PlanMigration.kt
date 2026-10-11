package com.example.lifemanager.data.local.migration

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef
import com.example.lifemanager.data.plan.*
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

/** Room owns the upgrade transaction, including historical migrations and final schema validation. */
class PlanMigration(private val todoZone: () -> ZoneId = { ZoneId.systemDefault() }) : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "Plan upgrade requires the Room migration transaction" }
        val settings = legacySettings(db)
        val nextGeneration = Math.addExact(generation(db), 1)
        val zone = todoZone()
        checkForeignKeys(db)
        val untouched = immutable.associateWith { fingerprint(db, it) }
        val sourceCount = Math.addExact(count(db, "todos"), count(db, "schedules"))
        PlanV6Schema.createPlans(db)
        var nextId = 0L
        LegacyPlanSqlRows.each(db, "todos") { cursor ->
            nextId = Math.addExact(nextId, 1)
            store(db, LegacyPlanConverter.todo(LegacyPlanSqlRows.todo(cursor), nextId, zone, settings.second))
        }
        LegacyPlanSqlRows.each(db, "schedules") { cursor ->
            nextId = Math.addExact(nextId, 1)
            store(db, LegacyPlanConverter.schedule(LegacyPlanSqlRows.schedule(cursor), nextId, settings.second))
        }
        val lookup = LegacyPlanLookup { key ->
            db.query("SELECT planId FROM plan_legacy_refs WHERE source=? AND legacyId=?",
                arrayOf<Any>(key.source.name, key.legacyId)).use { c -> if (c.moveToFirst()) c.sqlLong("planId") else null }
        }
        LegacyPlanSqlRows.each(db, "todos") { cursor ->
            val old = LegacyPlanSqlRows.todo(cursor)
            val id = requireNotNull(lookup.find(LegacyPlanKey(LegacyPlanSource.TODO, old.id)))
            val parent = LegacyPlanRelations.parent(old, lookup)
            db.execSQL("UPDATE plans SET parentId=? WHERE id=?", arrayOf(parent, id))
            check(PlanRowSql.read(db, id) == LegacyPlanConverter.todo(old, id, zone, settings.second).row.copy(parentId = parent)) {
                "Converted todo fields differ"
            }
        }
        LegacyPlanSqlRows.each(db, "schedules") { cursor ->
            val old = LegacyPlanSqlRows.schedule(cursor)
            val id = requireNotNull(lookup.find(LegacyPlanKey(LegacyPlanSource.SCHEDULE, old.id)))
            check(PlanRowSql.read(db, id) == LegacyPlanConverter.schedule(old, id, settings.second).row) {
                "Converted schedule fields differ"
            }
        }
        LegacyPlanSqlRows.each(db, "todo_tag_cross_ref") { c ->
            val mapped = LegacyPlanRelations.tag(TodoTagCrossRef(c.sqlLong("todoId"), c.sqlLong("tagId")), lookup)
            db.execSQL("INSERT INTO plan_tag_cross_ref VALUES (?,?)", arrayOf(mapped.planId, mapped.tagId))
        }
        LegacyPlanSqlRows.each(db, "schedule_exceptions") { c ->
            val mapped = LegacyPlanRelations.exception(ScheduleExceptionEntity(c.sqlLong("scheduleId"),
                c.sqlLong("occurrenceDate"), c.sqlFlag("isCancelled")), lookup)
            LocalDate.ofEpochDay(mapped.occurrenceDate)
            db.execSQL("INSERT INTO plan_exceptions VALUES (?,?,?)",
                arrayOf(mapped.planId, mapped.occurrenceDate, if (mapped.isCancelled) 1 else 0))
        }
        check(count(db, "plans") == sourceCount && count(db, "plan_legacy_refs") == sourceCount)
        check(count(db, "plan_tag_cross_ref") == count(db, "todo_tag_cross_ref"))
        check(count(db, "plan_exceptions") == count(db, "schedule_exceptions"))
        // In a one-parent graph all valid trees are reachable from roots; disconnected cycles are not.
        db.query("WITH RECURSIVE tree(id) AS (SELECT id FROM plans WHERE parentId IS NULL UNION ALL SELECT p.id FROM plans p JOIN tree t ON p.parentId=t.id) SELECT COUNT(*) FROM tree").use {
            check(it.moveToFirst() && it.getLong(0) == sourceCount) { "Plan parent cycle" }
        }
        checkForeignKeys(db)
        untouched.forEach { (table, digest) -> check(fingerprint(db, table) == digest) { "Unrelated data changed: $table" } }
        db.execSQL("ALTER TABLE app_settings RENAME TO app_settings_v5")
        db.execSQL(PlanV6Schema.SETTINGS)
        val target = settings.first
        db.execSQL("INSERT INTO app_settings VALUES (?,?,?,?,?,?,?)", arrayOf<Any>(1, target.theme, target.dateFormat,
            target.defaultCurrency, if (target.planReminders) 1 else 0, if (target.subscriptionReminders) 1 else 0,
            target.defaultReminderMask))
        db.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1", arrayOf(nextGeneration))
        check(generation(db) == nextGeneration)
        db.query("SELECT * FROM app_settings").use { c ->
            check(c.moveToFirst() && c.sqlInt("id") == 1 && c.sqlText("theme") == target.theme &&
                c.sqlText("dateFormat") == target.dateFormat && c.sqlText("defaultCurrency") == target.defaultCurrency &&
                c.sqlFlag("planReminders") == target.planReminders && c.sqlFlag("subscriptionReminders") == target.subscriptionReminders &&
                c.sqlInt("defaultReminderMask") == target.defaultReminderMask && !c.moveToNext()) { "Converted settings differ" }
        }
        for (table in listOf("todo_tag_cross_ref", "schedule_exceptions", "todos", "schedules", "app_settings_v5")) {
            db.execSQL("DROP TABLE $table")
        }
        checkForeignKeys(db)
        // Settings/generation triggers are still inside Room's transaction; reject late side effects too.
        untouched.forEach { (table, digest) -> check(fingerprint(db, table) == digest) { "Unrelated data changed: $table" } }
    }

    private fun store(db: SupportSQLiteDatabase, value: LegacyPlanConversion) {
        value.row.toEntity().toDomain()
        PlanRowSql.insert(db, value.row)
        db.execSQL("INSERT INTO plan_legacy_refs VALUES (?,?,?)",
            arrayOf<Any>(value.reference.source.name, value.reference.legacyId, value.reference.planId))
    }

    private fun legacySettings(db: SupportSQLiteDatabase): Pair<PlanSettingsEntity, LegacyReminderPreferences> =
        db.query("SELECT * FROM app_settings").use { c ->
            require(c.moveToFirst() && c.sqlInt("id") == 1) { "Missing legacy settings" }
            val theme = c.sqlText("theme").also { ThemeMode.valueOf(it) }
            val dateFormat = c.sqlText("dateFormat").also { DateFormat.valueOf(it) }
            val currency = c.sqlText("defaultCurrency").also { require(Currency.getInstance(it).defaultFractionDigits >= 0) }
            val preferences = LegacyReminderPreferences(c.sqlFlag("todoReminders"), c.sqlFlag("scheduleReminders"))
            val target = PlanSettingsEntity(theme = theme, dateFormat = dateFormat, defaultCurrency = currency,
                planReminders = preferences.planEnabled, subscriptionReminders = c.sqlFlag("subscriptionReminders"),
                defaultReminderMask = c.sqlInt("defaultReminderMask").also { require(it in 0..7) })
            check(!c.moveToNext()) { "Multiple settings rows" }
            target to preferences
        }

    private fun generation(db: SupportSQLiteDatabase): Long = db.query("SELECT * FROM app_maintenance").use { c ->
        check(c.moveToFirst() && c.sqlInt("id") == 1) { "Missing local generation" }
        c.sqlLong("generation").also { check(it >= 0 && !c.moveToNext()) { "Invalid local generation" } }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    private fun checkForeignKeys(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Legacy/target foreign key violation" } }
    }

    /** Only fixed table digests live in memory; values include SQLite type and byte length. */
    private fun fingerprint(db: SupportSQLiteDatabase, table: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        LegacyPlanSqlRows.each(db, table) { c ->
            hash.update(0x7F.toByte())
            for (index in 0 until c.columnCount) {
                hash.update(c.getType(index).toByte())
                when (c.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> hash.update(ByteBuffer.allocate(8).putLong(c.getLong(index)).array())
                    Cursor.FIELD_TYPE_STRING -> {
                        val bytes = c.getString(index).toByteArray(Charsets.UTF_8)
                        hash.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                        hash.update(bytes)
                    }
                    else -> error("Unexpected persisted type")
                }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private val immutable = listOf("tags", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")
}
