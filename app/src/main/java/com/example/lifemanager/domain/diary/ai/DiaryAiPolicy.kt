package com.example.lifemanager.domain.diary.ai

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AiInputPlan(val chunks: List<List<AiDiarySource>>, val includedCount: Int, val excludedCount: Int, val limitedEvidence: Boolean)
object AiInputPolicy {
    fun plan(sources: List<AiDiarySource>, maxChunkChars: Int = 12_000, maxTotalChars: Int = 60_000, maxChunks: Int = 6): AiInputPlan {
        require(maxChunkChars > 0 && maxTotalChars > 0 && maxChunks > 0)
        require(sources.map { it.id }.distinct().size == sources.size) { "日记来源重复" }
        val included = sources.filter { it.body.isNotBlank() || !it.mood.isNullOrBlank() ||
            (it.title.isNotBlank() && !isGeneratedTitle(it.title)) }
        val chunks = mutableListOf<List<AiDiarySource>>()
        var chunk = mutableListOf<AiDiarySource>(); var chunkSize = 0; var total = 0
        included.forEach { source ->
            val size = source.title.length.toLong() + source.body.length + (source.mood?.length ?: 0) + 64
            if (size > maxChunkChars || total.toLong() + size > maxTotalChars) throw AiBudgetException()
            if (chunkSize + size > maxChunkChars && chunk.isNotEmpty()) { chunks += chunk.toList(); chunk = mutableListOf(); chunkSize = 0 }
            chunk += source; chunkSize += size.toInt(); total += size.toInt()
        }
        if (chunk.isNotEmpty()) chunks += chunk.toList()
        if (chunks.size > maxChunks) throw AiBudgetException()
        return AiInputPlan(chunks, included.size, sources.size - included.size, included.size < 3)
    }
    private fun isGeneratedTitle(title:String):Boolean {
        val value=title.trim()
        return value.endsWith(" 日记") && runCatching { LocalDate.parse(value.removeSuffix(" 日记")) }.isSuccess
    }
}

object AiResultPolicy {
    private val prohibited = listOf("你患有", "你患了", "诊断为", "确诊", "你有抑郁症", "你有焦虑症", "你是坏人", "你真没用")
    fun validate(sections: List<AiReviewSection>, sourceIds: Set<Long>) {
        if (sections.size != 5 || sections.map { it.kind }.toSet() != AiSectionKind.entries.toSet()) throw AiProtocolException()
        if (sections.sumOf { it.text.length } > 12_000) throw AiProtocolException()
        sections.forEach {
            if (it.text.isBlank() || it.text.length > 3000 || it.sourceIds.any { id -> id !in sourceIds } ||
                (it.kind != AiSectionKind.SUPPORT && it.sourceIds.isEmpty()) || prohibited.any { phrase -> phrase in it.text }) throw AiProtocolException()
        }
    }
}

object AiSchedulePolicy {
    fun recentCompleted(consent: AiAutoConsent, now: LocalDateTime): List<AiPeriod> {
        if (!consent.enabled || now.hour < 9) return emptyList()
        fun floor(date: java.time.LocalDate) = when (consent.kind) {
            AiPeriodKind.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            AiPeriodKind.MONTH -> date.withDayOfMonth(1)
        }
        val current = floor(now.toLocalDate()); val start = floor(consent.enabledOn)
        return (2L downTo 1L).map { offset ->
            val from = if (consent.kind == AiPeriodKind.WEEK) current.minusWeeks(offset) else current.minusMonths(offset)
            val next = if (consent.kind == AiPeriodKind.WEEK) from.plusWeeks(1) else from.plusMonths(1)
            AiPeriod(consent.kind, from, next.minusDays(1))
        }.filter { !it.from.isBefore(start) }
    }
    fun mayAttempt(state: AiJobState, attempts: Int) = state == AiJobState.RETRYABLE_UNSENT && attempts < 2
}
