package com.example.lifemanager.domain.diary

import java.time.LocalDate
import java.time.YearMonth

object DiaryRules {
    const val MAX_ATTACHMENTS = 20
    const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 500L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 1024L * 1024 * 1024
    fun ordered(entries: List<DiaryEntry>): List<DiaryEntry> = entries.sortedWith(compareByDescending<DiaryEntry> { it.createdAt }.thenByDescending { it.id })
    fun statistics(entries: List<DiaryEntry>, today: LocalDate): DiaryStatistics {
        val dates = entries.map { it.date }.toSet()
        var day = if (today in dates) today else today.minusDays(1)
        var streak = 0
        while (day in dates) { streak++; day = day.minusDays(1) }
        val month = entries.filter { YearMonth.from(it.date) == YearMonth.from(today) }
        return DiaryStatistics(streak, month.size, month.map { it.date }.distinct().size)
    }
    fun onThisDay(entries: List<DiaryEntry>, today: LocalDate): List<DiaryEntry> = ordered(entries.filter { it.date.year < today.year && it.date.month == today.month && it.date.dayOfMonth == today.dayOfMonth })
    fun validateContent(date: LocalDate, body: String, media: List<DiaryMedia>, today: LocalDate) {
        require(!date.isAfter(today)) { "日记日期不能晚于今天" }
        require(body.isNotBlank() || media.isNotEmpty()) { "请填写正文或添加附件" }
        validateMedia(media)
    }
    fun validateMedia(media: List<DiaryMedia>) {
        require(media.size <= MAX_ATTACHMENTS && media.map { it.id }.distinct().size == media.size) { "附件数量无效" }
        var bytes = 0L
        media.forEach {
            require(it.id.matches(Regex("[a-f0-9-]{36}"))) { "附件标识无效" }
            val maximum = mediaLimit(it.mimeType)
            require(it.bytes in 1..maximum && it.sha256.matches(Regex("[a-f0-9]{64}")) && it.position >= 0) { "附件元数据无效" }
            require(it.bytes <= MAX_ENTRY_BYTES - bytes) { "每篇附件不能超过1GiB" }
            bytes += it.bytes
        }
    }
    fun mediaLimit(mimeType: String): Long = when (mimeType) {
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/avif", "image/bmp" -> MAX_IMAGE_BYTES
        "video/mp4", "video/webm", "video/3gpp", "video/3gpp2", "video/quicktime", "video/x-matroska" -> MAX_VIDEO_BYTES
        else -> throw IllegalArgumentException("不支持的图片或视频类型")
    }
}
