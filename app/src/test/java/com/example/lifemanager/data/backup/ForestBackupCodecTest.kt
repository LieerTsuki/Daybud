package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ForestBackupCodecTest {
    private val codec = VersionedBackupJsonCodec()
    private val zone = ZoneId.of("UTC")
    private fun current() = V2BackupFixtures.full().let { old -> old.copy(tables = old.tables + mapOf(
        "habit_trees" to listOf(V2BackupFixtures.row("id" to 31L, "habitId" to 5L, "species" to "MAPLE", "plantedAt" to 7L)),
        "habit_waterings" to listOf(V2BackupFixtures.row("recordId" to 9L, "treeId" to 31L)))) }

    @Test fun `full forest exports format3 schema7 and roundtrips consumption`() {
        val document = V2BackupFixtures.document(current())
        val bytes = codec.encode(document)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(3, root.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(7, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(document, codec.decode(bytes, zone).document)
        assertEquals("V3", codec.decode(bytes, zone).sourceFormat.name)
        assertFailsWith<BackupValidationException> { V2BackupJsonCodec().encode(document) }
    }
    @Test fun `old v2 validates its original checksum then imports one seed without consuming records`() {
        val source = V2BackupFixtures.document()
        val bytes = V2BackupJsonCodec().encode(source)
        val converted = codec.decode(bytes, zone).document.payload
        assertEquals(source.payload.tables, converted.tables - "habit_trees" - "habit_waterings")
        assertEquals(1, converted.tables.getValue("habit_trees").size)
        assertTrue(converted.tables.getValue("habit_waterings").isEmpty())
        assertFailsWith<BackupValidationException> { codec.decode(bytes.decodeToString().replace("\"EUR\"", "\"CNY\"").toByteArray(), zone) }
    }
    @Test fun `cross habit dangling duplicate and over twenty watering links reject`() {
        val source = current()
        val otherHabit = source.tables.getValue("habits").single().with("id", BackupValue.Integer(6))
        val cross = source.table("habits", source.tables.getValue("habits") + otherHabit)
            .table("habit_trees", listOf(source.tables.getValue("habit_trees").single().with("habitId", BackupValue.Integer(6))))
        val records = (1L..21).map { id -> V2BackupFixtures.row("id" to id, "habitId" to 5L, "date" to id, "createdAt" to 7L) }
        val over = source.table("habit_records", records).table("habit_waterings", records.map { V2BackupFixtures.row("recordId" to (it.fields.getValue("id") as BackupValue.Integer).value, "treeId" to 31L) })
        for (bad in listOf(cross, over, source.table("habit_waterings", source.tables.getValue("habit_waterings") + source.tables.getValue("habit_waterings")),
            source.table("habit_waterings", listOf(V2BackupFixtures.row("recordId" to 999L, "treeId" to 31L))),
            source.table("habit_trees", listOf(source.tables.getValue("habit_trees").single().with("species", BackupValue.Text("INVALID")))))) {
            assertFailsWith<BackupValidationException> { codec.encode(V2BackupFixtures.document(bad)) }
        }
    }
    @Test fun `forest rows and converted seeds count toward row and complete byte budgets`() {
        val document = V2BackupFixtures.document(current())
        val bytes = codec.encode(document)
        assertEquals(document, VersionedBackupJsonCodec(BackupLimits(maxBytes = bytes.size)).decode(bytes, zone).document)
        assertFailsWith<BackupValidationException> { VersionedBackupJsonCodec(BackupLimits(maxBytes = bytes.size - 1)).encode(document) }
        val count = current().tables.values.sumOf { it.size }
        assertFailsWith<BackupValidationException> { VersionedBackupJsonCodec(BackupLimits(maxRows = count - 1)).encode(document) }
        val old = V2BackupFixtures.document()
        val oldBytes = V2BackupJsonCodec().encode(old)
        assertFailsWith<BackupValidationException> { VersionedBackupJsonCodec(BackupLimits(maxRows = old.payload.tables.values.sumOf { it.size })).decode(oldBytes, zone) }
    }
}
