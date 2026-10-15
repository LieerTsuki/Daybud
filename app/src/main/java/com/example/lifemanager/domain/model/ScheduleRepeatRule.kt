package com.example.lifemanager.domain.model

/** Stable persisted enum shared by unified plans and historical backup/migration decoding. */
enum class ScheduleRepeatRule {
    NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM,
}
