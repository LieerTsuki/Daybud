package com.example.lifemanager.notification

import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.net.toUri
import com.example.lifemanager.domain.plan.LegacyPlanReminderIds
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Isolated upgrade cleanup. Caller owns global admission and the plan lock; never schedule here. */
class LegacyPlanReminderCleaner(private val context: Context) {
    suspend fun clear(ids: LegacyPlanReminderIds) {
        val todos = ids.todoIds + cachedIds("todo")
        val schedules = ids.scheduleIds + cachedIds("schedule") + occurrenceIds()
        val scope = currentCoroutineContext()
        var failure: Exception? = null
        fun attempt(action: () -> Unit): Boolean {
            scope.ensureActive()
            return try { action(); true }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                false
            }
        }
        fun cancelForms(module: String, id: Long): Boolean {
            var complete = true
            for (data in listOf(false, true)) {
                if (!attempt {
                    val receiver = if (module == "todo") TodoReminderReceiver::class.java else ScheduleReminderReceiver::class.java
                    val code = if (module == "todo") ReminderKey.forTodo(id) else ReminderKey.forSchedule(id)
                    val intent = Intent(context, receiver)
                    if (data) intent.data = "lifemanager://$module-reminder/$id".toUri()
                    PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                        context.getSystemService(AlarmManager::class.java).cancel(it)
                        it.cancel()
                    }
                }) complete = false
            }
            return complete
        }
        TodoOperationCoordinator.run {
            for (id in todos.sorted()) if (cancelForms("todo", id)) attempt { ReminderAlarmInventory(context, "todo").forget(id) }
        }
        ScheduleOperationCoordinator.run {
            for (id in schedules.sorted()) if (cancelForms("schedule", id)) {
                attempt { ReminderAlarmInventory(context, "schedule").forget(id) }
                attempt { ScheduleAlarmOccurrenceStore(context).forget(id) }
            }
        }
        attempt {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.forEach { active ->
                if (active.tag != null || active.notification.channelId != NotificationHelper.CHANNEL_ID) return@forEach
                val extras = active.notification.extras
                @Suppress("DEPRECATION")
                val module = extras.get(ReminderNotifications.MODULE)
                if (!extras.containsKey(ReminderNotifications.MODULE) || module == "todo" || module == "schedule") {
                    attempt { manager.cancel(active.tag, active.id) }
                }
            }
        }
        failure?.let { throw it }
    }

    private fun cachedIds(module: String): Set<Long> = context.getSharedPreferences("reminder_alarm_ids", Context.MODE_PRIVATE).all
        .filterKeys { it.startsWith("$module/") }.map { (key, value) ->
            val raw = key.substringAfter('/')
            val id = checkNotNull(raw.toLongOrNull()) { "Invalid legacy alarm inventory" }
            check(raw == id.toString() && value is Boolean) { "Invalid legacy alarm inventory" }
            id
        }.toSet()

    private fun occurrenceIds(): Set<Long> = context.getSharedPreferences("schedule_alarm_occurrences", Context.MODE_PRIVATE).all
        .map { (key, value) ->
            val parts = key.split('/')
            check(parts.size == 2 && parts[1] in setOf("start", "generation") && value is Long) { "Invalid legacy occurrence inventory" }
            val id = checkNotNull(parts[0].toLongOrNull()) { "Invalid legacy occurrence inventory" }
            check(parts[0] == id.toString() && (parts[1] != "generation" || value >= 0L)) { "Invalid legacy occurrence inventory" }
            id
        }.toSet()
}
