package com.example.lifemanager.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.plan.PlanReminderToken

interface PlanReminderNotifier {
    fun permitted(): Boolean
    /** Submitted without exception or observable blocking, NOT confirmation of user delivery. */
    fun show(token: PlanReminderToken, title: String): Boolean
    fun clear(planId: Long, keep: PlanReminderToken? = null)
}
class PlanNotificationHelper(private val context: Context) : PlanReminderNotifier {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    override fun permitted(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun show(token: PlanReminderToken, title: String): Boolean {
        if (!permitted()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "计划提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PlanReminderIntents.metadata(Intent(context, MainActivity::class.java)
            .setAction(OPEN_ACTION).setData(PlanReminderIdentity.data(token).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), token)
        val click = PendingIntent.getActivity(context, PlanReminderIdentity.requestCode(token), intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("计划提醒")
            .setContentText(title).setContentIntent(click).setAutoCancel(true)
            .addExtras(intent.extras).build()
        if (!permitted()) return false
        return try {
            manager.notify(PlanReminderIdentity.data(token), 0, notification)
            if (permitted()) true else {
                manager.cancel(PlanReminderIdentity.data(token), 0)
                false
            }
        } catch (_: SecurityException) { false }
    }
    override fun clear(planId: Long, keep: PlanReminderToken?) {
        require(planId > 0)
        // Inventory may already describe tomorrow; today's card lives in the system inventory.
        manager.activeNotifications.forEach { active ->
            if (active.notification.channelId != CHANNEL) return@forEach
            val token = PlanReminderIdentity.parse(active.tag) ?: return@forEach
            if (token.planId == planId && token != keep &&
                PlanReminderIntents.matchesMetadata(active.notification.extras, token)) {
                manager.cancel(active.tag, active.id)
            }
        }
    }
    companion object {
        const val CHANNEL = "plan_reminders"
        const val OPEN_ACTION = "com.example.lifemanager.action.OPEN_PLAN_V1"
    }
}
