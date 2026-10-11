package com.example.lifemanager.data.plan

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.lifemanager.data.local.Converters
import com.example.lifemanager.data.local.entity.*

/** Complete isolated target schema, validated by the generated Room delegate on Android. */
@Database(entities = [PlanEntity::class, PlanTagCrossRef::class, PlanExceptionEntity::class,
    PlanLegacyRefEntity::class, PlanSettingsEntity::class, MaintenanceEntity::class, TagEntity::class,
    HabitEntity::class, HabitRecordEntity::class, SubscriptionEntity::class,
    SubscriptionPaymentEntity::class, SubscriptionReminderEntity::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class, PlanConverters::class)
abstract class PlanMigrationDeviceDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
}
