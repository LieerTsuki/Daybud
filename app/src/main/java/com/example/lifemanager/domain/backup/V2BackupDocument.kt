package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode

/** Isolated raw backup contract. No local maintenance generation or reminder technical state. */
data class V2BackupSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dateFormat: DateFormat = DateFormat.YMD,
    val defaultCurrency: String = "CNY", val planReminders: Boolean = true, val subscriptionReminders: Boolean = true,
    val defaultReminderDays: Set<Int> = emptySet())
/** Shared raw carrier; external formats are selected only by their explicit schema/codec. */
data class V2BackupPayload(val tables: Map<String, List<BackupRow>>, val settings: V2BackupSettings,
    val diary: com.example.lifemanager.domain.diary.DiaryBackupSnapshot? = null)
data class V2BackupDocument(val appVersion: String, val exportedAt: String, val payload: V2BackupPayload,
    /** Local preview lease, never serialized to a backup. */
    val previewToken: String? = null)
interface V2BackupCodec {
    fun encode(document: V2BackupDocument): ByteArray
    fun decode(bytes: ByteArray): V2BackupDocument
}
