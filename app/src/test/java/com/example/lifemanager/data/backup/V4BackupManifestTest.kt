package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class V4BackupManifestTest {
    private val codec=V4BackupManifest()
    private fun document(): V2BackupDocument {
        val legacy=V2BackupFixtures.empty().let { it.copy(tables=it.tables+mapOf("habit_trees" to emptyList(),"habit_waterings" to emptyList())) }
        val media=DiaryMedia("12345678-1234-1234-1234-123456789012","video/mp4",123,"a".repeat(64))
        val entry=DiaryEntry(17,LocalDate.parse("2024-02-29"),"保留标题\n🌱","正文",DiaryMood.CALM,listOf(media),Instant.ofEpochMilli(-1),Instant.ofEpochMilli(123),7)
        val draft=DiaryDraft("draft",17,6,entry.date," 未完成 ","修改草稿",null,listOf(media),Instant.EPOCH,Instant.ofEpochMilli(12),3)
        val review=DiaryReview("review",entry.date,entry.date,Instant.ofEpochMilli(55),listOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语").map { DiaryReviewSection(it,"回顾🌱",listOf(17)) },listOf(DiarySourceRevision(17,7)),provider="https://example.com/v1",model="model",inputHash="f".repeat(64))
        return V2BackupFixtures.document(legacy.copy(diary=DiaryBackupSnapshot(listOf(entry),listOf(draft),listOf(media),listOf(review),999))).copy(previewToken="never-export-this-token")
    }
    @Test fun fullDiaryRoundtripPreservesSharedMediaDraftRevisionsReviewProvenanceAndHighWater() {
        val original=document()
        val bytes=codec.encode(original)
        assertEquals(original.copy(previewToken=null),codec.decode(bytes))
        val text=bytes.decodeToString()
        assertFalse(text.contains("never-export-this-token"))
        val root=Json.parseToJsonElement(text).jsonObject
        assertEquals(4,root.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(8,root.getValue("schemaVersion").jsonPrimitive.int)
    }
    @Test fun duplicateUnknownFieldsAndTamperedPayloadAreRejected() {
        val text=codec.encode(document()).decodeToString()
        val duplicate=text.replaceFirst("\"schemaVersion\":8","\"schemaVersion\":8,\"schemaVersion\":8")
        val unknown=text.replaceFirst("\"schemaVersion\":8","\"schemaVersion\":8,\"apiKey\":\"secret\"")
        for(bad in listOf(duplicate,unknown,text.replace("修改草稿","被替换"))) assertFailsWith<BackupValidationException> { codec.decode(bad.toByteArray()) }
    }
    @Test fun signedButDanglingOrUnknownDiaryMembersRejectAndLegacyChecksumStillValidates() {
        val root=Json.parseToJsonElement(codec.encode(document()).decodeToString()).jsonObject
        fun withPayload(payload:JsonObject):ByteArray = JsonObject(root+mapOf("payload" to payload,"payloadSha256" to JsonPrimitive(CanonicalBackupJson.sha256(payload)))).toString().toByteArray()
        val payload=root.getValue("payload").jsonObject
        val diary=payload.getValue("diary").jsonObject
        assertFailsWith<BackupValidationException> { codec.decode(withPayload(JsonObject(payload+("diary" to JsonObject(diary+("media" to JsonArray(emptyList())))))) ) }
        assertFailsWith<BackupValidationException> { codec.decode(withPayload(JsonObject(payload+("diary" to JsonObject(diary+("unknown" to JsonPrimitive(1))))))) }
        val legacy=payload.getValue("legacy").jsonObject
        assertFailsWith<BackupValidationException> { codec.decode(withPayload(JsonObject(payload+("legacy" to JsonObject(legacy+("payloadSha256" to JsonPrimitive("0".repeat(64)))))))) }
    }
    @Test fun totalManifestByteAndCombinedRowBudgetsApply() {
        val original=document()
        val bytes=codec.encode(original)
        assertEquals(original.copy(previewToken=null),V4BackupManifest(limits=BackupLimits(maxBytes=bytes.size)).decode(bytes))
        assertFailsWith<BackupValidationException> { V4BackupManifest(limits=BackupLimits(maxBytes=bytes.size-1)).encode(original) }
        assertFailsWith<BackupValidationException> { V4BackupManifest(limits=BackupLimits(maxRows=2)).encode(original) }
        assertFailsWith<BackupValidationException> { codec.encode(original.copy(payload=original.payload.copy(diary=null))) }
    }
    @Test fun validChecksumCannotAdmitUnknownOrIncompleteReviewStructure() {
        val root=Json.parseToJsonElement(codec.encode(document()).decodeToString()).jsonObject
        val payload=root.getValue("payload").jsonObject
        val diary=payload.getValue("diary").jsonObject
        val review=diary.getValue("reviews").jsonArray.single().jsonObject
        val sections=review.getValue("sections").jsonArray
        val badSections=listOf(
            JsonArray(sections.drop(1)+JsonObject(sections.first().jsonObject+("title" to JsonPrimitive("未知段落")))),
            JsonArray(sections.dropLast(1)),
            JsonArray(sections.dropLast(1)+sections.first()),
        )
        badSections.forEach { parts ->
            val changedDiary=JsonObject(diary+("reviews" to JsonArray(listOf(JsonObject(review+("sections" to parts))))))
            val changedPayload=JsonObject(payload+("diary" to changedDiary))
            val changed=JsonObject(root+mapOf("payload" to changedPayload,"payloadSha256" to JsonPrimitive(CanonicalBackupJson.sha256(changedPayload))))
            assertFailsWith<BackupValidationException> { codec.decode(changed.toString().toByteArray()) }
        }
    }
}
