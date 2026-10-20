package com.example.lifemanager.domain.diary

/** Shared boundary for Room snapshots and untrusted format4 manifests. Missing physical files remain metadata. */
object DiaryBackupValidator {
    private val sectionTitles=setOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语")
    fun validateReviewSections(review:DiaryReview) {
        require(review.sections.size==5 && review.sections.map { it.title }.toSet()==sectionTitles) { "回顾必须包含五个唯一的已知段落" }
        require(review.sections.sumOf { it.text.length.toLong() }<=12_000) { "回顾内容超限" }
        val ids=review.sources.map { it.entryId }.toSet()
        review.sections.forEach {
            require(it.text.isNotBlank() && it.text.length<=3000 && it.sourceIds.distinct().size==it.sourceIds.size &&
                it.sourceIds.all { id -> id in ids } && (it.title=="温暖寄语" || it.sourceIds.isNotEmpty())) { "回顾段落来源无效" }
        }
    }
    fun validate(snapshot: DiaryBackupSnapshot) {
        fun <T> unique(values: List<T>) = require(values.distinct().size == values.size) { "日记备份包含重复标识" }
        unique(snapshot.entries.map { it.id }); unique(snapshot.drafts.map { it.id })
        unique(snapshot.media.map { it.id }); unique(snapshot.reviews.map { it.id })
        val entries = snapshot.entries.associateBy { it.id }
        val media = snapshot.media.associateBy { it.id }
        require(snapshot.entrySequence >= (entries.keys.maxOrNull() ?: 0L)) { "日记序列无效" }
        snapshot.media.forEach { DiaryRules.validateMedia(listOf(it)) }
        fun links(items: List<DiaryMedia>) {
            DiaryRules.validateMedia(items)
            require(items.map { it.position } == items.indices.toList()) { "附件顺序无效" }
            items.forEach { require(media[it.id]?.copy(position=it.position) == it) { "附件引用缺失或元数据不一致" } }
        }
        snapshot.entries.forEach {
            require(it.id > 0 && it.revision > 0 && it.title.isNotBlank()) { "正式日记字段无效" }
            require(it.body.isNotBlank() || it.media.isNotEmpty()) { "正式日记内容为空" }
            require(it.updatedAt >= it.createdAt) { "日记时间无效" }
            links(it.media)
        }
        snapshot.drafts.forEach {
            require(it.id.isNotBlank() && it.revision > 0 && it.updatedAt >= it.createdAt) { "草稿字段无效" }
            require((it.entryId == null) == (it.baseRevision == null)) { "草稿来源无效" }
            if (it.entryId != null) require(it.entryId in entries && requireNotNull(it.baseRevision) > 0 && it.baseRevision <= entries.getValue(it.entryId).revision) { "草稿来源不存在或修订无效" }
            links(it.media)
        }
        val referenced = (snapshot.entries.flatMap { it.media } + snapshot.drafts.flatMap { it.media }).map { it.id }.toSet()
        require(referenced == media.keys) { "备份包含无引用附件" }
        snapshot.reviews.forEach { review ->
            validateReviewSections(review)
            require(review.id.isNotBlank() && review.from <= review.to && review.sources.isNotEmpty() && review.sections.isNotEmpty()) { "回顾字段无效" }
            unique(review.sources.map { it.entryId })
            val ids = review.sources.map { source ->
                val entry = entries[source.entryId]
                require(entry != null && source.revision > 0 && source.revision <= entry.revision && (review.needsUpdate || entry.date in review.from..review.to)) { "回顾来源无效" }
                require(review.needsUpdate || source.revision == entry.revision) { "变更来源未标记" }
                source.entryId
            }.toSet()
            review.sections.forEach { require(it.title.isNotBlank() && it.text.isNotBlank() && it.sourceIds.all { id -> id in ids }) { "回顾段落来源无效" } }
        }
    }
}
