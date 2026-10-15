package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Upgrade tombstone. Never query removed tables or reinterpret a legacy schedule ID. */
class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TITLE = "schedule_title"
        const val EXTRA_OCCURRENCE_START = "schedule_occurrence_start"
    }
}
