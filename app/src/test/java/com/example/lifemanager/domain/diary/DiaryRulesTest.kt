package com.example.lifemanager.domain.diary

import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class DiaryRulesTest {
    private fun entry(id: Long, date: String, created: Long = id, updated: Long = created) =
        DiaryEntry(id, LocalDate.parse(date), "", "正文", null, emptyList(), Instant.ofEpochMilli(created), Instant.ofEpochMilli(updated), 1)

    @Test fun editedOldEntryKeepsCreationOrderAndTiesUseDescendingId() {
        val entries = listOf(entry(1,"2026-10-19", 1, 999), entry(2,"2026-10-19", 2), entry(3,"2026-10-19", 2))
        assertEquals(listOf(3L, 2L, 1L), DiaryRules.ordered(entries).map { it.id })
    }

    @Test fun streakCountsDistinctCivilDatesAndMayEndYesterday() {
        val entries = listOf(entry(1,"2026-10-18"),entry(2,"2026-10-18"),entry(3,"2026-10-17"),entry(4,"2026-09-30"))
        assertEquals(DiaryStatistics(2,3,2), DiaryRules.statistics(entries,LocalDate.parse("2026-10-19")))
        assertEquals(0, DiaryRules.statistics(entries,LocalDate.parse("2026-10-20")).streakDays)
    }

    @Test fun leapDayMemoryDoesNotBorrowFebruary28OrCurrentYear() {
        val entries = listOf(entry(1,"2024-02-29"),entry(2,"2023-02-28"),entry(3,"2028-02-29"))
        assertEquals(listOf(1L), DiaryRules.onThisDay(entries,LocalDate.parse("2028-02-29")).map { it.id })
    }

    @Test fun completionRejectsFutureDateAndWhitespaceOnlyContent() {
        assertFailsWith<IllegalArgumentException> { DiaryRules.validateContent(LocalDate.parse("2026-10-20"), "内容", emptyList(), LocalDate.parse("2026-10-19")) }
        assertFailsWith<IllegalArgumentException> { DiaryRules.validateContent(LocalDate.parse("2026-10-19"), " \n ", emptyList(), LocalDate.parse("2026-10-19")) }
    }
}
