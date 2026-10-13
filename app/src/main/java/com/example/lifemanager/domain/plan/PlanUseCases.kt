package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

data class PlanWriteReceipt(val planId: Long, val generation: DataGeneration) {
    init { require(planId > 0) { "A committed receipt requires a persisted identity" } }
}
sealed interface PlanReminderUpdate {
    data object Applied : PlanReminderUpdate
    data class Failed(val cause: Exception) : PlanReminderUpdate
}
data class PlanWriteResult(val receipt: PlanWriteReceipt, val reminders: PlanReminderUpdate)
class PlanCommitHandoffException(val receipt: PlanWriteReceipt, cause: Exception) : IllegalStateException(cause)
class PlanCommittedCancellationException(val receipt: PlanWriteReceipt, cause: CancellationException) : CancellationException() {
    init { initCause(cause) }
}

/** Owns admission: callers must not wrap these methods in another maintenance/module permit.
 * onCommitted is a mandatory, short state handoff (not I/O or user interaction). It consumes
 * an unsaved draft before reminder work can fail/cancel. E still owns actual UI wiring.
 */
class PlanUseCases(private val repository: PlanRepository, private val maintenance: MaintenanceCoordinator, private val reminders: PlanReminderEffects) {
    suspend fun saveChecked(plan:Plan,tagNames:List<String>,expected:DataGeneration,policy:PlanEditPolicy,
        onCommitted:suspend (PlanWriteReceipt)->Unit):PlanWriteResult {
        // Own caller collections before suspension; confirmations cannot change behind a dialog.
        val names=tagNames.toList()
        val draft=plan.ownedEditSnapshot().copy(tagNames=names)
        val owned=policy.copy(original=policy.original?.ownedEditSnapshot(),confirmed=policy.confirmed?.ownedEditSnapshot())
        return write(expected,onCommitted) {
            val checked=cancellablePlanWriteBody { verifyPlanEdit(repository,draft,owned) }
            repository.save(checked,names,expected)
        }
    }

    suspend fun save(plan: Plan, tagNames: List<String>, expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit): PlanWriteResult =
        write(expected, onCommitted) { repository.save(plan, tagNames, expected) }

    suspend fun setCompleted(id: Long, completed: Boolean, expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit): PlanWriteResult =
        write(expected, onCommitted) { repository.setCompleted(id, completed, expected); id }

    suspend fun delete(id: Long, expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit): PlanWriteResult =
        write(expected, onCommitted) { repository.delete(id, expected); id }

    /** Destructive UI confirmations own the shown snapshot, not just a reusable identity. */
    suspend fun deleteChecked(confirmed: Plan, expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit): PlanWriteResult {
        val snapshot=confirmed.ownedEditSnapshot()
        require(snapshot.id>0)
        return write(expected,onCommitted) {
            cancellablePlanWriteBody {
                if(repository.get(snapshot.id)!=snapshot)throw PlanEditChangedException()
            }
            repository.delete(snapshot.id,expected)
            snapshot.id
        }
    }

    suspend fun saveException(value: PlanException, expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit): PlanWriteResult =
        write(expected, onCommitted) { repository.saveException(value, expected); value.planId }

    /** Retry only the side effect, never the mutation. Missing current rows must be cancelled. */
    suspend fun retryReminders(receipt: PlanWriteReceipt): PlanWriteResult = try {
        maintenance.run(receipt.generation) {
            PlanOperationCoordinator.run { reconcile(receipt) }
        }
    } catch (error: CancellationException) {
        throw committedCancellation(receipt, error)
    }

    private suspend fun write(expected: DataGeneration, onCommitted: suspend (PlanWriteReceipt) -> Unit, mutation: suspend () -> Long): PlanWriteResult {
        // Keep the receipt outside every cancellable scope: maintenance's withContext can
        // replace an inner cancellation exception with the already-recorded Job cancel cause.
        var committed: PlanWriteReceipt? = null
        return try {
            maintenance.run(expected) {
                PlanOperationCoordinator.run {
                    currentCoroutineContext().ensureActive()
                    val writer = currentCoroutineContext().job
                    try {
                        // Room runs its body under the original cancellable writer. Only commit/return
                        // and the short state handoff survive cancellation after a successful body.
                        withContext(NonCancellable + PlanWriteCancellation(writer)) {
                            val receipt = PlanWriteReceipt(mutation(), expected).also { committed = it }
                            onCommitted(receipt)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val receipt = committed ?: throw error
                        throw PlanCommitHandoffException(receipt, error)
                    }
                    reconcile(checkNotNull(committed))
                }
            }
        } catch (error: CancellationException) {
            val receipt = committed ?: throw error
            throw committedCancellation(receipt, error)
        }
    }

    private fun committedCancellation(receipt: PlanWriteReceipt, error: CancellationException): PlanCommittedCancellationException =
        if (error is PlanCommittedCancellationException && error.receipt == receipt) error
        else PlanCommittedCancellationException(receipt, error)

    private suspend fun reconcile(receipt: PlanWriteReceipt): PlanWriteResult {
        val update = try {
            currentCoroutineContext().ensureActive()
            reminders.reconcile(receipt.planId, receipt.generation)
            PlanReminderUpdate.Applied
        } catch (error: CancellationException) {
            throw PlanCommittedCancellationException(receipt, error)
        } catch (error: Exception) {
            PlanReminderUpdate.Failed(error)
        }
        return PlanWriteResult(receipt, update)
    }
}
