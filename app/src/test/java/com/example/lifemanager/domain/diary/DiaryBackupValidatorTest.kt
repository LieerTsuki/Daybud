package com.example.lifemanager.domain.diary

import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class DiaryBackupValidatorTest {
    private val date=LocalDate.parse("2026-10-19")
    private val entry=DiaryEntry(1,date,"标题","正文",null,emptyList(),Instant.EPOCH,Instant.EPOCH,2)
    @Test fun unknownDuplicateOrIncompleteReviewSectionsCannotEnterRestoredData() {
        val sections=listOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语").map { DiaryReviewSection(it,"真实回顾",listOf(1)) }
        val review=DiaryReview("review",date,date,Instant.EPOCH,sections,listOf(DiarySourceRevision(1,2)))
        fun validate(parts:List<DiaryReviewSection>)=DiaryBackupValidator.validate(DiaryBackupSnapshot(entries=listOf(entry),reviews=listOf(review.copy(sections=parts)),entrySequence=1))
        validate(sections)
        assertFailsWith<IllegalArgumentException> { validate(sections.drop(1)+sections.first().copy(title="未知段落")) }
        assertFailsWith<IllegalArgumentException> { validate(sections.dropLast(1)+sections.first()) }
        assertFailsWith<IllegalArgumentException> { validate(sections.dropLast(1)) }
    }
    @Test fun outdatedReviewRemainsBackedUpAfterSourceDateMovesOutsideOriginalRange() {
        val review=DiaryReview("review",date.minusDays(7),date.minusDays(1),Instant.EPOCH,listOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语").map { DiaryReviewSection(it,"旧回顾",listOf(1)) },listOf(DiarySourceRevision(1,1)),needsUpdate=true)
        DiaryBackupValidator.validate(DiaryBackupSnapshot(entries=listOf(entry),reviews=listOf(review),entrySequence=1))
    }
    @Test fun unmarkedStaleReviewAndMissingMediaAreRejected() {
        val review=DiaryReview("review",date,date,Instant.EPOCH,listOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语").map { DiaryReviewSection(it,"旧回顾",listOf(1)) },listOf(DiarySourceRevision(1,1)))
        assertFailsWith<IllegalArgumentException> { DiaryBackupValidator.validate(DiaryBackupSnapshot(entries=listOf(entry),reviews=listOf(review),entrySequence=1)) }
        val media=DiaryMedia("12345678-1234-1234-1234-123456789012","image/jpeg",3,"0".repeat(64))
        assertFailsWith<IllegalArgumentException> { DiaryBackupValidator.validate(DiaryBackupSnapshot(entries=listOf(entry.copy(media=listOf(media))),entrySequence=1)) }
    }
}
