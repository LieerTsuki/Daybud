package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

class UnifiedReminderReconciler(private val maintenance: MaintenanceCoordinator,
    private val plans: PlanRepository, private val sources: PlanReminderSources, private val store: PlanReminderStore,
    private val legacy: LegacyPlanReminderCleaner, private val scheduler: PlanReminderCalibration,
    private val notifications: PlanNotificationInventory, private val subscriptions: SubscriptionCalibration) : UnifiedReminderReconciliation {
    override suspend fun reconcile(resetSystem: Boolean) {
        val generation = maintenance.capture()
        var failure: Exception? = null
        suspend fun attempt(action: suspend () -> Unit) {
            currentCoroutineContext().ensureActive()
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        maintenance.run(generation) {
            PlanOperationCoordinator.run {
                // These authoritative snapshots and orphan decisions share the writer's SAME lock.
                sources.configuration()
                val current = plans.observeAll().first()
                val legacyIds = sources.legacyIds()
                val remembered = store.all()
                notifications.tokens() // fail before external changes if system inventory is unreadable
                val valid = current.map { it.id }.toSet()
                legacy.clear(legacyIds) // failure here prevents any new pass scheduling
                attempt { notifications.clearStale(generation, valid) }
                for (state in remembered) if (state.token.planId !in valid) attempt { scheduler.cancelCaptured(state.token) }
                for (plan in current) attempt { scheduler.calibrate(plan.id, generation, resetSystem) }
            }
            // No nested module locks. Keep original global admission through subscriptions.
            attempt { subscriptions.reconcile(generation, resetSystem) }
        }
        failure?.let { throw it }
    }
}
