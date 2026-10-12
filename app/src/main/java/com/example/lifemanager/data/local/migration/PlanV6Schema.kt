package com.example.lifemanager.data.local.migration

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Shared target DDL; not registered with the production v5 database. */
object PlanV6Schema {
    internal const val SETTINGS = "CREATE TABLE app_settings (id INTEGER NOT NULL, theme TEXT NOT NULL, dateFormat TEXT NOT NULL, defaultCurrency TEXT NOT NULL, planReminders INTEGER NOT NULL, subscriptionReminders INTEGER NOT NULL, defaultReminderMask INTEGER NOT NULL, PRIMARY KEY(id))"
    internal fun createPlans(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, description TEXT, priority TEXT NOT NULL, color INTEGER NOT NULL, location TEXT, participants TEXT, timeMode TEXT NOT NULL, dueAt INTEGER, dueDate INTEGER, startAt INTEGER, endAt INTEGER, allDayStartDate INTEGER, allDayEndDate INTEGER, timeZone TEXT NOT NULL, isCompleted INTEGER NOT NULL, completedAt INTEGER, repeatRule TEXT NOT NULL, repeatInterval INTEGER NOT NULL, repeatDaysOfWeek TEXT, repeatEndDate INTEGER, reminderEnabled INTEGER NOT NULL, reminderMinutes INTEGER, parentId INTEGER, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(parentId) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        for (column in listOf("parentId", "timeMode", "dueAt", "dueDate", "startAt", "allDayStartDate")) {
            db.execSQL("CREATE INDEX index_plans_$column ON plans ($column)")
        }
        db.execSQL("CREATE TABLE plan_tag_cross_ref (planId INTEGER NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(planId,tagId), FOREIGN KEY(planId) REFERENCES plans(id) ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_plan_tag_cross_ref_tagId ON plan_tag_cross_ref (tagId)")
        db.execSQL("CREATE TABLE plan_exceptions (planId INTEGER NOT NULL, occurrenceDate INTEGER NOT NULL, isCancelled INTEGER NOT NULL, PRIMARY KEY(planId,occurrenceDate), FOREIGN KEY(planId) REFERENCES plans(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE plan_legacy_refs (source TEXT NOT NULL, legacyId INTEGER NOT NULL, planId INTEGER NOT NULL, PRIMARY KEY(source,legacyId), FOREIGN KEY(planId) REFERENCES plans(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_plan_legacy_refs_planId ON plan_legacy_refs (planId)")
        // No business FK: a deleted plan's system identity must remain until cleanup succeeds.
        db.execSQL("CREATE TABLE plan_reminder_state (planId INTEGER NOT NULL, generation INTEGER NOT NULL, occurrenceDate INTEGER NOT NULL, triggerAt INTEGER NOT NULL, delivered INTEGER NOT NULL, PRIMARY KEY(planId))")
    }

    val INITIALIZE = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO app_settings VALUES (1,'SYSTEM','YMD','CNY',1,1,0)")
            db.execSQL("INSERT INTO app_maintenance VALUES (1,0)")
        }
    }
}
