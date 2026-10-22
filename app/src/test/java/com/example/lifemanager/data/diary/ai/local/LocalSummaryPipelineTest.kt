package com.example.lifemanager.data.diary.ai.local

import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalSummaryPipelineTest {
    private val source = AiDiarySource(1, 3, LocalDate.of(2026, 10, 19), "项目", "报告还没有写完，计划明天先完成提纲。", "平静")
    private val good = """{"summary":"这段时间你在推进报告，目前还没有写完，已经计划明天先完成提纲。","advice":["可以先列出报告的三个小标题，再逐个补充已有的材料。","不妨给明天的提纲留出二十分钟，结束后再决定下一步。"]}"""
    private class Session(var raw: String, var tokens: Int = 400) : LocalInferenceSession {
        var tokenizations = 0
        var generations = 0
        var received = ""
        var outputLimit = 0
        var eos = true
        var generatedTokens = 100
        var returnedPromptTokens: Int? = null
        var cancelled = false
        override suspend fun countTokens(system: String, user: String): Int { tokenizations++; received = user; return tokens }
        override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
            generations++; received = user; outputLimit = maxOutputTokens
            return LocalInferenceOutput(raw, returnedPromptTokens ?: tokens, generatedTokens, eos)
        }
        override fun cancel() { cancelled = true }
        override fun close() = Unit
    }

    @Test fun thirteenShortEntriesUseOneCompleteRequestWithoutAnEightEntryCutoff() = runTest {
        val sources = (1L..13).map { source.copy(id = it, date = source.date.plusDays(it), body = "第${it}天，报告还没有写完。") }
        val session = Session(good)
        val result = LocalSummaryPipeline(session).generate(sources, {})
        val input = Json.parseToJsonElement(session.received).jsonObject.getValue("sources").jsonArray
        assertEquals(13, input.size)
        assertEquals(sources.map { it.body }, input.map { it.jsonObject.getValue("body").jsonPrimitive.content })
        assertEquals(1, session.tokenizations)
        assertEquals(1, session.generations)
        assertTrue(session.outputLimit <= 320)
        assertTrue(result.all { it.sourceIds == sources.map { it.id }.toSet() })
        assertEquals("这段时间你在推进报告，目前还没有写完，已经计划明天先完成提纲。", result.first { it.kind == AiSectionKind.LIFE }.text)
    }

    @Test fun actualTokenBudgetRejectsBeforeGenerationWithoutDroppingSources() = runTest {
        val session = Session(good, tokens = 3777)
        expectLocal(LocalAiFailure.BUDGET) { LocalSummaryPipeline(session).generate(listOf(source), {}) }
        assertEquals(1, session.tokenizations)
        assertEquals(0, session.generations)
    }

    @Test fun promptAtTheContextBoundaryIsAcceptedAndOutputLimitIsEnforced() = runTest {
        val session = Session(good, tokens = 3776)
        LocalSummaryPipeline(session).generate(listOf(source), {})
        assertEquals(1, session.generations)
        assertEquals(320, session.outputLimit)
    }

    @Test fun multibyteUtf8OverflowIsRejectedBeforeTokenizerOrGeneration() = runTest {
        val session = Session(good)
        expectLocal(LocalAiFailure.BUDGET) {
            LocalSummaryPipeline(session).generate(listOf(source.copy(body = "中".repeat(17_000))), {})
        }
        assertEquals(0, session.tokenizations)
        assertEquals(0, session.generations)
    }

    @Test fun eosAndReportedTokensAreCheckedBeforeAcceptingReview() = runTest {
        for (session in listOf(
            Session(good).apply { eos = false },
            Session(good).apply { generatedTokens = 321 },
            Session(good).apply { returnedPromptTokens = 401 },
        )) {
            try { LocalSummaryPipeline(session).generate(listOf(source), {}); fail("Incomplete or miscounted output accepted") }
            catch (_: LocalAiException) { }
            assertEquals(1, session.generations)
        }
    }

    @Test fun missingExtraNumericOrEmptyGeneratedFieldsNeverProduceReview() = runTest {
        for (raw in listOf(
            good.replace(",\"advice\":[", ",\"extra\":1,\"advice\":["),
            """{"summary":"。。。。","advice":["可以休息一下。","不妨喝杯热水。"]}""",
            """{"summary":123,"advice":["可以先列出三个报告小标题。","不妨预留二十分钟补充材料。"]}""",
            """{"summary":"报告还没有完成，计划明天继续。","advice":["可以先列出报告提纲。"]}""",
            """{"summary":"报告还没有完成，计划明天继续。","advice":["","不妨先列出报告提纲。"]}""",
        )) {
            try { LocalSummaryPipeline(Session(raw)).generate(listOf(source), {}); fail("Malformed generated content accepted: $raw") }
            catch (_: AiProtocolException) { }
        }
    }

    @Test fun stateChangeAfterTokenizationStopsBeforeAnyDecode() = runTest {
        val session = Session(good)
        try {
            LocalSummaryPipeline(session).generate(listOf(source), { if (session.tokenizations > 0) throw AiStaleException() })
            fail("Changed source was decoded")
        } catch (_: AiStaleException) { }
        assertEquals(0, session.generations)
    }

    @Test fun cancellationAfterDecodeCancelsSessionAndRejectsLateOutput() = runTest {
        val session = Session(good)
        try {
            LocalSummaryPipeline(session).generate(listOf(source), { if (session.generations > 0) throw CancellationException("Cancelled") })
            fail("Late result survived cancellation")
        } catch (_: CancellationException) { }
        assertTrue(session.cancelled)
    }

    @Test fun singleStepProgressReportsActualWorkAndNeverPretendsToMerge() = runTest {
        val progress = mutableListOf<LocalAiProgress>()
        LocalSummaryPipeline(Session(good)).generate(listOf(source), {}, progress::add)
        assertTrue(progress.any { it.stage == LocalAiStage.GENERATING && it.completedSteps == 0 && it.totalSteps == 1 })
        assertTrue(progress.any { it.stage == LocalAiStage.GENERATING && it.completedSteps == 1 && it.totalSteps == 1 })
        assertFalse(progress.any { it.stage == LocalAiStage.MERGING })
    }

    private suspend fun expectLocal(code: LocalAiFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: LocalAiException) { assertEquals(code, error.code) }
    }
}
