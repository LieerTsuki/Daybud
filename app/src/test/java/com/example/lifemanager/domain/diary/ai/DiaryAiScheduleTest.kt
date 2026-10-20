package com.example.lifemanager.domain.diary.ai

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class DiaryAiScheduleTest {
    @Test fun weeklyPeriodsRespectMondayBoundaryAndOptInFloor() {
        val consent = AiAutoConsent(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 14), 3, "https://a.example", true)
        val periods = AiSchedulePolicy.recentCompleted(consent, LocalDateTime.of(2026, 10, 26, 9, 0))
        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)), periods.map { it.from })
        assertEquals(LocalDate.of(2026, 10, 25), periods.last().through)
    }

    @Test fun longOfflineOnlyOffersTwoRecentPeriodsAndNotBeforeNine() {
        val consent = AiAutoConsent(AiPeriodKind.MONTH, LocalDate.of(2025, 1, 5), 1, "https://a.example", true)
        assertEquals(listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)), AiSchedulePolicy.recentCompleted(consent, LocalDateTime.of(2026, 10, 19, 9, 0)).map { it.from })
        assertTrue(AiSchedulePolicy.recentCompleted(consent, LocalDateTime.of(2026, 10, 1, 8, 59)).isEmpty())
    }

    @Test fun unknownSentAndTwoAttemptsNeverResend() {
        assertFalse(AiSchedulePolicy.mayAttempt(AiJobState.SENT_UNKNOWN, 1))
        assertFalse(AiSchedulePolicy.mayAttempt(AiJobState.RETRYABLE_UNSENT, 2))
        assertTrue(AiSchedulePolicy.mayAttempt(AiJobState.RETRYABLE_UNSENT, 1))
        assertFalse(AiSchedulePolicy.mayAttempt(AiJobState.SUCCEEDED, 1))
    }

    @Test fun revokedOrChangedServiceConsentHasNoEligiblePeriods() {
        val consent = AiAutoConsent(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 1), 1, "https://a.example", false)
        assertTrue(AiSchedulePolicy.recentCompleted(consent, LocalDateTime.of(2026, 10, 19, 10, 0)).isEmpty())
    }
}
