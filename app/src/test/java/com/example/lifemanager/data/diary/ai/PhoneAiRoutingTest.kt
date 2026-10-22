package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.time.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneAiRoutingTest {
    private val day = LocalDate.of(2026, 10, 14)
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    private val hash = "a".repeat(64)
    private class Source(val entries: List<AiDiarySource>) : DiaryAiDataSource {
        var saves = 0
        override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through, entries)
        override suspend fun isCurrent(snapshot: AiDiarySnapshot) = true
        override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean { saves++; return true }
        override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
        override suspend fun deleteReview(id: String) = Unit
    }
    private class Store(var value: AiServiceConfig) : AiStateStore {
        override suspend fun config() = value
        override suspend fun saveConfig(address: String, model: String, key: String) = value
        override suspend fun consents() = listOf(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.of(2026, 10, 1), value.revision, value.origin, true, 1))
        override suspend fun setConsent(consent: AiAutoConsent) = Unit
        override suspend fun jobs() = emptyList<AiAutoJob>()
        override suspend fun putJob(job: AiAutoJob) = Unit
        override suspend fun pauseAutomatic() = Unit
    }
    private class Cloud : AiCompletionGateway {
        var calls = 0
        override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> {
            beforeSend(); calls++; error("cloud must not receive offline sources")
        }
        override suspend fun probe(config: AiServiceConfig) { calls++ }
    }
    private class Phone : PhoneAiRunner {
        var calls = 0
        var ids = emptySet<Long>()
        var received = emptyList<AiDiarySource>()
        var before: () -> Unit = {}
        var result: (List<AiReviewSection>) -> List<AiReviewSection> = { it }
        override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
            before(); beforeStep(); calls++; ids = sources.map { it.id }.toSet(); received = sources.toList()
            return result(listOf(
                AiReviewSection(AiSectionKind.LIFE, "这段时间你留下了日常记录，具体内容可以结合日记回看。", ids),
                AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), ids),
                AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), ids),
                AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以补记一件在意的小事，方便之后回看。", ids),
                AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨先选一个小步骤，按自己的状态安排。", ids),
            ))
        }
        override suspend fun probe(config: AiServiceConfig) { calls++ }
    }
    private fun entry(body: String = "散步了") = AiDiarySource(1, 4, day, "今天", body, "开心")
    @Test fun localAdmissionPassesLongEntryUnchangedToPhoneForTokenBudgeting() = runTest {
        val cloud = Cloud(); val phone = Phone(); val store = Store(AiServiceConfig.local("small", hash, 2))
        val engine = DiaryAiEngine(Source(listOf(entry("学习".repeat(7000)))), store, cloud, clock, phone)
        val preview = engine.preview(day, day); val review = engine.generate(preview)
        assertEquals(1, phone.calls); assertEquals(0, cloud.calls); assertEquals(setOf(1L), review.sources.keys)
        assertTrue(review.model.contains(hash)); assertEquals("phone-summary:v1:$hash", review.provider)
        assertEquals("学习".repeat(7000), phone.received.single().body)
    }
    @Test fun missingPhoneEngineNeverFallsBackToCloud() = runTest {
        val cloud = Cloud(); val engine = DiaryAiEngine(Source(listOf(entry())), Store(AiServiceConfig.local("small", hash, 2)), cloud, clock)
        try { engine.generate(engine.preview(day, day)); fail("missing phone accepted") } catch (_: IllegalStateException) { }
        assertEquals(0, cloud.calls)
    }
    @Test fun localProbeAndAutomaticNeverInvokeCloud() = runTest {
        val cloud = Cloud(); val phone = Phone()
        val engine = DiaryAiEngine(Source(listOf(entry())), Store(AiServiceConfig.local("small", hash, 2)), cloud, clock, phone)
        engine.testConnection(); engine.runAutomatic()
        assertEquals(1, phone.calls); assertEquals(0, cloud.calls)
    }
    @Test fun modeChangedBeforePhoneStepInvalidatesPreview() = runTest {
        val store = Store(AiServiceConfig.local("small", hash, 2)); val cloud = Cloud()
        val phone = Phone().apply { before = { store.value = AiServiceConfig.checked("https://cloud.example", "model", "key", 3) } }
        val engine = DiaryAiEngine(Source(listOf(entry())), store, cloud, clock, phone)
        try { engine.generate(engine.preview(day, day)); fail("stale offline output") } catch (_: AiStaleException) { }
        assertEquals(0, phone.calls); assertEquals(0, cloud.calls)
    }

    @Test fun summarySavesProgramDerivedMoodAndCompleteSourceManifest() = runTest {
        val source = Source(listOf(entry()))
        val engine = DiaryAiEngine(source, Store(AiServiceConfig.local("small", hash, 2)), Cloud(), clock, Phone())
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        assertEquals("已填写的心情标签（不推测正文中的情绪）：\n2026-10-14 #1：开心", review.sections.single { it.kind == AiSectionKind.MOOD }.text)
        assertEquals("本次回顾来源（可打开原始日记核对）：\n2026-10-14 #1", review.sections.single { it.kind == AiSectionKind.MOMENTS }.text)
        assertTrue(engine.save(review, preview)); assertEquals(1, source.saves)
    }

    @Test fun phoneProviderPrefixCannotMakeForgedProseSaveable() = runTest {
        val source = Source(listOf(entry()))
        val engine = DiaryAiEngine(source, Store(AiServiceConfig.local("small", hash, 2)), Cloud(), clock, Phone())
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        val forged = review.copy(sections = review.sections.map { if (it.kind == AiSectionKind.LIFE) it.copy(text = "你今天获得了比赛冠军。") else it })
        try { engine.save(forged, preview); fail("An offline prefix cannot authorize invented prose") }
        catch (_: AiProtocolException) { }
        assertEquals(0, source.saves)
    }

    @Test fun modelInventedMoodIsRejectedAtGenerationBoundary() = runTest {
        val source = Source(listOf(entry()))
        val phone = Phone().apply { result = { sections -> sections.map {
            if (it.kind == AiSectionKind.MOOD) it.copy(text = "根据散步推断你一直焦虑。") else it
        } } }
        val engine = DiaryAiEngine(source, Store(AiServiceConfig.local("small", hash, 2)), Cloud(), clock, phone)
        try { engine.generate(engine.preview(day, day)); fail("Phone cannot invent mood labels before display") }
        catch (_: AiProtocolException) { }
        assertEquals(0, source.saves)
    }

    @Test fun retiredCloudModeCannotReuseAPhoneSaveReceiptOrProviderMetadata() = runTest {
        val source = Source(listOf(entry()))
        val config = AiServiceConfig.checked("https://cloud.example", "model", "key", 2)
        val store = Store(AiServiceConfig.local("small", hash, 1))
        val cloud = Cloud(); val engine = DiaryAiEngine(source, store, cloud, clock, Phone())
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        store.value = config
        try { engine.save(review, preview); fail("Retired cloud mode reused an offline save action") }
        catch (_: AiStaleException) { }
        try { engine.preview(day, day); fail("Retired cloud mode created a preview") }
        catch (_: IllegalStateException) { }
        assertEquals(0, cloud.calls)
        assertEquals(0, source.saves)
    }
}
