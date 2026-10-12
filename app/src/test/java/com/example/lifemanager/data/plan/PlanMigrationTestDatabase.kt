package com.example.lifemanager.data.plan

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.lifemanager.data.local.Converters
import com.example.lifemanager.data.local.entity.*

/** Complete target schema fixture; never registered in the production database factory. */
@Database(entities = [PlanEntity::class, PlanTagCrossRef::class, PlanExceptionEntity::class,
    PlanLegacyRefEntity::class, PlanSettingsEntity::class, MaintenanceEntity::class, TagEntity::class,
    HabitEntity::class, HabitRecordEntity::class, SubscriptionEntity::class,
    SubscriptionPaymentEntity::class, SubscriptionReminderEntity::class,
    PlanReminderStateEntity::class], version = 6, exportSchema = true)
@TypeConverters(Converters::class, PlanConverters::class)
abstract class PlanMigrationTestDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun reminders(): PlanReminderStateDao
}
