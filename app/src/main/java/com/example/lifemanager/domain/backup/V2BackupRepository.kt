package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.DataGeneration

interface V2BackupRepository {
    suspend fun snapshot(): V2BackupPayload
}

/** Storage boundary only. Caller must own confirmed maintenance; no file or alarm effects here. */
interface V2BackupMutationRepository {
    suspend fun replace(expected: DataGeneration, payload: V2BackupPayload): DataGeneration
    suspend fun clearBusinessData(expected: DataGeneration): DataGeneration
}
