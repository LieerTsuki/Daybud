package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode

/** Isolated raw backup contract. No local maintenance generation or reminder technical state. */
data class V2BackupSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dateFormat: DateFormat = DateFormat.YMD,
    val defaultCurrency: String = "CNY", val planReminders: Boolean = true, val subscriptionReminders: Boolean = true,
    val defaultReminderDays: Set<Int> = emptySet())
data class V2BackupPayload(val tables: Map<String, List<BackupRow>>, val settings: V2BackupSettings)
data class V2BackupDocument(val appVersion: String, val exportedAt: String, val payload: V2BackupPayload)
interface V2BackupCodec {
    fun encode(document: V2BackupDocument): ByteArray
    fun decode(bytes: ByteArray): V2BackupDocument
}
