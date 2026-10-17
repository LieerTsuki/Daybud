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
    private val workDispatcher: CoroutineDispatcher, private val limits: BackupLimits = BackupLimits()) {
    suspend fun export(location: BackupLocation, appVersion: String, exportedAt: String,
        expected: DataGeneration? = null): V2BackupDocument = withContext(workDispatcher) {
        val token = expected ?: maintenance.capture()
        val payload = maintenance.run(token) { repository.snapshot() }
        // Release ordinary admission before any slow document-provider operation.
        writeVerified(location, V2BackupDocument(appVersion, exportedAt, payload))
    }

    suspend fun writeVerified(location: BackupLocation, document: V2BackupDocument): V2BackupDocument {
        currentCoroutineContext().ensureActive()
        val owned = ownV2Document(document, limits)
        return withContext(workDispatcher) {
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

    suspend fun preview(location: BackupLocation, legacyTodoZone: ZoneId): BackupImportResult = withContext(workDispatcher) {
        files.withStagedInput(location, limits.maxBytes) { bytes ->
            val decoded = codec.decode(bytes, legacyTodoZone)
            currentCoroutineContext().ensureActive()
            decoded.copy(document = ownV2Document(decoded.document, limits))
        }
    }
    /** Full envelope guard for directly supplied restore documents, with no provider effects. */
    internal fun validateDocument(document: V2BackupDocument) { codec.encode(document) }
}

/** Bound/validate before allocating a private graph; own all mutable collections before suspension. */
internal fun ownV2Document(document: V2BackupDocument, limits: BackupLimits): V2BackupDocument {
    V3BackupValidator.validate(document, limits)
    return document.copy(payload = document.payload.copy(
        tables = document.payload.tables.mapValues { (_, rows) -> rows.map { BackupRow(it.fields.toMap()) } },
        settings = document.payload.settings.copy(defaultReminderDays = document.payload.settings.defaultReminderDays.toSet()),
    )).also { V3BackupValidator.validate(it, limits) }
}
