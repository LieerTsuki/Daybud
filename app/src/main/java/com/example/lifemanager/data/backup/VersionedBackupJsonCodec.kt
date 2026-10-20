package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/** Production entry point: strict historical hash checks, current forest conversion and V3 export. */
class VersionedBackupJsonCodec(private val limits: BackupLimits = BackupLimits()) : VersionedBackupCodec {
    override fun encode(document: V2BackupDocument): ByteArray {
        checkBackup(document.payload.diary == null, "日记和媒体必须使用完整v4备份")
        return V3BackupJsonCodec(limits).encode(document)
    }

    override fun decode(bytes: ByteArray, legacyTodoZone: ZoneId): BackupImportResult = when (version(bytes)) {
        1L -> LegacyBackupConverter(limits).convert(BackupJsonCodec(limits).decode(bytes), legacyTodoZone).let {
            it.copy(document = ForestBackupConverter(limits).convert(it.document))
        }
        2L -> BackupImportResult(ForestBackupConverter(limits).convert(V2BackupJsonCodec(limits).decode(bytes)), BackupSourceFormat.V2)
        3L -> BackupImportResult(V3BackupJsonCodec(limits).decode(bytes), BackupSourceFormat.V3)
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
        val root = StrictBackupJson(text, limits, BackupJsonContract.V3).parse()
            .objectWithKeys(setOf("format", "formatVersion", "schemaVersion", "appVersion", "exportedAt", "payload", "payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup", "未知备份格式")
        return root.getValue("formatVersion").integerValue()
    }
}
