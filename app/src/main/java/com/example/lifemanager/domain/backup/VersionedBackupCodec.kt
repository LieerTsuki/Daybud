package com.example.lifemanager.domain.backup

import java.time.ZoneId

enum class BackupSourceFormat { V1, V2, V3, V4 }

/** Provenance is a preview description, not authenticated identity or exported business data. */
data class BackupImportResult(
    val document: V2BackupDocument,
    val sourceFormat: BackupSourceFormat,
    /** Non-null only when legacy TODO rows required the explicitly supplied restore-device zone. */
    val legacyTodoTimeZone: String? = null,
) {
    val rowCounts: Map<String, Int> get() = document.payload.tables.mapValues { it.value.size } +
        (document.payload.diary?.let { mapOf("diary_entries" to it.entries.size,
            "diary_drafts" to it.drafts.size, "diary_media" to it.media.size,
            "diary_reviews" to it.reviews.size) } ?: emptyMap())
}

/** Exports format3, imports validated historical formats without file/database side effects. */
interface VersionedBackupCodec {
    fun encode(document: V2BackupDocument): ByteArray
    fun decode(bytes: ByteArray, legacyTodoZone: ZoneId): BackupImportResult
}
