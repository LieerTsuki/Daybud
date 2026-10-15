package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderSources
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UnifiedSubscriptionCalibration(private val context: Context, private val repository: SubscriptionRepository,
    private val generations: DataGenerationRepository, private val sources: PlanReminderSources,
    private val scheduler: SubscriptionReminderSchedulerContract) : SubscriptionCalibration {
    override suspend fun reconcile(expected: DataGeneration, resetSystem: Boolean): Unit = SubscriptionOperationCoordinator.run {
        val reader = SubscriptionReminderStateReader {
            val actual = generations.current()
            if (actual != expected) throw StaleGenerationException()
            actual to sources.configuration().subscriptionReminders
        }
        val (_, enabled) = reader.read()
        val current = repository.getSubscriptions()
        val offsets = current.associate { it.id to repository.getReminderDays(it.id) }
        var failure: Exception? = null
        suspend fun attempt(action: suspend () -> Unit) {
            currentCoroutineContext().ensureActive()
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { SubscriptionNotificationHelper.clearOwned(context, expected, current.map { it.id }.toSet(), !enabled) }
        attempt { scheduler.cancelObsolete(if (enabled && !resetSystem) current.map { it.id }.toSet() else emptySet()) }
        for (item in current) attempt {
            if (!enabled) scheduler.cancelAll(item.id) else {
                if (resetSystem) scheduler.cancelAll(item.id)
                scheduler.scheduleCurrent(item, checkNotNull(offsets[item.id]))
            }
        }
        failure?.let { throw it }
        Unit
    }
}
