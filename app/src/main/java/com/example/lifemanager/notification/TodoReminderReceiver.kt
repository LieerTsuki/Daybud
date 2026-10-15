package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Upgrade tombstone. A persisted legacy ID is never authorization for a unified plan. */
class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TITLE = "todo_title"
        const val EXTRA_DUE_AT = "todo_due_at"
    }
}
