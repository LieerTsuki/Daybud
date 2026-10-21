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
        var before: () -> Unit = {}
        var result: (List<AiReviewSection>) -> List<AiReviewSection> = { it }
        override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
            before(); beforeStep(); calls++; ids = sources.map { it.id }.toSet()
            val life = "AI摘选原文（逐字摘录，分类仅供回看）：\n" + sources.joinToString("\n") {
                val text = it.body.ifBlank { it.title }.let { raw -> raw.substring(0, raw.offsetByCodePoints(0, minOf(64, raw.codePointCount(0, raw.length)))) }
                "${it.date} #${it.id} ${if (it.body.isBlank()) "标题" else "正文"}：“$text”"
            }
            val mood = "已填写的心情标签（不推测正文中的情绪）：\n" + sources.joinToString("\n") {
                "${it.date} #${it.id}：${it.mood?.takeIf(String::isNotBlank) ?: "未填写"}"
            }
            return result(listOf(
                AiReviewSection(AiSectionKind.LIFE, life, ids),
                AiReviewSection(AiSectionKind.MOOD, mood, ids),
                AiReviewSection(AiSectionKind.MOMENTS, "本次AI未选出特别时刻原文片段；这不代表原文中不存在，可查看来源。", ids),
                AiReviewSection(AiSectionKind.GROWTH, "本次AI未选出反思原文片段；这不代表原文中不存在，可查看来源。", ids),
                AiReviewSection(AiSectionKind.SUPPORT, "本次AI未选出给自己的提醒原文片段；这不代表原文中不存在，可查看来源。", ids),
            ))
        }
        override suspend fun probe(config: AiServiceConfig) { calls++ }
    }
    private fun entry(body: String = "散步了") = AiDiarySource(1, 4, day, "今天", body, "开心")
    @Test fun localLongEntryUsesPhoneWithoutCloudCharacterLimitOrUpload() = runTest {
        val cloud = Cloud(); val phone = Phone(); val store = Store(AiServiceConfig.local("small", hash, 2))
        val engine = DiaryAiEngine(Source(listOf(entry("学习".repeat(7000)))), store, cloud, clock, phone)
        val preview = engine.preview(day, day); val review = engine.generate(preview)
        assertEquals(1, phone.calls); assertEquals(0, cloud.calls); assertEquals(setOf(1L), review.sources.keys)
        assertTrue(review.model.contains(hash)); assertTrue(review.provider.startsWith("phone-offline:"))
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

    @Test fun verifiedMedicalOriginalCanBeGeneratedAndSavedLocally() = runTest {
        val source = Source(listOf(entry("今天确诊了感冒。")))
        val engine = DiaryAiEngine(source, Store(AiServiceConfig.local("small", hash, 2)), Cloud(), clock, Phone())
        val preview = engine.preview(day, day)
        val review = engine.generate(preview)
        assertTrue(review.sections.first().text.contains("今天确诊了感冒。"))
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

    @Test fun inventedPhoneOutputIsRejectedAtGenerationBoundary() = runTest {
        val source = Source(listOf(entry()))
        val phone = Phone().apply { result = { sections -> sections.map {
            if (it.kind == AiSectionKind.LIFE) it.copy(text = "今天获得比赛冠军。") else it
        } } }
        val engine = DiaryAiEngine(source, Store(AiServiceConfig.local("small", hash, 2)), Cloud(), clock, phone)
        try { engine.generate(engine.preview(day, day)); fail("Phone output must be grounded before display") }
        catch (_: AiProtocolException) { }
        assertEquals(0, source.saves)
    }

    @Test fun cloudModeDoesNotGainAnExceptionFromPhoneProviderMetadata() = runTest {
        val source = Source(listOf(entry("今天确诊了感冒。")))
        val config = AiServiceConfig.checked("https://cloud.example", "model", "key", 2)
        val engine = DiaryAiEngine(source, Store(config), Cloud(), clock)
        val preview = engine.preview(day, day)
        val sections = Phone().generate(config, source.entries, {}, {})
        val review = AiDiaryReview("forged-phone-provider", day, day, Instant.now(clock), sections,
            source.entries.associate { it.id to it.revision }, provider = "phone-offline:$hash")
        try { engine.save(review, preview); fail("Cloud mode must retain its prose policy") }
        catch (_: AiProtocolException) { }
        assertEquals(0, source.saves)
    }
}
