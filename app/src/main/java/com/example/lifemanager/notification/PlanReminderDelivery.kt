package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** DELIVERED means a persistently decided submission, not platform/user acknowledgement. */
enum class PlanDeliveryOutcome { IGNORED, DELIVERED, NOT_SENT }
/** Caller owns original-generation global admission (Receiver runner), not the module lock yet. */
class PlanReminderDelivery(private val plans: PlanRepository, private val generations: DataGenerationRepository,
    private val preferences: PlanReminderPreferences, private val store: PlanReminderStore,
    private val notifier: PlanReminderNotifier, private val scheduler: PlanReminderSchedulerContract,
    private val clock: Clock) {
    suspend fun deliverCurrent(token: PlanReminderToken): PlanDeliveryOutcome = PlanOperationCoordinator.run {
        if (generations.current() != token.generation) throw StaleGenerationException()
        if (store.get(token.planId) != PlanReminderState(token, false)) return@run PlanDeliveryOutcome.IGNORED
        if (clock.instant() < token.triggerAt) return@run PlanDeliveryOutcome.IGNORED
        val plan = plans.get(token.planId)
        val exceptions = plans.getExceptions(token.planId)
        fun qualified() = plan != null && PlanReminderEligibility.matches(plan, exceptions, token, clock.instant())
        if (!qualified() || !preferences.enabled() || !notifier.permitted()) {
            scheduler.reconcile(token.planId, token.generation)
            return@run PlanDeliveryOutcome.IGNORED
        }
        if (generations.current() != token.generation) throw StaleGenerationException()
        if (!preferences.enabled() || !notifier.permitted() || !qualified()) {
            scheduler.reconcile(token.planId, token.generation)
            return@run PlanDeliveryOutcome.IGNORED
        }
        currentCoroutineContext().ensureActive()
        if (!notifier.show(token, checkNotNull(plan).title)) return@run PlanDeliveryOutcome.NOT_SENT
        check(store.markDelivered(token)) { "Reminder delivery decision did not match pending identity" }
        scheduler.advance(token)
        PlanDeliveryOutcome.DELIVERED
    }
}
