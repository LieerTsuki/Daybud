package com.example.lifemanager.notification

import android.content.Context

/** Operational cancellation hints, not business data. Missing cache never authorizes delivery. */
internal class ReminderAlarmInventory(context: Context, private val module: String) {
    private val preferences = context.getSharedPreferences("reminder_alarm_ids", Context.MODE_PRIVATE)
    fun forget(id: Long) {
        check(preferences.edit().remove("$module/$id").commit()) { "Unable to remove alarm identity" }
    }
}
