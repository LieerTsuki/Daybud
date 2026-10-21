package com.example.lifemanager.domain.diary.ai

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GroundedAiResultPolicyTest {
    private val first = AiDiarySource(1, 3, LocalDate.of(2026, 10, 19), "就医记录", "今天确诊了感冒。医生提醒多休息。", "平静")
    private val second = AiDiarySource(2, 5, first.date.plusDays(1), "计划", "准备明天读书。", null)
    private val prefix = "AI摘选原文（逐字摘录，分类仅供回看）：\n"
    private fun valid(sources: List<AiDiarySource> = listOf(first, second)): List<AiReviewSection> {
        val ids = sources.map { it.id }.toSet()
        val life = prefix + sources.joinToString("\n") { source ->
            val (field, raw) = when {
                source.body.isNotBlank() -> "正文" to source.body
                source.title.isNotBlank() -> "标题" to source.title
                else -> "心情标签" to source.mood.orEmpty()
            }
            val quote = raw.substring(0, raw.offsetByCodePoints(0, minOf(64, raw.codePointCount(0, raw.length)))).trim()
            "${source.date} #${source.id} $field：“$quote”"
        }
        return listOf(
            AiReviewSection(AiSectionKind.LIFE, life, ids),
            AiReviewSection(AiSectionKind.MOOD, "已填写的心情标签（不推测正文中的情绪）：\n" + sources.joinToString("\n") {
                "${it.date} #${it.id}：${it.mood?.takeIf(String::isNotBlank) ?: "未填写"}"
            }, ids),
            AiReviewSection(AiSectionKind.MOMENTS, "本次AI未选出特别时刻原文片段；这不代表原文中不存在，可查看来源。", ids),
            AiReviewSection(AiSectionKind.GROWTH, "本次AI未选出反思原文片段；这不代表原文中不存在，可查看来源。", ids),
            AiReviewSection(AiSectionKind.SUPPORT, "本次AI未选出给自己的提醒原文片段；这不代表原文中不存在，可查看来源。", ids),
        )
    }
    private fun replacing(kind: AiSectionKind, text: String, ids: Set<Long>? = null) = valid().map {
        if (it.kind == kind) it.copy(text = text, sourceIds = ids ?: it.sourceIds) else it
    }
    private fun rejects(sections: List<AiReviewSection>, sources: List<AiDiarySource> = listOf(first, second)) =
        assertThrows(AiProtocolException::class.java) { GroundedAiResultPolicy.validate(sections, sources) }

    @Test fun medicalOriginalIsAcceptedButCloudProsePolicyRemainsStrict() {
        GroundedAiResultPolicy.validate(valid(), listOf(first, second))
        assertThrows(AiProtocolException::class.java) { AiResultPolicy.validate(valid(), setOf(1, 2)) }
    }
    @Test fun titleAndMoodOnlyQuotesAreCheckedAgainstTheirOwnFields() {
        val title = first.copy(body = "", mood = null)
        val mood = second.copy(title = "", body = "", mood = "轻松")
        GroundedAiResultPolicy.validate(valid(listOf(title, mood)), listOf(title, mood))
        rejects(replacing(AiSectionKind.LIFE, prefix + "${first.date} #1 标题：“今天确诊了感冒。”", setOf(1)))
    }
    @Test fun inventedProseOrWrongEnvelopeCannotPass() {
        rejects(replacing(AiSectionKind.LIFE, "今天获得比赛冠军。"))
        rejects(replacing(AiSectionKind.LIFE, valid().first().text.replace("逐字摘录", "保留原意")))
        rejects(replacing(AiSectionKind.LIFE, valid().first().text + "\n以上体现了成长。"))
    }
    @Test fun continuousSubstringIsRequiredAndCannotCrossFieldsOrSources() {
        rejects(replacing(AiSectionKind.LIFE, valid().first().text.replace("今天确诊了感冒。医生提醒多休息。", "今天确诊了感冒。准备明天读书。")))
        rejects(replacing(AiSectionKind.LIFE, valid().first().text.replace("今天确诊了感冒。医生提醒多休息。", "今天感冒。")))
        rejects(replacing(AiSectionKind.LIFE, valid().first().text.replace("#1 正文", "#2 正文")))
        rejects(replacing(AiSectionKind.LIFE, valid().first().text.replace(first.date.toString(), "2026-10-17")))
    }
    @Test fun actualLineIdsMustEqualCitationsAndLifeMustCoverEverySource() {
        val oneLine = prefix + "${first.date} #1 正文：“今天确诊了感冒。”"
        rejects(replacing(AiSectionKind.LIFE, oneLine, setOf(1)))
        rejects(replacing(AiSectionKind.MOMENTS, oneLine, setOf(2)))
        rejects(replacing(AiSectionKind.MOMENTS, oneLine + "\n${first.date} #1 正文：“医生提醒多休息。”", setOf(1)))
        GroundedAiResultPolicy.validate(replacing(AiSectionKind.MOMENTS, oneLine, setOf(1)), listOf(first, second))
    }
    @Test fun emptySelectionNoticeMustBeExactAndReferToAllAvailableSources() {
        val notice = valid().first { it.kind == AiSectionKind.GROWTH }.text
        rejects(replacing(AiSectionKind.GROWTH, notice + "今天变成熟了。"))
        rejects(replacing(AiSectionKind.GROWTH, notice, setOf(1)))
        rejects(replacing(AiSectionKind.LIFE, "本次AI未选出生活原文片段；这不代表原文中不存在，可查看来源。"))
    }
    @Test fun moodIsRebuiltExactlyIncludingMissingAndWhitespaceLabels() {
        val mood = valid().first { it.kind == AiSectionKind.MOOD }.text
        rejects(replacing(AiSectionKind.MOOD, mood.replace("未填写", "开心")))
        rejects(replacing(AiSectionKind.MOOD, mood, setOf(1)))
        val source = first.copy(mood = "  ")
        GroundedAiResultPolicy.validate(valid(listOf(source)), listOf(source))
    }
    @Test fun quoteLimitUsesUnicodeCodePointsAndRejectsLongOrBlankSpans() {
        val source = first.copy(body = "🙂".repeat(65), mood = null)
        GroundedAiResultPolicy.validate(valid(listOf(source)), listOf(source))
        val tooLong = valid(listOf(source)).map { if (it.kind == AiSectionKind.LIFE) it.copy(text = prefix + "${source.date} #1 正文：“${source.body}”") else it }
        rejects(tooLong, listOf(source))
        rejects(replacing(AiSectionKind.LIFE, prefix + "${first.date} #1 正文：“ ”"))
    }
    @Test fun structuralAndDuplicateSourceChecksStillApply() {
        rejects(valid().dropLast(1))
        rejects(valid(), listOf(first, first))
        rejects(valid().map { if (it.kind == AiSectionKind.SUPPORT) it.copy(kind = AiSectionKind.LIFE) else it })
    }
}
