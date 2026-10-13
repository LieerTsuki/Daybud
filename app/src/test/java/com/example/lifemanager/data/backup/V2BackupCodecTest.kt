package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class V2BackupCodecTest {
    private val codec = V2BackupJsonCodec()
    private fun encode(payload: V2BackupPayload = V2BackupFixtures.empty(), limits: BackupLimits = BackupLimits()): ByteArray {
        val bytes = V2BackupJsonCodec(limits).encode(V2BackupFixtures.document(payload))
        assertTrue(bytes.isNotEmpty(), "v2 encoding must produce a complete document")
        return bytes
    }
    private fun root(payload: V2BackupPayload = V2BackupFixtures.empty()) = Json.parseToJsonElement(encode(payload).decodeToString()).jsonObject
    @Test fun `empty v2 contains every explicit business table new settings and correct versions`() {
        val root = root()
        assertEquals(2, root.getValue("formatVersion").jsonPrimitive.int); assertEquals(6, root.getValue("schemaVersion").jsonPrimitive.int)
        val payload = root.getValue("payload").jsonObject
        assertEquals(V2BackupFixtures.names.toSet() + "settings", payload.keys)
        assertEquals(setOf("theme", "dateFormat", "defaultCurrency", "planReminders", "subscriptionReminders", "defaultReminderDays"), payload.getValue("settings").jsonObject.keys)
        assertEquals(V2BackupFixtures.empty(), codec.decode(encode()).payload)
    }
    @Test fun `full roundtrip preserves Long extrema raw strings inactive fields and history`() {
        val full = V2BackupFixtures.full(); val bytes = encode(full)
        assertTrue(bytes.decodeToString().contains("-9223372036854775808")); assertTrue(bytes.decodeToString().contains("9223372036854775807"))
        val decoded = codec.decode(bytes).payload
        assertEquals(full, decoded)
        assertEquals(BackupValue.Text("7,01,1"), decoded.tables.getValue("plans").first().fields["repeatDaysOfWeek"])
        assertEquals(BackupValue.Integer(9007199254740993), decoded.tables.getValue("subscription_payments").single().fields["amountMinor"])
    }
    @Test fun `canonical empty hash has independently derived golden and accepts object reordering`() {
        val document = root()
        val payload = document.getValue("payload").jsonObject
        assertEquals(GOLDEN, document.getValue("payloadSha256").jsonPrimitive.content)
        assertEquals(GOLDEN, hash(CANONICAL_EMPTY.toByteArray()))
        val reordered = JsonObject((document + ("payload" to JsonObject(payload.entries.reversed().associate { it.toPair() }))).entries.reversed().associate { it.toPair() })
        val pretty = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), reordered)
        assertEquals(V2BackupFixtures.empty(), codec.decode(pretty.toByteArray()).payload)
    }
    @Test fun `hash mismatch or invalid checksum syntax is rejected`() {
        val json = encode().decodeToString()
        assertFailsWith<BackupValidationException> { codec.decode(json.replace("\"CNY\"", "\"EUR\"").toByteArray()) }
        for (hash in listOf("0".repeat(64), "A".repeat(64), "a".repeat(63))) {
            assertFailsWith<BackupValidationException> { codec.decode(JsonObject(root() + ("payloadSha256" to JsonPrimitive(hash))).toString().toByteArray()) }
        }
    }
    @Test fun `v1 and v2 codecs reject each others versions rather than silently convert`() {
        val old = BackupJsonCodec().encode(BackupDocument("0.1.1", "2026-10-13T00:00:00Z", BackupFixtures.fullPayload()))
        assertFailsWith<BackupValidationException> { codec.decode(old) }
        assertFailsWith<BackupValidationException> { BackupJsonCodec().decode(encode()) }
    }
    @Test fun `rejects unknown versions metadata keys and missing nullable business fields`() {
        val json = encode().decodeToString()
        for (bad in listOf(json.replace("\"formatVersion\":2", "\"formatVersion\":3"), json.replace("\"schemaVersion\":6", "\"schemaVersion\":5"),
            json.replace("\"format\":", "\"extra\":1,\"format\":"))) assertFailsWith<BackupValidationException> { codec.decode(bad.toByteArray()) }
        val full = root(V2BackupFixtures.full()); val payload = full.getValue("payload").jsonObject
        val plan = payload.getValue("plans").jsonArray.first().jsonObject
        for (bad in listOf(JsonObject(plan - "description"), JsonObject(plan + ("extra" to JsonPrimitive(1))))) {
            assertFailsWith<BackupValidationException> { codec.decode(rehash(full, JsonObject(payload + ("plans" to JsonArray(listOf(bad)))))) }
        }
    }
    @Test fun `valid hash cannot authorize foreign tables local state or old settings keys`() {
        val root = root(); val payload = root.getValue("payload").jsonObject
        for (name in listOf("todos", "app_maintenance", "plan_reminder_state")) {
            assertFailsWith<BackupValidationException> { codec.decode(rehash(root, JsonObject(payload + (name to JsonArray(emptyList()))))) }
        }
        val settings = payload.getValue("settings").jsonObject
        for (bad in listOf(JsonObject(settings - "planReminders"), JsonObject(settings + ("todoReminders" to JsonPrimitive(true))))) {
            assertFailsWith<BackupValidationException> { codec.decode(rehash(root, JsonObject(payload + ("settings" to bad)))) }
        }
    }
    @Test fun `duplicate decoded keys are rejected at envelope nested settings and row levels`() {
        val json = encode().decodeToString()
        for (bad in listOf(json.replace("\"formatVersion\":2", "\"formatVersion\":2,\"format\\u0056ersion\":2"),
            json.replace("\"planReminders\":true", "\"planReminders\":true,\"planReminders\":false"),
            encode(V2BackupFixtures.full()).decodeToString().replace("\"sortOrder\":-3", "\"sortOrder\":-3,\"sortOrder\":-3"))) {
            assertFailsWith<BackupValidationException> { codec.decode(bad.toByteArray()) }
        }
    }
    @Test fun `rejects malformed UTF8 unicode nesting truncation and trailing data`() {
        val bytes = encode()
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), bytes.copyOf(bytes.size - 1), bytes + " true".toByteArray(),
            "[".repeat(100).toByteArray(), bytes.decodeToString().replace("SYSTEM", "\\uD800").toByteArray())) {
            assertFailsWith<BackupValidationException> { codec.decode(bad) }
        }
    }
    @Test fun `rejects quoted numeric fractional exponent overflow and boolean aliases`() {
        val root = root(V2BackupFixtures.full()); val payload = root.getValue("payload").jsonObject
        val row = payload.getValue("plans").jsonArray.first().jsonObject
        for (value in listOf(JsonPrimitive("17"), JsonPrimitive(1.5), JsonPrimitive(true), JsonPrimitive("9223372036854775808"))) {
            val bad = JsonObject(row + ("id" to value))
            assertFailsWith<BackupValidationException> { codec.decode(rehash(root, JsonObject(payload + ("plans" to JsonArray(listOf(bad)))))) }
        }
        for (token in listOf("1e1", "9223372036854775808", "+17")) {
            assertFailsWith<BackupValidationException> { codec.decode(encode(V2BackupFixtures.full()).decodeToString().replace("\"id\":17", "\"id\":$token").toByteArray()) }
        }
    }
    @Test fun `settings rejects duplicate offsets wrong types unknown enums and invalid values`() {
        val root = root(); val payload = root.getValue("payload").jsonObject; val settings = payload.getValue("settings").jsonObject
        for ((key, value) in listOf("defaultReminderDays" to JsonNull, "defaultReminderDays" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))),
            "defaultReminderDays" to JsonArray(listOf(JsonPrimitive(2))), "planReminders" to JsonPrimitive(1),
            "theme" to JsonPrimitive("bad"), "dateFormat" to JsonPrimitive("bad"), "defaultCurrency" to JsonPrimitive("bad"))) {
            assertFailsWith<BackupValidationException> { codec.decode(rehash(root, JsonObject(payload + ("settings" to JsonObject(settings + (key to value)))))) }
        }
    }
    @Test fun `encode validates rather than emitting corrupt documents`() {
        assertFailsWith<BackupValidationException> { codec.encode(V2BackupFixtures.document().copy(appVersion = " ")) }
        assertFailsWith<BackupValidationException> { codec.encode(V2BackupFixtures.document(V2BackupFixtures.empty().table("plans", listOf(V2BackupFixtures.plan(0))))) }
    }
    @Test fun `exact UTF8 byte budget includes escaping metadata and unicode`() {
        val payload = V2BackupFixtures.full(); val bytes = encode(payload)
        assertContentEquals(bytes, encode(payload, BackupLimits(maxBytes = bytes.size)))
        assertFailsWith<BackupValidationException> { encode(payload, BackupLimits(maxBytes = bytes.size - 1)) }
        assertEquals(payload, V2BackupJsonCodec(BackupLimits(maxBytes = bytes.size)).decode(bytes).payload)
        assertFailsWith<BackupValidationException> { V2BackupJsonCodec(BackupLimits(maxBytes = bytes.size - 1)).decode(bytes) }
    }
    @Test fun `aggregate row limit includes sources and codec checks both directions`() {
        val payload = V2BackupFixtures.full(); val count = payload.tables.values.sumOf { it.size }
        val bytes = encode(payload, BackupLimits(maxRows = count))
        assertEquals(payload, V2BackupJsonCodec(BackupLimits(maxRows = count)).decode(bytes).payload)
        assertFailsWith<BackupValidationException> { encode(payload, BackupLimits(maxRows = count - 1)) }
        assertFailsWith<BackupValidationException> { V2BackupJsonCodec(BackupLimits(maxRows = count - 1)).decode(bytes) }
    }
    @Test fun `27 column plan budget admits a complete bounded collection`() {
        val payload = V2BackupFixtures.empty().table("plans", (1L..100L).map { V2BackupFixtures.plan(it) })
        assertEquals(payload, V2BackupJsonCodec(BackupLimits(maxRows = 100)).decode(encode(payload, BackupLimits(maxRows = 100))).payload)
    }
    @Test fun `default 64MiB input limit rejects excess bytes before parsing`() {
        assertFailsWith<BackupValidationException> { codec.decode(ByteArray(64 * 1024 * 1024 + 1)) }
    }
    private fun rehash(root: JsonObject, payload: JsonObject) = JsonObject(root + mapOf("payload" to payload,
        "payloadSha256" to JsonPrimitive(hash(canonical(payload).toString().toByteArray())))).toString().toByteArray()
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        const val CANONICAL_EMPTY = "{\"habit_records\":[],\"habits\":[],\"plan_exceptions\":[],\"plan_legacy_refs\":[],\"plan_tag_cross_ref\":[],\"plans\":[],\"settings\":{\"dateFormat\":\"YMD\",\"defaultCurrency\":\"CNY\",\"defaultReminderDays\":[],\"planReminders\":true,\"subscriptionReminders\":true,\"theme\":\"SYSTEM\"},\"subscription_payments\":[],\"subscription_reminders\":[],\"subscriptions\":[],\"tags\":[]}"
        const val GOLDEN = "70530df1d173be20aba4bcff857f5c1c0b69ea6f4e014445a9504f48f82e427b"
    }
}
