package com.example.lifemanager.notification

import android.content.Context

/** Upgrade cleanup only. Never schedules or authorizes a legacy occurrence. */
internal class ScheduleAlarmOccurrenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("schedule_alarm_occurrences", Context.MODE_PRIVATE)
    fun forget(id: Long) {
        check(preferences.edit().remove("$id/start").remove("$id/generation").commit())
    }
}
