package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UnifiedMaintenanceReminderEffects(private val context: Context, private val plans: PlanReminderCalibration,
    private val notifications: PlanNotificationInventory, private val legacy: LegacyPlanReminderCleaner,
    private val subscriptions: SubscriptionReminderSchedulerContract, private val reconciliation: UnifiedReminderReconciliation,
    private val request: () -> Unit) : MaintenanceReminderEffects {
    /** Caller owns maintenance session, possibly AFTER its commit advanced the generation. */
    override suspend fun clearPrevious(identities: ReminderIdentities) {
        var failure: Exception? = null
        suspend fun attempt(action: suspend () -> Unit) {
            currentCoroutineContext().ensureActive()
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        PlanOperationCoordinator.run {
            attempt { legacy.clear(LegacyPlanReminderIds(identities.todoIds.toSet(), identities.scheduleIds.toSet())) }
            for (token in identities.planAlarmTokens.distinct()) attempt { plans.cancelCaptured(token) }
            for (token in identities.planNotificationTokens.distinct()) attempt { notifications.cancelCaptured(token) }
        }
        SubscriptionOperationCoordinator.run {
            for (id in identities.subscriptionIds.distinct()) {
                // cancel() owns alarm/cache cleanup only; cancelAll() on an old injected
                // scheduler also deletes raw tags and would bypass our ownership predicate.
                attempt { subscriptions.cancel(id, SubscriptionReminderRules.supportedDays) }
                attempt { SubscriptionNotificationHelper.cancelOwnedForId(context, id) }
            }
        }
        failure?.let { throw it }
    }
    /** Only after maintenance/ordinary admission has been released; read current facts afresh. */
    override suspend fun reconcile() = reconciliation.reconcile(true)
    override fun requestReconciliation() = request()
}
