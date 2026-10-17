package com.example.lifemanager.domain.backup

import java.time.ZoneId

enum class BackupSourceFormat { V1, V2, V3 }

/** Provenance is a preview description, not authenticated identity or exported business data. */
data class BackupImportResult(
    val document: V2BackupDocument,
    val sourceFormat: BackupSourceFormat,
    /** Non-null only when legacy TODO rows required the explicitly supplied restore-device zone. */
    val legacyTodoTimeZone: String? = null,
) {
    val rowCounts: Map<String, Int> get() = document.payload.tables.mapValues { it.value.size }
}

/** Exports format3, imports validated historical formats without file/database side effects. */
interface VersionedBackupCodec {
    fun encode(document: V2BackupDocument): ByteArray
    fun decode(bytes: ByteArray, legacyTodoZone: ZoneId): BackupImportResult
}
