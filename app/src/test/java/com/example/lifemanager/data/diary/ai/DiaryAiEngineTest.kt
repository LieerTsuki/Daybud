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
    private class Phone : PhoneAiRunner {
        var calls = 0
        var onGenerate: () -> Unit = {}
        val batches = mutableListOf<List<AiDiarySource>>()
        override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
            beforeStep(); calls++; batches += sources.toList(); onGenerate()
            val ids = sources.map { it.id }.toSet()
            return listOf(
                AiReviewSection(AiSectionKind.LIFE, "这段时间你留下了生活记录，具体进展可以结合日记回看。", ids),
                AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), ids),
                AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), ids),
                AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以补记一件在意的小事，方便之后回看。", ids),
                AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨先选一个小步骤，按自己的状态安排。", ids),
            )
        }
        override suspend fun probe(config: AiServiceConfig) = Unit
    }
    private fun config() = AiServiceConfig.local("small", "a".repeat(64), 1)
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
        val source = Source(emptyList()); val gateway = Gateway(); val phone = Phone()
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway, phone = phone)
        val preview = engine.preview(day, day)
        try { engine.generate(preview); fail("empty upload") } catch (_: AiNoContentException) { }
        assertEquals(0, gateway.calls)
        assertEquals(0, phone.calls)
    }

    @Test fun sourceEditDuringPhoneGenerationPreventsPublishingOrSaving() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val gateway = Gateway(); val phone = Phone().apply { onGenerate = { source.current = false } }
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway, phone = phone)
        val preview = engine.preview(day, day)
        try { engine.generate(preview); fail("stale result published") } catch (_: AiStaleException) { }
        assertNull(source.saved)
        assertEquals(1, phone.calls); assertEquals(0, gateway.calls)
    }

    @Test fun changedConfigurationBetweenPreviewAndSendStopsUpload() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val gateway = Gateway(); val phone = Phone(); val store = MemoryAiStateStore(config())
        val engine = DiaryAiEngine(source, store, gateway, phone = phone)
        val preview = engine.preview(day, day)
        store.value = AiServiceConfig.local("other-model", "b".repeat(64), 2)
        try { engine.generate(preview); fail("old model generated") } catch (_: AiStaleException) { }
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
    }

    @Test fun checkedResultIsSavedOnlyOnExplicitSave() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, day, "", "散步了", null)))
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), Gateway(), phone = Phone())
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

    @Test fun retiredAutomaticWorkNeverResendsAHistoricalUnknownResult() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "散步了", null)))
        val store = automaticStore(); val gateway = Gateway().apply { failure = AiNetworkException(true, false) }
        val historic = AiAutoJob(AiPeriod(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)),
            1, 1, AiJobState.SENT_UNKNOWN, 1)
        store.jobValues[historic.period.key] = historic
        val phone = Phone(); val engine = DiaryAiEngine(source, store, gateway, automaticClock, phone)
        engine.runAutomatic()
        engine.runAutomatic()
        assertEquals(AiJobState.SENT_UNKNOWN, store.jobValues["WEEK:2026-10-12"]?.state)
        engine.runAutomatic()
        assertEquals(historic, store.jobValues[historic.period.key])
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
        assertNull(source.saved)
    }

    @Test fun retiredAutomaticWorkDoesNotRetryOrRewriteHistoricalHttpRefusal() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "散步了", null)))
        val store = automaticStore(); val gateway = Gateway().apply { failure = AiNetworkException(true, false, true, 401) }
        val historic = AiAutoJob(AiPeriod(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)),
            1, 1, AiJobState.PAUSED, 1)
        store.jobValues[historic.period.key] = historic
        store.consentValues = store.consentValues.map { it.copy(enabled = false) }
        val phone = Phone(); val engine = DiaryAiEngine(source, store, gateway, automaticClock, phone)
        engine.runAutomatic(); engine.runAutomatic(); engine.runAutomatic()
        assertEquals(AiJobState.PAUSED, store.jobValues["WEEK:2026-10-12"]?.state)
        assertEquals(historic, store.jobValues[historic.period.key])
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
        assertTrue(store.consentValues.none { it.enabled })
        assertNull(source.saved)
    }

    @Test fun retiredAutomaticRunsProcessNoPeriodEvenWithOldOptIn() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 7), "", "第一周", null), AiDiarySource(2, 1, LocalDate.of(2026, 10, 14), "", "第二周", null)))
        val store = automaticStore(); val gateway = Gateway()
        val phone = Phone(); val engine = DiaryAiEngine(source, store, gateway, automaticClock, phone)
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        assertTrue(store.jobValues.isEmpty())
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        engine.runAutomatic()
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
        assertNull(source.saved)
    }

    @Test fun revokedHistoricalConsentCannotSchedulePhoneOrCloudWork() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "第二周", null)))
        val store = automaticStore().apply { consentValues = consentValues.map { it.copy(enabledOn = LocalDate.of(2026, 10, 14)) } }
        store.consentValues = store.consentValues.map { it.copy(enabled = false) }
        val gateway = Gateway(); val phone = Phone()
        DiaryAiEngine(source, store, gateway, automaticClock, phone).runAutomatic()
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
        assertTrue(store.jobValues.isEmpty())
        assertNull(source.saved)
    }

    @Test fun replacingDataCannotReuseHistoricalConsentOrReenableRetiredAutomaticWork() = runTest {
        val source = Source(listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 14), "", "替换后的记录", null)))
        val store = automaticStore().apply { consentValues = consentValues.map { it.copy(enabledOn = LocalDate.of(2026, 10, 14)) } }
        val gateway = Gateway()
        val phone = Phone(); val engine = DiaryAiEngine(source, store, gateway, automaticClock, phone)
        source.generation = 2
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        assertTrue(store.jobValues.isEmpty())
        try { engine.setAutomatic(AiPeriodKind.WEEK, true); fail("Retired automation was enabled") }
        catch (_: IllegalStateException) { }
        engine.runAutomatic()
        assertEquals(0, gateway.calls)
        source.entries = listOf(AiDiarySource(2, 1, LocalDate.of(2026, 10, 21), "", "重新授权后这一周", null))
        val next = DiaryAiEngine(source, store, gateway, Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC), phone)
        next.runAutomatic()
        assertEquals(0, gateway.calls); assertEquals(0, phone.calls)
        assertNull(source.saved); assertTrue(store.jobValues.isEmpty())
    }

    @Test fun rangePassesEveryCompleteEntryToOnePhoneCallWithoutCloudPartials() = runTest {
        val entries = (1L..13L).map { AiDiarySource(it, it, day, "第${it}篇", "第${it}篇原文需要完整保留。", null) }
        val source = Source(entries); val gateway = Gateway(); val phone = Phone()
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway, phone = phone)
        val review = engine.generate(engine.preview(day, day))
        assertEquals(listOf(entries), phone.batches)
        assertTrue(gateway.batches.isEmpty()); assertTrue(gateway.summaries.isEmpty())
        assertEquals((1L..13L).toSet(), review.sources.keys)
        assertEquals(0, gateway.calls); assertEquals(1, phone.calls)
    }

    @Test fun longEntriesRemainCompleteAtThePhoneBudgetBoundary() = runTest {
        val entries = (1L..3L).map { AiDiarySource(it, 1, day, "", "字".repeat(6000), null) }
        val source = Source(entries); val gateway = Gateway(); val phone = Phone()
        val engine = DiaryAiEngine(source, MemoryAiStateStore(config()), gateway, phone = phone)
        val review = engine.generate(engine.preview(day, day))
        assertEquals(listOf(entries), phone.batches)
        assertEquals(setOf(1L, 2L, 3L), review.sources.keys)
        assertEquals(0, gateway.calls)
    }
}
