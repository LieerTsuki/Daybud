package com.example.lifemanager.domain.backup

import java.io.Closeable

/** A snapshot and its immutable media remain one owned export, even after CRUD resumes. */
interface BackupCapture : Closeable { val payload: V2BackupPayload }

/** Format4 streaming boundary. Local leases are not part of the portable manifest. */
interface BackupArchive {
    suspend fun capture(snapshot: suspend () -> V2BackupPayload): BackupCapture
    suspend fun writeVerified(location: BackupLocation, document: V2BackupDocument): V2BackupDocument
    suspend fun preview(location: BackupLocation, legacyTodoZone: java.time.ZoneId): BackupImportResult
    suspend fun validateDocument(document: V2BackupDocument)
    fun releasePreview(document: V2BackupDocument)
}
