package com.example.lifemanager.notification

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PlanReminderScheduler(private val context: Context, private val plans: PlanRepository,
    private val generations: DataGenerationRepository, private val preferences: PlanReminderPreferences,
    private val store: PlanReminderStore, private val notifier: PlanReminderNotifier,
    private val clock: Clock) : PlanReminderSchedulerContract {
    override suspend fun reconcile(planId: Long, expected: DataGeneration) = replace(planId, expected, null)
    override suspend fun advance(token: PlanReminderToken) {
        check(store.get(token.planId) == PlanReminderState(token, true)) { "Reminder decision no longer matches" }
        replace(token.planId, token.generation, token)
    }

    /** Caller holds global admission THEN the shared module lock; never reacquire either. */
    private suspend fun replace(planId: Long, expected: DataGeneration, keep: PlanReminderToken?) {
        require(planId > 0)
        if (generations.current() != expected) throw StaleGenerationException()
        val enabled = preferences.enabled()
        val plan = plans.get(planId)
        val candidate = plan?.let {
            val exceptions = plans.getExceptions(planId)
            PlanReminderRules.next(it, clock.instant(), exceptions, enabled, notifier.permitted())
        }
        val old = store.get(planId)
        currentCoroutineContext().ensureActive()
        old?.let { cancel(it.token) }
        notifier.clear(planId, keep)
        if (candidate == null) { store.forget(planId); return }
        val token = PlanReminderToken.from(candidate, expected)
        // Persist BEFORE the external call: a failure leaves an identity that can be cancelled.
        store.remember(token)
        currentCoroutineContext().ensureActive()
        // Room/platform cleanup may have outlasted the selected trigger. Never install catch-up.
        check(token.triggerAt > clock.instant()) { "Reminder trigger elapsed; retry current facts" }
        val operation = PendingIntent.getBroadcast(context, PlanReminderIdentity.requestCode(token),
            PlanReminderIntents.alarm(context, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            context.getSystemService(AlarmManager::class.java).setReminder(token.triggerAt.toEpochMilli(), operation)
        } catch (error: Exception) {
            try { cancel(token) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    private fun cancel(token: PlanReminderToken) {
        val operation = PendingIntent.getBroadcast(context, PlanReminderIdentity.requestCode(token),
            PlanReminderIntents.alarm(context, token), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }
}
