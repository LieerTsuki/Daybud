package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiaryAiEngineTest {
    private val day = LocalDate.of(2026, 10, 19)
    private class Source(var entries: List<AiDiarySource>) : DiaryAiDataSource {
        var current = true
        var generation = 1L
        var saved: AiDiaryReview? = null
        override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(generation, from, through, entries.filter { it.date >= from && it.date <= through })
        override suspend fun isCurrent(snapshot: AiDiarySnapshot) = current
        override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean {
            if (!current) return false
            saved = review
            return true
        }
        override fun observeReviews() = flowOf(listOfNotNull(saved))
        override suspend fun deleteReview(id: String) { saved = null }
    }
    private class Gateway : AiCompletionGateway {
        var calls = 0
        var onSend: () -> Unit = {}
        var beforeGuard: () -> Unit = {}
        var failure: Exception? = null
        val batches = mutableListOf<List<AiDiarySource>>()
        val summaries = mutableListOf<List<AiReviewSection>>()
        override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> {
            beforeGuard()
            beforeSend()
            calls++
            batches += sources.toList(); summaries += partials.toList()
            onSend()
            failure?.let { throw it }
            return AiSectionKind.entries.map { AiReviewSection(it, "今天有生活记录", sources.map { s -> s.id }.toSet()) }
        }
        override suspend fun probe(config: AiServiceConfig) = Unit
    }
    private fun config() = AiServiceConfig.checked("https://a.example/v1", "model", "secret", 1)
    private class MemoryAiStateStore(var value: AiServiceConfig) : AiStateStore {
        var consentValues = emptyList<AiAutoConsent>()
        val jobValues = mutableMapOf<String, AiAutoJob>()
        override suspend fun config() = value
        override suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig {
            value = AiServiceConfig.checked(address, model, key, value.revision + 1)
            return value
        }
        override suspend fun consents() = consentValues
        override suspend fun setConsent(consent: AiAutoConsent) { consentValues = consentValues.filterNot { it.kind == consent.kind } + consent }
        override suspend fun jobs() = jobValues.values.toList()
        override suspend fun putJob(job: AiAutoJob) { jobValues[job.period.key] = job }
        override suspend fun pauseAutomatic() { consentValues = consentValues.map { it.copy(enabled = false) } }
    }

    @Test fun zeroSourcesDoNotCallProvider() = runTest {
        val source = Source(emptyList()); val gateway = Gateway()
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway)
        val preview = engine.preview(day, day)
        try { engine.generate(preview); fail("empty upload") } catch (_: AiNoContentException) { }
        assertEquals(0, gateway.calls)
    }

    @Test fun sourceEditDuringNetworkPreventsPublishingOrSaving() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val gateway = Gateway().apply { onSend = { source.current = false } }
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway)
        val preview = engine.preview(day, day)
        try { engine.generate(preview); fail("stale result published") } catch (_: AiStaleException) { }
        assertNull(source.saved)
    }

    @Test fun changedConfigurationBetweenPreviewAndSendStopsUpload() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val gateway = Gateway(); val store = MemoryAiStateStore(config())
        val engine = DiaryAiEngine(source, store, gateway)
        val preview = engine.preview(day, day)
        store.saveConfig("https://b.example/v1", "model", "new-secret")
        try { engine.generate(preview); fail("old service uploaded") } catch (_: AiStaleException) { }
        assertEquals(0, gateway.calls)
    }

    @Test fun checkedResultIsSavedOnlyOnExplicitSave() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), Gateway())
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        assertNull(source.saved)
        assertTrue(engine.save(review, preview))
        assertEquals(setOf(1L), source.saved!!.sources.keys)
        assertFalse(source.saved!!.inputHash.isBlank())
    }

    private val automaticClock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC)
    private fun automaticStore() = MemoryAiStateStore(config()).apply {
        consentValues = listOf(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 1), 1, "https://a.example", true,1))
    }

    @Test fun automaticUnknownSentResultIsDurableAndNeverResent() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "散步了", null)))
        val store = automaticStore(); val gateway = Gateway().apply { failure = AiNetworkException(true, false) }
        val engine = DiaryAiEngine(source, store, gateway, automaticClock)
        engine.runAutomatic() // older of the two periods is empty
        engine.runAutomatic()
        assertEquals(AiJobState.SENT_UNKNOWN, store.jobValues["WEEK:2026-10-12"]?.state)
        engine.runAutomatic()
        assertEquals(1, gateway.calls)
        assertNull(source.saved)
    }

    @Test fun eachAutomaticRunProcessesOnlyOnePeriodAndHonorsOptInFloor() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 7), "", "第一周", null), AiDiarySource(2, 1, LocalDate.of(2026, 10, 14), "", "第二周", null)))
        val store = automaticStore(); val gateway = Gateway()
        val engine = DiaryAiEngine(source, store, gateway, automaticClock)
        engine.runAutomatic()
        assertEquals(1, gateway.calls)
        assertEquals(setOf("WEEK:2026-10-05"), store.jobValues.keys)
        engine.runAutomatic()
        assertEquals(2, gateway.calls)
        engine.runAutomatic()
        assertEquals(2, gateway.calls)
    }

    @Test fun revocationAtActualSendBoundaryPreventsAnyUpload() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "第二周", null)))
        val store = automaticStore().apply { consentValues = consentValues.map { it.copy(enabledOn = LocalDate.of(2026, 10, 14)) } }
        val gateway = Gateway().apply { beforeGuard = { store.consentValues = store.consentValues.map { it.copy(enabled = false) } } }
        DiaryAiEngine(source, store, gateway, automaticClock).runAutomatic()
        assertEquals(0, gateway.calls)
        assertNull(source.saved)
    }

    @Test fun replacingDataCannotReuseAutomaticConsentAndReenablingStartsAtCurrentPeriod() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "替换后的记录", null)))
        val store = automaticStore().apply { consentValues = consentValues.map { it.copy(enabledOn = LocalDate.of(2026, 10, 14)) } }
        val gateway = Gateway()
        val engine = DiaryAiEngine(source, store, gateway, automaticClock)
        source.generation = 2
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        assertTrue(store.jobValues.isEmpty())
        engine.setAutomatic(AiPeriodKind.WEEK, true)
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        source.entries = listOf(AiDiarySource(2, 1, LocalDate.of(2026, 10, 21), "", "重新授权后这一周", null))
        val next = DiaryAiEngine(source, store, gateway, Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC))
        next.runAutomatic()
        assertEquals(1, gateway.calls)
        assertEquals(setOf(2L), source.saved!!.sources.keys)
    }

    @Test fun longRangeSummarizesEveryCompleteEntryThenCombinesAllPartials() = runTest {
        val entries = (1L..3L).map { AiDiarySource(it, 1, day, "", "字".repeat(6000), null) }
        val source = Source(entries); val gateway = Gateway()
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway)
        val review = engine.generate(engine.preview(day, day))
        assertEquals(entries, gateway.batches.dropLast(1).flatten())
        assertEquals(15, gateway.summaries.last().size)
        assertTrue(gateway.batches.last().all { it.body.isEmpty() })
        assertEquals(setOf(1L, 2L, 3L), review.sources.keys)
    }
}
