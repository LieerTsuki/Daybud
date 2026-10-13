package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/** Isolated compatibility entry point; does not replace the production v1 Hilt binding. */
class VersionedBackupJsonCodec(private val limits: BackupLimits = BackupLimits()) : VersionedBackupCodec {
    override fun encode(document: V2BackupDocument): ByteArray = V2BackupJsonCodec(limits).encode(document)

    override fun decode(bytes: ByteArray, legacyTodoZone: ZoneId): BackupImportResult = when (version(bytes)) {
        1L -> LegacyBackupConverter(limits).convert(BackupJsonCodec(limits).decode(bytes), legacyTodoZone)
        2L -> BackupImportResult(V2BackupJsonCodec(limits).decode(bytes), BackupSourceFormat.V2)
        else -> throw BackupValidationException("不支持的备份格式版本")
    }

    private fun version(bytes: ByteArray): Long {
        checkBackup(bytes.size <= limits.maxBytes, "备份超过 ${limits.maxBytes} 字节限制")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) { throw BackupValidationException("备份不是有效 UTF-8 文件", error) }
        // Fixed largest supported contract budget, not a regex, unbounded parser or input-controlled budget.
        // The selected codec must then revalidate everything with its own budget (v1 retains 20/row).
        // Keeping this tree local avoids retaining it during source decoding/conversion.
        val root = StrictBackupJson(text, limits, BackupJsonContract.V2).parse()
            .objectWithKeys(setOf("format", "formatVersion", "schemaVersion", "appVersion", "exportedAt", "payload", "payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup", "未知备份格式")
        return root.getValue("formatVersion").integerValue()
    }
}
