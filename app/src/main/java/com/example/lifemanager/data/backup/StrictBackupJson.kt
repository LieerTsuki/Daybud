package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.BackupLimits
import com.example.lifemanager.domain.backup.BackupValidationException
import com.example.lifemanager.domain.backup.checkBackup
import com.example.lifemanager.domain.backup.checkBackupUnicode
import kotlinx.serialization.json.*
import com.example.lifemanager.domain.backup.V2BackupSchema
import com.example.lifemanager.domain.backup.V3BackupSchema

internal enum class BackupJsonContract { V1, V2, V3, V4 }

/**
 * Strict reader with version-specific, non-input-controlled member budgets. Unlike a last-key-wins JSON tree parser, duplicate
 * decoded member names are rejected before they can disappear. No floating
 * point conversion, lenient syntax or arbitrary recursion is accepted.
 */
internal class StrictBackupJson(private val source: String, private val limits: BackupLimits,
    contract: BackupJsonContract = BackupJsonContract.V1) {
    private val rowMembers = when (contract) {
        BackupJsonContract.V1 -> 20
        BackupJsonContract.V2 -> V2BackupSchema.maxRowMembers
        BackupJsonContract.V3 -> V3BackupSchema.maxRowMembers
        BackupJsonContract.V4 -> maxOf(V3BackupSchema.maxRowMembers, 32)
    }
    private val maximumDepth = if (contract == BackupJsonContract.V4) 14 else 8
    private val maximumArrayItems = if (contract == BackupJsonContract.V4) limits.maxRows.toLong() * 6 + 50 else limits.maxRows.toLong() + 3
    private var position = 0
    private var arrayItems = 0
    private var members = 0

    fun parse(): JsonElement {
        val value = value(0)
        whitespace()
        checkBackup(position == source.length, "JSON 尾部包含额外内容")
        return value
    }

    private fun value(depth: Int): JsonElement {
        checkBackup(depth <= maximumDepth, "JSON 嵌套层数过多")
        whitespace()
        return when (peek()) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> JsonPrimitive(string())
            't' -> literal("true", JsonPrimitive(true))
            'f' -> literal("false", JsonPrimitive(false))
            'n' -> literal("null", JsonNull)
            '-', in '0'..'9' -> number()
            else -> throw BackupValidationException("JSON 语法无效或文件不完整")
        }
    }

    private fun objectValue(depth: Int): JsonObject {
        expect('{')
        whitespace()
        val values = linkedMapOf<String, JsonElement>()
        if (consume('}')) return JsonObject(values)
        while (true) {
            whitespace()
            val key = string()
            checkBackup(key !in values, "JSON 存在重复字段")
            // Keep v1's original budget; v2 has 27-column plans. Also bound malicious
            // object-only documents before constructing their complete trees.
            checkBackup(++members <= limits.maxRows.toLong() * rowMembers + 50, "JSON 字段数量超限")
            whitespace(); expect(':')
            values[key] = value(depth)
            whitespace()
            if (consume('}')) return JsonObject(values)
            expect(',')
        }
    }

    private fun arrayValue(depth: Int): JsonArray {
        expect('[')
        whitespace()
        val values = mutableListOf<JsonElement>()
        if (consume(']')) return JsonArray(values)
        while (true) {
            // Table arrays contain maxRows in aggregate; settings add <=3.
            checkBackup(++arrayItems <= maximumArrayItems, "JSON 数组记录数量超限")
            values.add(value(depth))
            whitespace()
            if (consume(']')) return JsonArray(values)
            expect(',')
        }
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            when {
                char == '"' -> return result.toString().also(::checkBackupUnicode)
                char == '\\' -> {
                    checkBackup(position < source.length, "JSON 字符串不完整")
                    result.append(when (val escaped = source[position++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            checkBackup(position + 4 <= source.length, "JSON Unicode 转义不完整")
                            val hex = source.substring(position, position + 4)
                            checkBackup(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }, "JSON Unicode 转义无效")
                            position += 4
                            hex.toInt(16).toChar()
                        }
                        else -> throw BackupValidationException("JSON 字符串转义无效")
                    })
                }
                char.code < 0x20 -> throw BackupValidationException("JSON 字符串包含未转义控制字符")
                else -> result.append(char)
            }
        }
        throw BackupValidationException("JSON 字符串不完整")
    }

    private fun number(): JsonPrimitive {
        val begin = position
        consume('-')
        if (!consume('0')) {
            checkBackup(peek() in '1'..'9', "JSON 整数无效")
            while (peek() in '0'..'9') {
                position++
                checkBackup(position - begin <= 20, "JSON 整数超出 64 位范围")
            }
        }
        val number = source.substring(begin, position).toLongOrNull()
            ?: throw BackupValidationException("JSON 整数超出 64 位范围")
        return JsonPrimitive(number)
    }

    private fun literal(token: String, value: JsonElement): JsonElement {
        checkBackup(source.startsWith(token, position), "JSON 字面值无效")
        position += token.length
        return value
    }
    private fun whitespace() {
        while (true) when (peek()) { ' ', '\n', '\r', '\t' -> position++; else -> return }
    }
    private fun expect(char: Char) { checkBackup(consume(char), "JSON 语法无效或文件不完整") }
    private fun consume(char: Char): Boolean = if (peek() == char) { position++; true } else false
    private fun peek(): Char = source.getOrNull(position) ?: '\u0000'
}
