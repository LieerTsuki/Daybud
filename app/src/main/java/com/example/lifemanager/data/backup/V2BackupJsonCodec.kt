package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Isolated codec. Production BackupCodec/Hilt and v1 bytes stay unchanged until E. */
class V2BackupJsonCodec(private val limits: BackupLimits = BackupLimits()) : V2BackupCodec {
    override fun encode(document: V2BackupDocument): ByteArray {
        V2BackupValidator.validate(document, limits)
        val payload = V2BackupJsonMapping.toJson(document.payload)
        val root = buildJsonObject {
            put("format", "life-manager-backup"); put("formatVersion", 2); put("schemaVersion", 6)
            put("appVersion", document.appVersion); put("exportedAt", document.exportedAt)
            put("payload", payload); put("payloadSha256", "0".repeat(64))
        }
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
        val root = StrictBackupJson(text, limits, BackupJsonContract.V2).parse()
            .objectWithKeys(setOf("format", "formatVersion", "schemaVersion", "appVersion", "exportedAt", "payload", "payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup", "未知备份格式")
        checkBackup(root.getValue("formatVersion").integerValue() == 2L, "不支持的备份格式版本")
        checkBackup(root.getValue("schemaVersion").integerValue() == 6L, "不支持的数据库版本")
        val payload = root.getValue("payload")
        val hash = root.getValue("payloadSha256").textValue()
        checkBackup(hash.matches(Regex("[0-9a-f]{64}")), "备份校验值格式无效")
        checkBackup(MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), CanonicalBackupJson.sha256(payload).toByteArray(Charsets.US_ASCII)), "备份校验不通过，文件可能已损坏")
        return V2BackupDocument(root.getValue("appVersion").textValue(), root.getValue("exportedAt").textValue(), V2BackupJsonMapping.fromJson(payload))
            .also { V2BackupValidator.validate(it, limits) }
    }
}
