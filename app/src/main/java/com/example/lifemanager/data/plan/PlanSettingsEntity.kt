package com.example.lifemanager.data.plan

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Target-only contract; production settings remain on v5 until the atomic activation. */
@Entity(tableName = "app_settings")
data class PlanSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val theme: String = "SYSTEM",
    val dateFormat: String = "YMD",
    val defaultCurrency: String = "CNY",
    val planReminders: Boolean = true,
    val subscriptionReminders: Boolean = true,
    val defaultReminderMask: Int = 0,
)
