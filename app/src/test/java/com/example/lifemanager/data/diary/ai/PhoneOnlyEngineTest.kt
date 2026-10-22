package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.time.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneOnlyEngineTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val sha = "a".repeat(64)
    private class Source(var entries: List<AiDiarySource>) : DiaryAiDataSource {
        var current = true
        var saved: AiDiaryReview? = null
        override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through, entries.filter { it.date >= from && it.date <= through })
        override suspend fun isCurrent(snapshot: AiDiarySnapshot) = current && snapshot.sources == entries.filter { it.date >= snapshot.from && it.date <= snapshot.through }
        override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean {
            if (!isCurrent(snapshot)) return false
            saved = review
            return true
        }
        override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
        override suspend fun deleteReview(id: String) = Unit
    }
    private class Store(var value: AiServiceConfig) : AiStateStore {
        val scheduled = mutableMapOf<String, AiAutoJob>()
        override suspend fun config() = value
        override suspend fun saveConfig(address: String, model: String, key: String) = error("retired")
        override suspend fun consents() = listOf(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 1), value.revision, value.origin, true, 1))
        override suspend fun setConsent(consent: AiAutoConsent) = Unit
        override suspend fun jobs() = scheduled.values.toList()
        override suspend fun putJob(job: AiAutoJob) { scheduled[job.period.key] = job }
        override suspend fun pauseAutomatic() = Unit
    }
    private class Cloud : AiCompletionGateway {
        var calls = 0
        override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> {
            beforeSend(); calls++
            return AiSectionKind.entries.map { AiReviewSection(it, "云端不应再被调用", sources.map { s -> s.id }.toSet()) }
        }
        override suspend fun probe(config: AiServiceConfig) { calls++ }
    }
    private class Phone : PhoneAiRunner {
        var after: () -> Unit = {}
        var calls = 0
        override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
            beforeStep(); calls++; after()
            val ids = sources.map { it.id }.toSet()
            return listOf(
                AiReviewSection(AiSectionKind.LIFE, "你记录了散步和学习安排。", ids),
                AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), ids),
                AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), ids),
                AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以先选一个小任务完成。", ids),
                AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨为散步留出时间。", ids),
            )
        }
        override suspend fun probe(config: AiServiceConfig) { calls++ }
    }
    private fun source() = Source(listOf(AiDiarySource(1, 1, day, "安排", "散步后准备学习。", "平静")))
    private fun local() = AiServiceConfig.local("small", sha, 1)
    private fun cloud() = AiServiceConfig.checked("https://retired.example", "model", "synthetic", 1)

    @Test fun legacyCloudConfigurationCannotCreateANewPreview() = runTest {
        val network = Cloud()
        val engine = DiaryAiEngine(source(), Store(cloud()), network, clock, Phone())
        try { engine.preview(day, day); fail("retired cloud preview accepted") } catch (_: IllegalStateException) { }
        assertEquals(0, network.calls)
    }
    @Test fun legacyCloudConfigurationCannotProbeANetworkService() = runTest {
        val network = Cloud()
        val engine = DiaryAiEngine(source(), Store(cloud()), network, clock)
        try { engine.testConnection(); fail("retired cloud probe accepted") } catch (_: IllegalStateException) { }
        assertEquals(0, network.calls)
    }
    @Test fun oldAutomaticConsentCannotGenerateSaveOrScheduleAnything() = runTest {
        val network = Cloud(); val source = source().apply { entries = entries.map { it.copy(date = LocalDate.of(2026, 10, 14)) } }
        val store = Store(cloud())
        val engine = DiaryAiEngine(source, store, network, clock)
        repeat(3) { engine.runAutomatic() }
        assertEquals(0, network.calls)
        assertNull(source.saved)
        assertTrue(store.scheduled.isEmpty())
    }
    @Test fun newSummaryHasItsOwnProviderAndOnlyExplicitSavePersistsIt() = runTest {
        val source = source(); val network = Cloud(); val phone = Phone()
        val engine = DiaryAiEngine(source, Store(local()), network, clock, phone)
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        assertEquals(PhoneSummaryResultPolicy.provider(sha), review.provider)
        assertEquals("small@$sha", review.model)
        assertTrue(review.inputHash.matches(Regex("[a-f0-9]{64}")))
        assertNull(source.saved)
        assertTrue(engine.save(review, preview))
        assertEquals(review, source.saved)
        assertEquals(1, phone.calls); assertEquals(0, network.calls)
    }
    @Test fun sourceEditDuringGenerationPreventsPublishingAndSaving() = runTest {
        val source = source(); val phone = Phone().apply { after = { source.current = false } }
        val engine = DiaryAiEngine(source, Store(local()), Cloud(), clock, phone)
        try { engine.generate(engine.preview(day, day)); fail("stale summary") } catch (_: AiStaleException) { }
        assertNull(source.saved)
    }
    @Test fun editingGeneratedProseCannotReuseItsSaveReceipt() = runTest {
        val source = source()
        val engine = DiaryAiEngine(source, Store(local()), Cloud(), clock, Phone())
        val preview = engine.preview(day, day); val review = engine.generate(preview)
        val forged = review.copy(sections = review.sections.map { if (it.kind == AiSectionKind.LIFE) it.copy(text = "今天获得全国比赛冠军。") else it })
        try { engine.save(forged, preview); fail("edited generated summary accepted") } catch (_: AiProtocolException) { }
        assertNull(source.saved)
    }
    @Test fun anIncludedSourceCannotBeRemovedFromTheConfirmedPreviewPlan() = runTest {
        val source = source().apply { entries = entries + entries.single().copy(id = 2) }
        val phone = Phone(); val engine = DiaryAiEngine(source, Store(local()), Cloud(), clock, phone)
        val preview = engine.preview(day, day)
        val forged = preview.copy(plan = preview.plan.copy(chunks = listOf(listOf(source.entries.first())), includedCount = 1))
        try { engine.generate(forged); fail("incomplete source plan accepted") } catch (_: AiStaleException) { }
        assertEquals(0, phone.calls)
    }
}
