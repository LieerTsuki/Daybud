package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.CancellationException

sealed interface V2BackupMaintenanceRequest {
    val expected: DataGeneration
    data class Restore(override val expected: DataGeneration, val source: BackupLocation,
        val document: V2BackupDocument) : V2BackupMaintenanceRequest
    data class Clear(override val expected: DataGeneration, val createBackup: Boolean) : V2BackupMaintenanceRequest
}

/** A failed generation read is not evidence of rollback. Never automatically repeat data writes. */
class BackupCommitOutcomeUnknownException(cause: Throwable) : IllegalStateException("无法确认数据是否已提交，请重新读取数据，勿重复恢复", cause)
class BackupCommitOutcomeUnknownCancellationException(cause: CancellationException) : CancellationException("取消时无法确认提交结果，请重新读取数据，勿重复恢复") {
    init { initCause(cause) }
}
