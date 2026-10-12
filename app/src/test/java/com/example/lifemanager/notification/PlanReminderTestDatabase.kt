package com.example.lifemanager.notification

import android.database.Cursor
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.lifemanager.data.local.Converters
import com.example.lifemanager.data.local.dao.SubscriptionDao
import com.example.lifemanager.data.local.entity.*
import com.example.lifemanager.data.plan.*

/** New-install isolated target with technical inventory; NOT a migration-chain acceptance. */
@Database(entities = [PlanEntity::class, PlanTagCrossRef::class, PlanExceptionEntity::class,
    PlanLegacyRefEntity::class, PlanSettingsEntity::class, MaintenanceEntity::class, TagEntity::class,
    HabitEntity::class, HabitRecordEntity::class, SubscriptionEntity::class,
    SubscriptionPaymentEntity::class, SubscriptionReminderEntity::class, PlanReminderStateEntity::class],
    version = 6, exportSchema = false)
@TypeConverters(Converters::class, PlanConverters::class)
abstract class PlanReminderTestDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun reminders(): PlanReminderStateDao
    abstract fun metadata(): PlanReminderFixtureDao
    abstract fun subscriptions(): SubscriptionDao
}

@Dao interface PlanReminderFixtureDao { @RawQuery fun read(query: SupportSQLiteQuery): Cursor }
