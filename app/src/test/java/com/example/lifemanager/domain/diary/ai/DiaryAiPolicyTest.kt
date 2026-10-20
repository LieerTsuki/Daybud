package com.example.lifemanager.domain.diary.ai

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DiaryAiPolicyTest {
    private val day = LocalDate.of(2026, 10, 19)
    private fun source(id: Long, body: String = "今天散步了") = AiDiarySource(id, 1, day, "一天", body, null)

    @Test fun emptyAndMediaOnlyInputNeverBecomesRequest() {
        assertTrue(AiInputPolicy.plan(emptyList()).chunks.isEmpty())
        assertTrue(AiInputPolicy.plan(listOf(source(1, "").copy(title="$day 日记"))).chunks.isEmpty())
    }

    @Test fun fewTextEntriesCarryLimitedEvidenceNotice() {
        assertTrue(AiInputPolicy.plan(listOf(source(1))).limitedEvidence)
        assertFalse(AiInputPolicy.plan((1L..3L).map { source(it) }).limitedEvidence)
    }
    @Test fun userWrittenTitleCountsAsTextButGeneratedDateTitleDoesNotInventContent() {
        val titled=source(1, "").copy(title="第一次登上山顶")
        val automatic=source(2, "").copy(title="$day 日记")
        val blank=source(3, "").copy(title="")
        val changedDate=source(4, "").copy(date=day.minusDays(1),title="$day 日记")
        val plan=AiInputPolicy.plan(listOf(titled,automatic,blank,changedDate))
        assertEquals(listOf(titled),plan.chunks.flatten())
        assertEquals(3,plan.excludedCount)
    }

    @Test fun chunkingIncludesEveryEntryExactlyOnceAtWholeEntryBoundaries() {
        val entries = (1L..5L).map { source(it, "字".repeat(100)) }
        val plan = AiInputPolicy.plan(entries, maxChunkChars = 260, maxTotalChars = 2000, maxChunks = 10)
        assertEquals(entries, plan.chunks.flatten())
        assertTrue(plan.chunks.size > 1)
        assertEquals(5, plan.includedCount)
    }

    @Test fun oversizeEntryAndTotalBudgetAreRejectedWithoutTruncation() {
        assertThrows(AiBudgetException::class.java) { AiInputPolicy.plan(listOf(source(1, "字".repeat(500))), 200) }
        assertThrows(AiBudgetException::class.java) { AiInputPolicy.plan((1L..5L).map { source(it) }, maxTotalChars = 10) }
    }

    @Test fun invalidSectionOrForeignSourceCannotPublish() {
        val valid = AiSectionKind.entries.map { AiReviewSection(it, "有记录的生活", setOf(1)) }
        AiResultPolicy.validate(valid, setOf(1))
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid.dropLast(1), setOf(1)) }
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid.map { it.copy(sourceIds = setOf(99)) }, setOf(1)) }
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid.map { it.copy(sourceIds = emptySet()) }, setOf(1)) }
    }

    @Test fun duplicateSectionsAndDiagnosisAreRejected() {
        val valid = AiSectionKind.entries.map { AiReviewSection(it, "有记录的生活", setOf(1)) }
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid.dropLast(1) + valid.first(), setOf(1)) }
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid.map { it.copy(text = "你患有抑郁症") }, setOf(1)) }
    }
}
