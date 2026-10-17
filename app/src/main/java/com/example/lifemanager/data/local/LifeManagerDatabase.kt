package com.example.lifemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.example.lifemanager.data.local.dao.HabitDao
import com.example.lifemanager.data.local.dao.TagDao
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.dao.SubscriptionDao
import com.example.lifemanager.data.local.entity.SubscriptionEntity
import com.example.lifemanager.data.local.entity.SubscriptionPaymentEntity
import com.example.lifemanager.data.local.entity.SubscriptionReminderEntity
import com.example.lifemanager.data.local.entity.MaintenanceEntity
import com.example.lifemanager.data.local.dao.MaintenanceDao
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.data.plan.PlanEntity
import com.example.lifemanager.data.plan.PlanTagCrossRef
import com.example.lifemanager.data.plan.PlanExceptionEntity
import com.example.lifemanager.data.plan.PlanLegacyRefEntity
import com.example.lifemanager.data.plan.PlanSettingsEntity
import com.example.lifemanager.data.plan.PlanReminderStateEntity
import com.example.lifemanager.data.local.migration.PlanMigration
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.data.plan.PlanConverters
import com.example.lifemanager.data.plan.PlanDao
import com.example.lifemanager.data.plan.PlanReminderStateDao
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lifemanager.data.local.entity.HabitTreeEntity
import com.example.lifemanager.data.local.entity.HabitWateringEntity
import com.example.lifemanager.data.local.dao.HabitForestDao
import com.example.lifemanager.data.local.migration.HabitForestMigration

@Database(
    entities = [
        PlanEntity::class,
        TagEntity::class,
        PlanTagCrossRef::class,
        PlanExceptionEntity::class,
        PlanLegacyRefEntity::class,
        HabitEntity::class,
        HabitRecordEntity::class,
        HabitTreeEntity::class,
        HabitWateringEntity::class,
        SubscriptionEntity::class,
        SubscriptionPaymentEntity::class,
        SubscriptionReminderEntity::class,
        PlanSettingsEntity::class,
        MaintenanceEntity::class,
        PlanReminderStateEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class, PlanConverters::class)
abstract class LifeManagerDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun planReminderStateDao(): PlanReminderStateDao
    abstract fun tagDao(): TagDao
    abstract fun habitDao(): HabitDao
    abstract fun habitForestDao(): HabitForestDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun backupSnapshotDao(): BackupSnapshotDao

    companion object {
        val INITIALIZE = PlanV6Schema.INITIALIZE

        // Historical 4→5 must retain the v5 columns before PlanMigration reads them.
        private fun initializeLegacyDefaults(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO app_settings (id, theme, dateFormat, defaultCurrency, todoReminders, scheduleReminders, subscriptionReminders, defaultReminderMask) VALUES (1, 'SYSTEM', 'YMD', 'CNY', 1, 1, 1, 0)")
            db.execSQL("INSERT OR IGNORE INTO app_maintenance (id, generation) VALUES (1, 0)")
        }

        val LEGACY_MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS schedules (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            startAt INTEGER,
                            endAt INTEGER,
                            isAllDay INTEGER NOT NULL,
                            allDayStartDate INTEGER,
                            allDayEndDate INTEGER,
                            location TEXT,
                            participants TEXT,
                            note TEXT,
                            color INTEGER NOT NULL,
                            reminderMinutes INTEGER,
                            repeatRule TEXT NOT NULL,
                            repeatInterval INTEGER NOT NULL,
                            repeatDaysOfWeek TEXT,
                            repeatEndDate INTEGER,
                            timeZone TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_startAt ON schedules (startAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_allDayStartDate ON schedules (allDayStartDate)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS schedule_exceptions (
                            scheduleId INTEGER NOT NULL,
                            occurrenceDate INTEGER NOT NULL,
                            isCancelled INTEGER NOT NULL,
                            PRIMARY KEY (scheduleId, occurrenceDate),
                            FOREIGN KEY (scheduleId) REFERENCES schedules(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_exceptions_scheduleId ON schedule_exceptions (scheduleId)")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS habits (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            iconKey TEXT NOT NULL,
                            color INTEGER NOT NULL,
                            frequencyType TEXT NOT NULL,
                            frequencyValue INTEGER NOT NULL,
                            customDaysOfWeek TEXT,
                            startDate INTEGER NOT NULL,
                            note TEXT,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS habit_records (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            habitId INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_records_habitId ON habit_records (habitId)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_records_habitId_date ON habit_records (habitId, date)")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, appName TEXT NOT NULL, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, billingCycle TEXT NOT NULL, nextBillingDate INTEGER NOT NULL, startDate INTEGER NOT NULL, category TEXT, note TEXT, isActive INTEGER NOT NULL, cancelDate INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_subscriptions_nextBillingDate ON subscriptions (nextBillingDate)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS subscription_payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subscriptionId INTEGER NOT NULL, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, paidAt INTEGER NOT NULL, note TEXT, FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_payments_subscriptionId ON subscription_payments (subscriptionId)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS subscription_reminders (subscriptionId INTEGER NOT NULL, daysBefore INTEGER NOT NULL, PRIMARY KEY(subscriptionId, daysBefore), FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_reminders_subscriptionId ON subscription_reminders (subscriptionId)")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (id INTEGER NOT NULL, theme TEXT NOT NULL, dateFormat TEXT NOT NULL, defaultCurrency TEXT NOT NULL, todoReminders INTEGER NOT NULL, scheduleReminders INTEGER NOT NULL, subscriptionReminders INTEGER NOT NULL, defaultReminderMask INTEGER NOT NULL, PRIMARY KEY(id))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_maintenance (id INTEGER NOT NULL, generation INTEGER NOT NULL, PRIMARY KEY(id))")
                    initializeLegacyDefaults(db)
                }
            },
        )

        val MIGRATIONS: Array<Migration> = LEGACY_MIGRATIONS + arrayOf(PlanMigration(), HabitForestMigration())
    }
}
