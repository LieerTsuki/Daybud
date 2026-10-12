package com.example.lifemanager.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.example.lifemanager.domain.maintenance.DataGeneration
import androidx.core.content.ContextCompat
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.model.Subscription
import java.time.LocalDate
import android.service.notification.StatusBarNotification

object SubscriptionNotificationHelper {
    const val CHANNEL_ID = "subscription_reminders"

    fun showReminder(context: Context, subscription: Subscription, daysBefore: Int, dueDate: LocalDate, generation: DataGeneration) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "订阅扣费提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val openIntent = PendingIntent.getActivity(
            context,
            ReminderKey.forSubscription(subscription.id, daysBefore),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse(ReminderKey.subscriptionData(subscription.id, daysBefore, dueDate)))
                .putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
                .putExtra(ReminderGeneration.EXTRA, generation.value),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("订阅扣费提醒")
            .setContentText("${subscription.appName} 将于 $dueDate 扣费（提前 $daysBefore 天）")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addExtras(Bundle().apply {
                putLong(ReminderGeneration.EXTRA, generation.value)
                putString(ReminderNotifications.MODULE, "subscription")
            })
            .build()
        // Notification tags, like alarm data, preserve the full ID and isolate other modules.
        try {
            manager.notify(notificationTag(subscription.id, daysBefore), 0, notification)
        } catch (_: SecurityException) {
            // Revocation between the permission check and notify is a normal no-delivery outcome.
        }
    }

    fun cancelAll(context: Context, subscriptionId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        SubscriptionReminderRules.supportedDays.forEach { days ->
            manager.cancel(notificationTag(subscriptionId, days), 0)
        }
    }

    private fun notificationTag(subscriptionId: Long, daysBefore: Int) = "subscription/$subscriptionId/$daysBefore"

    /** Target-only cleanup: never sweep the combined legacy channel or foreign cards. */
    internal fun clearOwned(context: Context, current: DataGeneration, validIds: Set<Long>, disabled: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { card ->
            val identity = owned(card) ?: return@filter false
            disabled || identity.second != current.value || identity.first !in validIds
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    internal fun cancelOwnedForId(context: Context, subscriptionId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { owned(it)?.first == subscriptionId }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    @Suppress("DEPRECATION")
    private fun owned(card: StatusBarNotification): Pair<Long, Long>? {
        if (card.id != 0 || card.notification.channelId != CHANNEL_ID) return null
        val pieces = card.tag?.split('/') ?: return null
        if (pieces.size != 3 || pieces[0] != "subscription") return null
        val id = pieces[1].toLongOrNull() ?: return null
        val days = pieces[2].toIntOrNull() ?: return null
        if (id <= 0 || days !in SubscriptionReminderRules.supportedDays || card.tag != notificationTag(id, days)) return null
        val extras = card.notification.extras
        if (extras.get(ReminderNotifications.MODULE) != "subscription") return null
        val generation = extras.get(ReminderGeneration.EXTRA) as? Long ?: return null
        if (generation < 0) return null
        return id to generation
    }
}
