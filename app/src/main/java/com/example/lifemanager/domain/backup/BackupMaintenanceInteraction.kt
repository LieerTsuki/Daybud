package com.example.lifemanager.domain.backup

import kotlinx.coroutines.CancellationException

enum class BackupMaintenanceStage { PREPARING, CHOOSING_PROTECTION, WRITING_PROTECTION, CONFIRMING, COMMITTING, RECONCILING }

interface BackupMaintenanceInteraction {
    fun progress(stage: BackupMaintenanceStage)
    suspend fun chooseProtection(): BackupLocation?
    suspend fun confirm(protection: BackupLocation?): Boolean
}

data class BackupMaintenanceResult(val committed: Boolean, val remindersPending: Boolean)

/** Cancellation must not misrepresent a transaction which already committed. */
class BackupCommittedCancellationException(cause: CancellationException) : CancellationException("Data committed; follow-up interrupted") {
    init { initCause(cause) }
}
