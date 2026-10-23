package com.example.lifemanager.domain.diary

import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.Instant
import java.time.LocalDate

enum class DiaryMood(val label: String) { HAPPY("开心"), CALM("平静"), NEUTRAL("一般"), LOW("低落"), IRRITATED("烦躁"), TIRED("疲惫") }
data class DiaryMedia(val id: String, val mimeType: String, val bytes: Long, val sha256: String, val position: Int = 0)
data class DiaryEntry(val id: Long, val date: LocalDate, val title: String, val body: String, val mood: DiaryMood?, val media: List<DiaryMedia>, val createdAt: Instant, val updatedAt: Instant, val revision: Long)
data class DiaryDraft(val id: String, val entryId: Long?, val baseRevision: Long?, val date: LocalDate, val title: String, val body: String, val mood: DiaryMood?, val media: List<DiaryMedia>, val createdAt: Instant, val updatedAt: Instant, val revision: Long)
data class DiaryStatistics(val streakDays: Int, val monthEntries: Int, val monthDays: Int)
data class DiaryContentSnapshot(val entries: List<DiaryEntry>, val drafts: List<DiaryDraft>)
data class DiarySourceRevision(val entryId: Long, val revision: Long)
data class DiarySnapshot(val from: LocalDate, val to: LocalDate, val entries: List<DiaryEntry>, val generation: DataGeneration)
data class DiaryReviewSection(val title: String, val text: String, val sourceIds: List<Long>)
data class DiaryReview(val id: String, val from: LocalDate, val to: LocalDate, val createdAt: Instant, val sections: List<DiaryReviewSection>, val sources: List<DiarySourceRevision>, val needsUpdate: Boolean = false, val provider: String = "", val model: String = "", val inputHash: String = "")
data class DiaryBackupSnapshot(val entries: List<DiaryEntry> = emptyList(), val drafts: List<DiaryDraft> = emptyList(), val media: List<DiaryMedia> = emptyList(), val reviews: List<DiaryReview> = emptyList(), val entrySequence: Long = 0)
class DiaryRevisionException : IllegalStateException("内容已变更，请重新打开；未保存的输入仍保留")
