package com.example.lifemanager.domain.diary.ai

/** Accepts only the phone excerpt envelope whose contents can be proved from this snapshot. */
object GroundedAiResultPolicy {
    const val MAX_SOURCES = 16
    const val MAX_SPAN_CODE_POINTS = 64
    const val EXCERPT_PREFIX = "AI摘选原文（逐字摘录，分类仅供回看）：\n"
    const val MOOD_PREFIX = "已填写的心情标签（不推测正文中的情绪）：\n"

    fun moodText(sources: List<AiDiarySource>): String = MOOD_PREFIX + sources.joinToString("\n") {
        "${it.date} #${it.id}：${it.mood?.takeIf(String::isNotBlank) ?: "未填写"}"
    }

    fun emptySelectionText(kind: AiSectionKind): String {
        val name = when (kind) {
            AiSectionKind.LIFE -> "生活"
            AiSectionKind.MOMENTS -> "特别时刻"
            AiSectionKind.GROWTH -> "反思"
            AiSectionKind.SUPPORT -> "给自己的提醒"
            AiSectionKind.MOOD -> invalid()
        }
        return "本次AI未选出${name}原文片段；这不代表原文中不存在，可查看来源。"
    }

    fun validate(sections: List<AiReviewSection>, sources: List<AiDiarySource>) {
        if (sources.isEmpty() || sources.size > MAX_SOURCES || sources.any { it.id <= 0 || points(it.mood.orEmpty()) > MAX_SPAN_CODE_POINTS }) invalid()
        val byId = sources.associateBy { it.id }
        if (byId.size != sources.size) invalid()
        val ids = byId.keys
        AiResultPolicy.validateStructure(sections, ids)
        sections.forEach { section ->
            if (section.kind == AiSectionKind.MOOD) {
                if (section.text != moodText(sources) || section.sourceIds != ids) invalid()
            } else if (section.text == emptySelectionText(section.kind)) {
                if (section.kind == AiSectionKind.LIFE || section.sourceIds != ids) invalid()
            } else {
                if (!section.text.startsWith(EXCERPT_PREFIX)) invalid()
                val cited = linkedSetOf<Long>()
                section.text.removePrefix(EXCERPT_PREFIX).split('\n').forEach { line ->
                    val source = sources.singleOrNull { line.startsWith("${it.date} #${it.id} ") } ?: invalid()
                    if (!cited.add(source.id)) invalid()
                    val rest = line.removePrefix("${source.date} #${source.id} ")
                    val fields = mapOf("标题" to source.title, "正文" to source.body, "心情标签" to source.mood.orEmpty())
                    val field = fields.keys.singleOrNull { rest.startsWith("$it：“") && rest.endsWith("”") } ?: invalid()
                    val quote = rest.removePrefix("$field：“").dropLast(1)
                    if (quote.isBlank() || quote != quote.trim() || points(quote) > MAX_SPAN_CODE_POINTS || !fields.getValue(field).contains(quote)) invalid()
                    if (field == "心情标签" && (source.title.isNotBlank() || source.body.isNotBlank())) invalid()
                }
                if (section.sourceIds != cited || section.kind == AiSectionKind.LIFE && cited != ids) invalid()
            }
        }
    }

    private fun points(text: String) = text.codePointCount(0, text.length)
    private fun invalid(): Nothing = throw AiProtocolException()
}
