package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Explicit current format3/schema7 codec; historical codecs retain their original contracts. */
class V3BackupJsonCodec(private val limits: BackupLimits = BackupLimits()) : V2BackupCodec {
    override fun encode(document: V2BackupDocument): ByteArray {
        V3BackupValidator.validate(document, limits)
        val root = V3BackupJsonMapping.envelope(document)
        val payload = root.getValue("payload")
        val size = CanonicalBackupJson.byteCount(root, limits.maxBytes)
        val checked = JsonObject(root + ("payloadSha256" to JsonPrimitive(CanonicalBackupJson.sha256(payload))))
        return CanonicalBackupJson.encode(checked, size)
    }
    override fun decode(bytes: ByteArray): V2BackupDocument {
        checkBackup(bytes.size <= limits.maxBytes, "备份超过 ${limits.maxBytes} 字节限制")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) { throw BackupValidationException("备份不是有效 UTF-8 文件", error) }
        val root = StrictBackupJson(text, limits, BackupJsonContract.V3).parse()
            .objectWithKeys(setOf("format", "formatVersion", "schemaVersion", "appVersion", "exportedAt", "payload", "payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup", "未知备份格式")
        checkBackup(root.getValue("formatVersion").integerValue() == 3L, "不支持的备份格式版本")
        checkBackup(root.getValue("schemaVersion").integerValue() == 7L, "不支持的数据库版本")
        val payload = root.getValue("payload")
        val hash = root.getValue("payloadSha256").textValue()
        checkBackup(hash.matches(Regex("[0-9a-f]{64}")), "备份校验值格式无效")
        checkBackup(MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), CanonicalBackupJson.sha256(payload).toByteArray(Charsets.US_ASCII)), "备份校验不通过，文件可能已损坏")
        return V2BackupDocument(root.getValue("appVersion").textValue(), root.getValue("exportedAt").textValue(), V3BackupJsonMapping.fromJson(payload))
            .also { V3BackupValidator.validate(it, limits) }
    }
}
