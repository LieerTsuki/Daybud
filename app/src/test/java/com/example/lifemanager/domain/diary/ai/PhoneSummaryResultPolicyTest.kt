package com.example.lifemanager.domain.diary.ai

import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneSummaryResultPolicyTest {
    private val sources = listOf(
        AiDiarySource(2, 5, LocalDate.of(2026, 10, 20), "报告", "还没有完成。", null),
        AiDiarySource(1, 3, LocalDate.of(2026, 10, 19), "\"日记\"", "第一行\n第二行😀", "平静"),
    )
    private fun sections(): List<AiReviewSection> {
        val ids = setOf(1L, 2L)
        return listOf(
            AiReviewSection(AiSectionKind.LIFE, "这段时间你记录了日常进展，目前报告还没有完成。", ids),
            AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), ids),
            AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), ids),
            AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以先列出报告的三个小标题，再补上现有材料。", ids),
            AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨预留二十分钟整理思路，之后再选择下一步。", ids),
        )
    }

    @Test fun validSummaryKeepsFiveStorageKindsAndAllSources() {
        PhoneSummaryResultPolicy.validate(sections(), sources)
        assertEquals(5, sections().size)
    }

    @Test fun sparseNotesMayHaveShortPlainSummaryAndSmallOptionalActions() {
        PhoneSummaryResultPolicy.validate(sections().map {
            when (it.kind) {
                AiSectionKind.LIFE -> it.copy(text = "记录了读书。")
                AiSectionKind.GROWTH -> it.copy(text = "参考建议：可先补充书名。")
                AiSectionKind.SUPPORT -> it.copy(text = "参考建议：可先休息。")
                else -> it
            }
        }, sources)
    }

    @Test fun everySectionMustCoverExactlyTheConfirmedSources() {
        for (kind in AiSectionKind.entries) {
            invalid(sections().map { if (it.kind == kind) it.copy(sourceIds = setOf(1)) else it })
            invalid(sections().map { if (it.kind == kind) it.copy(sourceIds = setOf(1, 2, 9)) else it })
        }
    }

    @Test fun programGeneratedMoodAndSourceManifestCannotBeRewritten() {
        invalid(sections().map { if (it.kind == AiSectionKind.MOOD) it.copy(text = "看起来你一直很快乐") else it })
        invalid(sections().map { if (it.kind == AiSectionKind.MOMENTS) it.copy(text = "只参考了最后一天") else it })
    }

    @Test fun adviceMustRemainSeparateFromFactsAndDifferentFromEachOther() {
        invalid(sections().map { if (it.kind == AiSectionKind.GROWTH) it.copy(text = it.text.removePrefix("参考建议：")) else it })
        val first = sections().first { it.kind == AiSectionKind.GROWTH }.text
        invalid(sections().map { if (it.kind == AiSectionKind.SUPPORT) it.copy(text = first) else it })
    }

    @Test fun punctuationRepetitionControlsAndExcessiveGeneratedTextAreRejected() {
        for (text in listOf("……。。！！", "哈哈哈哈哈哈哈哈哈哈哈哈", "短", "你好\u0000你在努力完成报告。", "报告仍未完成。".repeat(80))) {
            invalid(sections().map { if (it.kind == AiSectionKind.LIFE) it.copy(text = text) else it })
        }
    }

    @Test fun shortSummaryAndAdviceLimitsAreEnforcedAtTheirExactBoundaries() {
        val summary = "这段时间报告尚未完成，需要继续整理材料。".repeat(8).take(140)
        val advice = "可以先整理已有材料，再列出报告提纲。".repeat(3).take(45)
        val boundary = sections().map {
            when (it.kind) {
                AiSectionKind.LIFE -> it.copy(text = summary)
                AiSectionKind.GROWTH -> it.copy(text = "参考建议：$advice")
                else -> it
            }
        }
        PhoneSummaryResultPolicy.validate(boundary, sources)
        invalid(boundary.map { if (it.kind == AiSectionKind.LIFE) it.copy(text = it.text + "字") else it })
        invalid(boundary.map { if (it.kind == AiSectionKind.GROWTH) it.copy(text = it.text + "字") else it })
    }

    @Test fun providerParserAcceptsOnlyVersionedLocalDigestAndCannotBypassHistoricalExcerptRules() {
        val digest = "0123456789abcdef".repeat(4)
        assertEquals("phone-summary:v1:$digest", PhoneSummaryResultPolicy.provider(digest))
        assertEquals(digest, PhoneSummaryResultPolicy.modelSha256("phone-summary:v1:$digest"))
        for (value in listOf("phone-summary:v1:", "phone-summary:v1:" + "A".repeat(64), "phone-summary:v1:$digest/", "phone-summary:v2:$digest", "phone-offline:$digest")) {
            assertNull(PhoneSummaryResultPolicy.modelSha256(value))
        }
        try { GroundedAiResultPolicy.validate(sections(), sources); fail("New prose bypassed historical excerpt policy") }
        catch (_: AiProtocolException) { }
    }

    @Test fun canonicalInputPreservesAllFieldsAndIsIndependentOfCallerOrdering() {
        val encoded = PhoneSummaryResultPolicy.input(sources)
        assertEquals(encoded, PhoneSummaryResultPolicy.input(sources.reversed()))
        val input = Json.parseToJsonElement(encoded).jsonObject.getValue("sources").jsonArray
        assertEquals(2, input.size)
        assertEquals("\"日记\"", input[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("第一行\n第二行😀", input[0].jsonObject.getValue("body").jsonPrimitive.content)
        assertEquals(3L, input[0].jsonObject.getValue("revision").jsonPrimitive.long)
        assertEquals("2026-10-19", input[0].jsonObject.getValue("date").jsonPrimitive.content)
        assertEquals(JsonNull, input[1].jsonObject.getValue("mood"))
        assertNotEquals(encoded, PhoneSummaryResultPolicy.input(sources.map { if (it.id == 1L) it.copy(revision = 4) else it }))
    }

    private fun invalid(value: List<AiReviewSection>) {
        try { PhoneSummaryResultPolicy.validate(value, sources); fail("Invalid summary accepted") }
        catch (_: AiProtocolException) { }
    }
}
