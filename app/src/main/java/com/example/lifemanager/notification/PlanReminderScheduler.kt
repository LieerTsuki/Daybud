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
    private val clock: Clock) : PlanReminderCalibration {
    /** Ordinary passes do not invalidate an already-triggered inexact alarm still awaiting delivery. */
    override suspend fun calibrate(planId: Long, expected: DataGeneration, resetSystem: Boolean) {
        require(planId > 0)
        if (generations.current() != expected) throw StaleGenerationException()
        val plan = plans.get(planId)
        val exceptions = if (plan == null) emptyList() else plans.getExceptions(planId)
        val enabled = preferences.enabled() && notifier.permitted()
        val state = store.get(planId)
        val inventory = checkNotNull(notifier as? PlanNotificationInventory) { "Calibration requires owned notification inventory" }
        fun corresponds(token: PlanReminderToken): Boolean {
            if (plan == null || token.generation != expected || !enabled) return false
            val candidate = PlanReminderRules.next(plan, token.triggerAt.minusNanos(1), exceptions) ?: return false
            return PlanReminderToken.from(candidate, expected) == token
        }
        val barrier = state?.takeIf { corresponds(it.token) }
        val keep = inventory.tokens().filter { it.planId == planId &&
            (it.triggerAt <= clock.instant() || barrier?.let { b -> b.delivered && b.token == it } == true) && corresponds(it) }
            .maxWithOrNull(compareBy<PlanReminderToken> { it.triggerAt }.thenBy { it.occurrenceDate })
        val now = clock.instant() // sample after suspended Room reads and system inventory lookup
        if (state != null && state.awaitingAlarm && corresponds(state.token) &&
            PlanReminderEligibility.matches(checkNotNull(plan), exceptions, state.token, maxOf(now, state.token.triggerAt)) &&
            (!resetSystem || state.token.triggerAt > now)) {
            currentCoroutineContext().ensureActive()
            notifier.clear(planId, keep)
            if (state.token.triggerAt > clock.instant()) install(state.token)
            else if (resetSystem) replace(planId, expected, keep, barrier)
            return
        }
        replace(planId, expected, keep, barrier)
    }
    /** Maintenance may have already cleared Room rows; only the captured exact identity is cancelled. */
    override suspend fun cancelCaptured(token: PlanReminderToken) {
        val current = store.get(token.planId)
        currentCoroutineContext().ensureActive()
        if (current?.token == token && current.awaitingAlarm) store.remember(token)
        cancel(token)
        if (current?.token == token) store.forget(token.planId)
    }
    override suspend fun reconcile(planId: Long, expected: DataGeneration) = replace(planId, expected, null)
    override suspend fun advance(token: PlanReminderToken) {
        check(store.get(token.planId) == PlanReminderState(token, true)) { "Reminder decision no longer matches" }
        replace(token.planId, token.generation, token, PlanReminderState(token, true))
    }

    /** Caller holds global admission THEN the shared module lock; never reacquire either. */
    private suspend fun replace(planId: Long, expected: DataGeneration, keep: PlanReminderToken?, barrier: PlanReminderState? = null) {
        require(planId > 0)
        if (generations.current() != expected) throw StaleGenerationException()
        val enabled = preferences.enabled()
        val plan = plans.get(planId)
        val candidate = plan?.let {
            val exceptions = plans.getExceptions(planId)
            val boundary = barrier?.let { b -> if (b.delivered) b.token.triggerAt else b.token.triggerAt.minusNanos(1) }
            PlanReminderRules.next(it, maxOf(clock.instant(), boundary ?: java.time.Instant.MIN), exceptions, enabled, notifier.permitted())
        }
        val old = store.get(planId)
        currentCoroutineContext().ensureActive()
        // Invalidate an accepted alarm before cancellation; ordinary repair must not preserve it
        // if a later platform/persistence operation fails. Keep the same chronological hint.
        old?.let { if (it.awaitingAlarm) store.remember(it.token); cancel(it.token) }
        notifier.clear(planId, keep)
        if (candidate == null) {
            if (barrier == null) store.forget(planId)
            return
        }
        val token = PlanReminderToken.from(candidate, expected)
        currentCoroutineContext().ensureActive()
        install(token)
    }
    private suspend fun install(token: PlanReminderToken) {
        // Every install, including reinstalls, invalidates 2 BEFORE touching the system.
        store.remember(token)
        // Room/platform cleanup may have outlasted the selected trigger. Never install catch-up.
        check(token.triggerAt > clock.instant()) { "Reminder trigger elapsed; retry current facts" }
        val operation = PendingIntent.getBroadcast(context, PlanReminderIdentity.requestCode(token),
            PlanReminderIntents.alarm(context, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            context.getSystemService(AlarmManager::class.java).setReminder(token.triggerAt.toEpochMilli(), operation)
            check(store.markAwaiting(token)) { "Reminder installation did not match unconfirmed identity" }
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
