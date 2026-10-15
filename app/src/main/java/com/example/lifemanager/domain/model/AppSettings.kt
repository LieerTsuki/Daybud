package com.example.lifemanager.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DateFormat { YMD, MDY, DMY }

/** Historical format-v1 settings contract; live preferences use V1AppSettings. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dateFormat: DateFormat = DateFormat.YMD,
    val defaultCurrency: String = "CNY",
    val todoReminders: Boolean = true,
    val scheduleReminders: Boolean = true,
    val subscriptionReminders: Boolean = true,
    val defaultReminderDays: Set<Int> = emptySet(),
)
