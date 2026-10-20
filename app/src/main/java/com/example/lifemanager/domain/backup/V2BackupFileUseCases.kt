package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Verified current output and bounded historical preview; UI owns consent and operation identity. */
class V2BackupFileUseCases(private val repository: V2BackupRepository, private val codec: VersionedBackupCodec,
    private val files: BackupFileStore, private val maintenance: MaintenanceCoordinator,
    private val workDispatcher: CoroutineDispatcher, private val limits: BackupLimits = BackupLimits(),
    private val archive: BackupArchive? = null) {
    suspend fun export(location: BackupLocation, appVersion: String, exportedAt: String,
        expected: DataGeneration? = null): V2BackupDocument = withContext(workDispatcher) {
        val token = expected ?: maintenance.capture()
        if (archive != null) {
            var captured: BackupCapture? = null
            try {
                maintenance.run(token) { archive.capture { repository.snapshot() }.also { captured=it } }
                return@withContext archive.writeVerified(location, V2BackupDocument(appVersion, exportedAt, checkNotNull(captured).payload))
            } finally { captured?.close() }
        }
        val payload = maintenance.run(token) { repository.snapshot() }
        // Release ordinary admission before any slow document-provider operation.
        writeVerified(location, V2BackupDocument(appVersion, exportedAt, payload))
    }

    suspend fun writeVerified(location: BackupLocation, document: V2BackupDocument): V2BackupDocument {
        currentCoroutineContext().ensureActive()
        val owned = ownV2Document(document, limits)
        return withContext(workDispatcher) {
            if (archive != null) return@withContext archive.writeVerified(location, owned)
            val bytes = codec.encode(owned)
            currentCoroutineContext().ensureActive()
            files.write(location, bytes)
            currentCoroutineContext().ensureActive()
            val reopened = codec.decode(files.read(location, limits.maxBytes), ZoneId.of("UTC"))
            if (reopened.sourceFormat != BackupSourceFormat.V3 || reopened.document != owned) {
                throw BackupFileException("输出不是本次导出的完整v3备份，不能视为成功备份")
            }
            currentCoroutineContext().ensureActive()
            owned
        }
    }

    suspend fun preview(location: BackupLocation, legacyTodoZone: ZoneId): BackupImportResult {
        var allocated: BackupImportResult?=null
        var handedOff=false
        try {
            val result=withContext(workDispatcher) {
                if (archive != null) archive.preview(location, legacyTodoZone).also { allocated=it }
                else files.withStagedInput(location, limits.maxBytes) { bytes ->
                    val decoded = codec.decode(bytes, legacyTodoZone)
                    currentCoroutineContext().ensureActive()
                    decoded.copy(document = ownV2Document(decoded.document, limits))
                }
            }
            handedOff=true
            return result
        } finally { if(!handedOff)allocated?.let { releasePreview(it.document) } }
    }
    /** Full envelope guard for directly supplied restore documents, with no provider effects. */
    internal suspend fun validateDocument(document: V2BackupDocument) {
        if (archive != null) archive.validateDocument(document) else codec.encode(document)
    }

    fun releasePreview(document: V2BackupDocument) { archive?.releasePreview(document) }
}
