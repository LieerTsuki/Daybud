package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.*
import kotlinx.coroutines.*

/** Isolated target workflow. All database ports must refer to the same target singleton. */
class V2BackupMaintenanceWorkflow(private val snapshots: V2BackupRepository,
    private val mutations: V2BackupMutationRepository, private val files: V2BackupFileUseCases,
    private val generations: DataGenerationRepository, private val coordinator: MaintenanceCoordinator,
    private val capture: BackupReminderCapture, private val reminders: MaintenanceReminderEffects,
    private val dispatcher: CoroutineDispatcher, private val limits: BackupLimits = BackupLimits()) {
    suspend fun execute(request: V2BackupMaintenanceRequest, appVersion: String, exportedAt: String,
        interaction: BackupMaintenanceInteraction): BackupMaintenanceResult {
        currentCoroutineContext().ensureActive()
        val owned = when (request) {
            is V2BackupMaintenanceRequest.Restore -> request.copy(document = ownV2Document(request.document, limits))
            is V2BackupMaintenanceRequest.Clear -> request
        }
        // Evidence outlives both the maintenance-session and dispatcher return bridges.
        var committed = false
        var uncertain = false
        try {
            return withContext(dispatcher) {
                if (owned is V2BackupMaintenanceRequest.Restore) files.validateDocument(owned.document)
                var entered = false
                var pending = false
                try {
                    interaction.progress(BackupMaintenanceStage.PREPARING)
                    coordinator.withSession { session ->
                        entered = true
                        if (session.generation != owned.expected) throw StaleGenerationException()
                        val old = coordinator.withMaintenance(session) { capture.snapshot() }
                        val needsBackup = owned is V2BackupMaintenanceRequest.Restore ||
                            (owned as V2BackupMaintenanceRequest.Clear).createBackup
                        var protection: BackupLocation? = null
                        if (needsBackup) {
                            val snapshot = coordinator.withMaintenance(session) { snapshots.snapshot() }
                            interaction.progress(BackupMaintenanceStage.CHOOSING_PROTECTION)
                            protection = interaction.chooseProtection() ?: return@withSession
                            if (owned is V2BackupMaintenanceRequest.Restore && protection == owned.source) {
                                throw BackupFileException("保护备份必须另存文件，不能覆盖恢复来源")
                            }
                            interaction.progress(BackupMaintenanceStage.WRITING_PROTECTION)
                            files.writeVerified(protection, V2BackupDocument(appVersion, exportedAt, snapshot))
                        }
                        interaction.progress(BackupMaintenanceStage.CONFIRMING)
                        if (!interaction.confirm(protection)) return@withSession
                        currentCoroutineContext().ensureActive()
                        interaction.progress(BackupMaintenanceStage.COMMITTING)
                        coordinator.withMaintenance(session) {
                            val writer = currentCoroutineContext().job
                            try {
                                // Original writer still controls every SQL-body guard. Await actual
                                // Room endTransaction before proving outcome or releasing the freeze.
                                withContext(NonCancellable + V2BackupWriteCancellation(writer)) {
                                    when (owned) {
                                        is V2BackupMaintenanceRequest.Restore -> mutations.replace(session.generation, owned.document.payload)
                                        is V2BackupMaintenanceRequest.Clear -> mutations.clearBusinessData(session.generation)
                                    }
                                    committed = true
                                }
                            } catch (error: Exception) {
                                val advanced = try {
                                    withContext(NonCancellable) {
                                        val actual = generations.current()
                                        when {
                                            actual == session.generation -> false
                                            session.generation.value < Long.MAX_VALUE && actual.value == session.generation.value + 1 -> true
                                            else -> error("事务返回时本机世代既非原值也非原值加一，结果无法确定")
                                        }
                                    }
                                } catch (proofFailure: Exception) {
                                    uncertain = true
                                    error.addSuppressed(proofFailure)
                                    if (error is CancellationException) throw error
                                    throw BackupCommitOutcomeUnknownException(error)
                                }
                                if (!advanced) throw error
                                committed = true
                                pending = true
                                if (error is CancellationException) throw error
                            }
                            try { currentCoroutineContext().ensureActive(); reminders.clearPrevious(old) }
                            catch (error: Exception) {
                                if (error is CancellationException) throw error
                                pending = true
                            }
                        }
                    }
                } finally {
                    if (entered) withContext(NonCancellable) {
                        // Picker cancel, backup error, rollback, commit, destruction: request fresh facts.
                        try { reminders.requestReconciliation() }
                        catch (_: Exception) { pending = true }
                    }
                }
                if (entered) {
                    interaction.progress(BackupMaintenanceStage.RECONCILING)
                    try { withTimeout(8_000) { reminders.reconcile() } }
                    catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        pending = true
                    }
                }
                BackupMaintenanceResult(committed, pending)
            }
        } catch (error: CancellationException) {
            if (committed) throw BackupCommittedCancellationException(error)
            if (uncertain) throw BackupCommitOutcomeUnknownCancellationException(error)
            throw error
        }
    }
}
