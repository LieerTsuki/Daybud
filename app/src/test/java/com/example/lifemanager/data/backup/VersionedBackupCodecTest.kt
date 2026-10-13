package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class VersionedBackupCodecTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun old(payload: BackupPayload = BackupFixtures.fullPayload()) = BackupJsonCodec().encode(BackupDocument("0.1.1", "2026-10-13T00:00:00Z", payload))
    private fun fresh(payload: V2BackupPayload = V2BackupFixtures.full()) = V2BackupJsonCodec().encode(V2BackupFixtures.document(payload))
    private fun decode(bytes: ByteArray, limits: BackupLimits = BackupLimits()) = VersionedBackupJsonCodec(limits).decode(bytes, zone)
    private fun encode(document: V2BackupDocument, limits: BackupLimits = BackupLimits()) = VersionedBackupJsonCodec(limits).encode(document)
    private fun target(result: BackupImportResult) = result.document
    private fun notice(result: BackupImportResult) = result.legacyTodoTimeZone
    private fun source(result: BackupImportResult) = result.sourceFormat.name
    private fun root(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    private fun rehash(root: JsonObject, payload: JsonObject): ByteArray {
        fun sorted(value: JsonElement): JsonElement = when(value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(value.map(::sorted)); else -> value
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(sorted(payload).toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return JsonObject(root + ("payload" to payload) + ("payloadSha256" to JsonPrimitive(hash))).toString().toByteArray()
    }
    @Test fun `v1 original checksum passes before conversion and v2 export computes a new checksum`() {
        val input = old(); val result = decode(input); val output = encode(target(result)); val v2 = root(output)
        assertEquals("V1", source(result)); assertEquals("Asia/Shanghai", notice(result))
        assertEquals(2, v2.getValue("formatVersion").jsonPrimitive.int); assertEquals(6, v2.getValue("schemaVersion").jsonPrimitive.int)
        assertNotEquals(root(input).getValue("payloadSha256"), v2.getValue("payloadSha256"))
        assertEquals(target(result), V2BackupJsonCodec().decode(output))
        assertEquals("0.1.1", target(result).appVersion); assertEquals("2026-10-13T00:00:00Z", target(result).exportedAt)
        assertFalse(output.decodeToString().contains("legacyTodoTimeZone")); assertFalse(output.decodeToString().contains("sourceFormat"))
    }
    @Test fun `v2 direct import preserves all raw fields and never injects todo zone`() {
        val result = decode(fresh()); assertEquals("V2", source(result)); assertNull(notice(result))
        assertEquals(V2BackupFixtures.document(V2BackupFixtures.full()), target(result))
    }
    @Test fun `empty files route by format with exact empty target counts and no timezone notice`() {
        val v1 = decode(old(BackupPayload(BackupFixtures.emptyTables, com.example.lifemanager.domain.model.AppSettings())))
        val v2 = decode(fresh(V2BackupFixtures.empty()))
        for (result in listOf(v1, v2)) {
            assertNull(notice(result)); assertTrue(target(result).payload.tables.values.all { it.isEmpty() })
            val counts = result.rowCounts
            assertEquals(V2BackupFixtures.names.associateWith { 0 }, counts)
        }
    }
    @Test fun `unknown versions mismatched schemas and numeric strings reject without fallback`() {
        for (bytes in listOf(old(), fresh())) {
            val original = root(bytes)
            for (bad in listOf(original + ("formatVersion" to JsonPrimitive(3)), original + ("formatVersion" to JsonPrimitive("1")),
                original + ("schemaVersion" to JsonPrimitive(99)), original + ("format" to JsonPrimitive("foreign")),
                original + ("formatVersion" to JsonPrimitive(0)), original + ("schemaVersion" to JsonPrimitive(if (bytes.contentEquals(old())) 6 else 5)))) {
                assertFailsWith<BackupValidationException> { decode(JsonObject(bad).toString().toByteArray()) }
            }
        }
    }
    @Test fun `corrupt old checksum wins over target semantic error and is never recalculated to accept`() {
        val original = root(old()); val payload = original.getValue("payload").jsonObject
        val badSchedule = JsonObject(payload.getValue("schedules").jsonArray.single().jsonObject + ("endAt" to JsonPrimitive(1000)))
        val badPayload = JsonObject(payload + ("schedules" to JsonArray(listOf(badSchedule))))
        val error = assertFailsWith<BackupValidationException> { decode(JsonObject(original + ("payload" to badPayload)).toString().toByteArray()) }
        assertTrue(error.message.orEmpty().contains("校验不通过"), "Original checksum must reject before conversion semantics")
        assertFailsWith<BackupValidationException> { decode(rehash(original, badPayload)) }
    }
    @Test fun `valid original hash cannot bypass old types relations or field whitelist`() {
        val original = root(old()); val payload = original.getValue("payload").jsonObject
        val todo = payload.getValue("todos").jsonArray.single().jsonObject
        for (bad in listOf(JsonObject(todo + ("id" to JsonPrimitive("41"))), JsonObject(todo - "description"),
            JsonObject(todo + ("extra" to JsonPrimitive(1))), JsonObject(todo + ("parentId" to JsonPrimitive(999))), JsonObject(todo + ("parentId" to JsonPrimitive(41))))) {
            assertFailsWith<BackupValidationException> { decode(rehash(original, JsonObject(payload + ("todos" to JsonArray(listOf(bad)))))) }
        }
    }
    @Test fun `valid v1 hash and schema cannot bypass v2 active time and reminder semantics`() {
        val original = root(old()); val payload = original.getValue("payload").jsonObject
        val schedule = payload.getValue("schedules").jsonArray.single().jsonObject
        for (bad in listOf(JsonObject(schedule + ("startAt" to JsonNull)), JsonObject(schedule + ("repeatInterval" to JsonPrimitive(0))),
            JsonObject(schedule + ("reminderMinutes" to JsonPrimitive(-1))))) {
            val input = rehash(original, JsonObject(payload + ("schedules" to JsonArray(listOf(bad)))))
            BackupJsonCodec().decode(input)
            assertFailsWith<BackupValidationException> { decode(input) }
        }
    }
    @Test fun `v2 checksum fields and source uniqueness still use strict v2 validator`() {
        val bytes = fresh(); assertFailsWith<BackupValidationException> { decode(bytes.decodeToString().replace("\"EUR\"", "\"CNY\"").toByteArray()) }
        val original = root(bytes); val payload = original.getValue("payload").jsonObject
        val refs = payload.getValue("plan_legacy_refs").jsonArray
        assertFailsWith<BackupValidationException> { decode(rehash(original, JsonObject(payload + ("plan_legacy_refs" to JsonArray(listOf(refs[0],refs[0])))))) }
    }
    @Test fun `arbitrary envelope ordering and escaped version key route correctly`() {
        val original = root(fresh()); val reordered = JsonObject(original.entries.reversed().associate { it.toPair() })
        val text = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), reordered).replace("\"formatVersion\"", "\"format\\u0056ersion\"")
        assertEquals("V2", source(decode(text.toByteArray())))
    }
    @Test fun `decoded duplicate version keys unknown envelope fields and trailing content reject`() {
        val text = fresh().decodeToString()
        for (bad in listOf(text.replace("\"formatVersion\":2", "\"formatVersion\":2,\"format\\u0056ersion\":1"),
            text.replace("\"formatVersion\":2", "\"formatVersion\":2,\"extra\":1"), text + "{}")) {
            assertFailsWith<BackupValidationException> { decode(bad.toByteArray()) }
        }
    }
    @Test fun `bounded probing rejects malformed utf8 nesting floating exponent and overflow versions`() {
        assertFailsWith<BackupValidationException> { decode(byteArrayOf(0xC3.toByte(),0x28)) }
        for (number in listOf("1.0", "1e0", "9223372036854775808")) assertFailsWith<BackupValidationException> {
            decode(old().decodeToString().replace("\"formatVersion\":1", "\"formatVersion\":$number").toByteArray())
        }
        assertFailsWith<BackupValidationException> { decode(("[".repeat(9) + "0" + "]".repeat(9)).toByteArray()) }
    }
    @Test fun `converted row and byte expansion rejects source that alone fits input limits`() {
        val input = old(); assertFailsWith<BackupValidationException> { decode(input, BackupLimits(maxRows = 11)) }
        val size = encode(target(decode(input))).size; assertTrue(size > input.size)
        assertEquals(12, target(decode(input, BackupLimits(maxBytes = size, maxRows = 12))).payload.tables.values.sumOf { it.size })
        assertFailsWith<BackupValidationException> { decode(input, BackupLimits(maxBytes = size - 1)) }
    }
    @Test fun `input bytes limit applies before version discovery with exact v2 boundary`() {
        val input = fresh(); assertEquals("V2", source(decode(input, BackupLimits(maxBytes = input.size))))
        assertFailsWith<BackupValidationException> { decode(input, BackupLimits(maxBytes = input.size - 1)) }
        val oversized = assertFailsWith<BackupValidationException> { decode(ByteArray(64 * 1024 * 1024 + 1)) }
        assertTrue(oversized.message.orEmpty().contains("字节限制"), "Reject oversized bytes before attempting JSON discovery")
    }
    @Test fun `v1 second pass retains original twenty member budget after v2 bounded discovery`() {
        val original = root(old(BackupPayload(BackupFixtures.emptyTables, com.example.lifemanager.domain.model.AppSettings())))
        val payload = original.getValue("payload").jsonObject
        val objectRow = JsonObject((1..46).associate { "bad$it" to JsonPrimitive(1) })
        val input = rehash(original, JsonObject(payload + ("todos" to JsonArray(listOf(objectRow)))))
        val error = assertFailsWith<BackupValidationException> { decode(input, BackupLimits(maxRows = 1)) }
        assertTrue(error.message.orEmpty().contains("字段数量超限"), "V1 must enforce its original 70-member budget, not V2's 77")
    }
    @Test fun `v2 twenty seven field plan parsing succeeds near aggregate row limit`() {
        val template = V2BackupFixtures.plan()
        val payload = V2BackupFixtures.empty().copy(tables = V2BackupFixtures.empty().tables + ("plans" to (1..100).map { template.with("id", BackupValue.Integer(it.toLong())) }))
        val input = fresh(payload)
        assertEquals(payload, target(decode(input, BackupLimits(maxRows = 100))).payload)
    }
}
