package com.example.lifemanager.domain.diary.ai

import kotlinx.serialization.json.*

/** Validates the deterministic envelope. Factual and advice quality still require evaluation. */
object PhoneSummaryResultPolicy {
    const val PROVIDER_PREFIX = "phone-summary:v1:"
    const val ADVICE_PREFIX = "参考建议："
    const val MAX_SOURCES = 16
    const val MAX_SUMMARY_POINTS = 140
    const val MAX_ADVICE_POINTS = 45
    private val digest = Regex("[a-f0-9]{64}")
    private val order = compareBy<AiDiarySource>({ it.date }, { it.id })

    fun provider(sha256: String): String {
        require(digest.matches(sha256)) { "离线模型摘要无效" }
        return PROVIDER_PREFIX + sha256
    }

    fun modelSha256(provider: String): String? = provider.takeIf { it.startsWith(PROVIDER_PREFIX) }
        ?.removePrefix(PROVIDER_PREFIX)?.takeIf(digest::matches)

    fun input(sources: List<AiDiarySource>): String {
        checkSources(sources)
        return buildJsonObject {
            put("protocol", "phone-summary:v1")
            put("sources", buildJsonArray {
                sources.sortedWith(order).forEach { source -> add(buildJsonObject {
                    put("sourceId", source.id)
                    put("revision", source.revision)
                    put("date", source.date.toString())
                    put("title", source.title)
                    put("body", source.body)
                    put("mood", source.mood?.let(::JsonPrimitive) ?: JsonNull)
                }) }
            })
        }.toString()
    }

    fun moodText(sources: List<AiDiarySource>): String = "已填写的心情标签（不推测正文中的情绪）：\n" +
        sources.sortedWith(order).joinToString("\n") { "${it.date} #${it.id}：${it.mood?.takeIf(String::isNotBlank) ?: "未填写"}" }

    fun sourceText(sources: List<AiDiarySource>): String = "本次回顾来源（可打开原始日记核对）：\n" +
        sources.sortedWith(order).joinToString("\n") { "${it.date} #${it.id}" }

    fun validate(sections: List<AiReviewSection>, sources: List<AiDiarySource>) {
        checkSources(sources)
        val ids = sources.map { it.id }.toSet()
        AiResultPolicy.validate(sections, ids)
        if (sections.any { it.sourceIds != ids }) invalid()
        val byKind = sections.associateBy { it.kind }
        if (byKind.getValue(AiSectionKind.MOOD).text != moodText(sources) ||
            byKind.getValue(AiSectionKind.MOMENTS).text != sourceText(sources)) invalid()
        generated(byKind.getValue(AiSectionKind.LIFE).text, MAX_SUMMARY_POINTS)
        val advice = listOf(AiSectionKind.GROWTH, AiSectionKind.SUPPORT).map { kind ->
            val text = byKind.getValue(kind).text
            if (!text.startsWith(ADVICE_PREFIX)) invalid()
            text.removePrefix(ADVICE_PREFIX).also { generated(it, MAX_ADVICE_POINTS) }
        }
        if (advice[0] == advice[1]) invalid()
    }

    private fun generated(text: String, maximum: Int) {
        if (text.isBlank() || text != text.trim() || points(text) > maximum || text.any { it.isISOControl() } ||
            text.codePoints().anyMatch { it in 0xD800..0xDFFF }) invalid()
        val han = text.codePoints().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }.toArray()
        if (han.size < 4 || han.toSet().size < 4) invalid()
    }

    private fun checkSources(sources: List<AiDiarySource>) {
        if (sources.isEmpty() || sources.size > MAX_SOURCES || sources.any { it.id <= 0 || it.revision <= 0 || points(it.mood.orEmpty()) > 64 } ||
            sources.map { it.id }.distinct().size != sources.size) invalid()
    }

    private fun points(text: String) = text.codePointCount(0, text.length)
    private fun invalid(): Nothing = throw AiProtocolException()
}
