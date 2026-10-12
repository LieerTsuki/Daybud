package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException

/** E provides Application dependencies and registers this receiver; fail closed until then. */
class PlanReminderReceiver(private val dependencies: (Context) -> Dependencies? = { it.applicationContext as? Dependencies }) : BroadcastReceiver() {
    interface Dependencies {
        val runner: ReminderBroadcastRunner
        val delivery: PlanReminderDelivery
        /** Enqueue ONLY; never re-enter held permits or replay the old payload. */
        fun requestReconciliation()
    }
    override fun onReceive(context: Context, intent: Intent) {
        val token = try { PlanReminderIntents.read(context, intent) } catch (_: Exception) { null } ?: return
        val pending = goAsync()
        val finish = { pending?.finish(); Unit }
        val services: Dependencies
        val runner: ReminderBroadcastRunner
        try {
            services = dependencies(context.applicationContext) ?: run { finish(); return }
            runner = services.runner
        } catch (error: CancellationException) { finish(); throw error }
        catch (_: Exception) { Log.w("PlanReminder", "Unable to obtain reminder services"); finish(); return }
        runner.launch(finish, services::requestReconciliation, token.generation) {
            try {
                if (services.delivery.deliverCurrent(token) == PlanDeliveryOutcome.NOT_SENT) services.requestReconciliation()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                try { services.requestReconciliation() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (retry: Exception) { error.addSuppressed(retry) }
                throw error
            }
        }
    }
}
