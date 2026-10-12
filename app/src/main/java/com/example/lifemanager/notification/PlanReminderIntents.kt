package com.example.lifemanager.notification

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import androidx.core.net.toUri
import com.example.lifemanager.domain.plan.PlanReminderToken

object PlanReminderIntents {
    const val ACTION = "com.example.lifemanager.action.PLAN_REMINDER_V1"
    const val EXTRA_ID = "plan_id"
    const val EXTRA_DAY = "plan_occurrence_day"
    const val EXTRA_TRIGGER = "plan_trigger_millis"
    private const val RECEIVER = "com.example.lifemanager.notification.PlanReminderReceiver"
    fun alarm(context: Context, token: PlanReminderToken): Intent = metadata(Intent(ACTION)
        .setComponent(ComponentName(context.packageName, RECEIVER))
        .setPackage(context.packageName).setData(PlanReminderIdentity.data(token).toUri()), token)

    fun read(context: Context, intent: Intent): PlanReminderToken? {
        if (intent.action != ACTION || intent.component != ComponentName(context.packageName, RECEIVER) ||
            (intent.`package` != null && intent.`package` != context.packageName)) return null
        val token = PlanReminderIdentity.parse(intent.dataString) ?: return null
        return token.takeIf { matchesMetadata(intent.extras, it) }
    }

    internal fun metadata(intent: Intent, token: PlanReminderToken): Intent = intent
        .putExtra(EXTRA_ID, token.planId).putExtra(ReminderGeneration.EXTRA, token.generation.value)
        .putExtra(EXTRA_DAY, token.occurrenceDate.toEpochDay()).putExtra(EXTRA_TRIGGER, token.triggerAt.toEpochMilli())

    @Suppress("DEPRECATION")
    internal fun matchesMetadata(extras: android.os.Bundle?, token: PlanReminderToken): Boolean =
        extras?.get(EXTRA_ID) is Long && extras.get(EXTRA_ID) == token.planId &&
            extras.get(ReminderGeneration.EXTRA) is Long && extras.get(ReminderGeneration.EXTRA) == token.generation.value &&
            extras.get(EXTRA_DAY) is Long && extras.get(EXTRA_DAY) == token.occurrenceDate.toEpochDay() &&
            extras.get(EXTRA_TRIGGER) is Long && extras.get(EXTRA_TRIGGER) == token.triggerAt.toEpochMilli()
}
