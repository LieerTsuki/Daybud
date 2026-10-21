package com.example.lifemanager.data.diary.ai.local

import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalGroundedPipelineTest {
    private class Runtime : LocalInferenceSession {
        val users = mutableListOf<String>()
        var eos = true
        var cancelled = false
        var mutate: (String) -> String = { it }
        var tokens: (String, String) -> Int = { s, u -> (s.length + u.length) / 2 + 1 }
        var beforeGenerate: () -> Unit = {}
        override suspend fun countTokens(system: String, user: String) = tokens(system, user)
        override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
            beforeGenerate(); users += user
            val groups = Json.parseToJsonElement(user).jsonObject.getValue("sources").jsonArray.map { it.jsonObject }
            val output = buildJsonObject { put("items", buildJsonArray {
                groups.forEach { group -> add(buildJsonObject {
                    val entries = group.getValue("units").jsonArray.map { it.jsonObject }
                    put("sourceId", group.getValue("sourceId")); put("life", entries.first().getValue("index")); put("moment", 0); put("reflection", 0); put("reminder", 0)
                }) }
            }) }.toString()
            return LocalInferenceOutput(mutate(output), countTokens(system, user), 100, eos)
        }
        override fun cancel() { cancelled = true }
        override fun close() = Unit
    }
    private fun source(id: Long = 1, body: String = "实验没有完成。明天计划求助。", mood: String? = "失落") =
        AiDiarySource(id, 7, LocalDate.of(2026, 10, 19), "", body, mood)

    @Test fun onlyOriginalQuotesAndExplicitMoodAreReturned() = runTest {
        val result = LocalGroundedPipeline(Runtime()).generate(listOf(source()), {})
        assertEquals(5, result.size); AiResultPolicy.validate(result, setOf(1L))
        assertTrue(result.first().text.contains("实验没有完成。"))
        assertTrue(result.first { it.kind == AiSectionKind.MOOD }.text.contains("失落"))
        assertTrue(result.first { it.kind == AiSectionKind.GROWTH }.text.contains("未选出"))
        assertFalse(result.any { "能力提升" in it.text })
    }
    @Test fun sameDayEntriesRetainIndependentMoodLabelsAndSources() = runTest {
        val result = LocalGroundedPipeline(Runtime()).generate(listOf(source(mood = "烦躁"), source(2, "视频聊天。", "轻松")), {})
        val mood = result.first { it.kind == AiSectionKind.MOOD }.text
        assertTrue(mood.contains("#1：烦躁")); assertTrue(mood.contains("#2：轻松"))
        assertEquals(setOf(1L, 2L), result.first().sourceIds.toSet())
    }
    @Test fun missingMoodIsNotInferredAndTitleOnlyIsNotTurnedIntoCompletedEvent() = runTest {
        val result = LocalGroundedPipeline(Runtime()).generate(listOf(source(body = "", mood = null).copy(title = "准备参加读书会")), {})
        assertTrue(result.first().text.contains("准备参加读书会"))
        assertTrue(result.first { it.kind == AiSectionKind.MOOD }.text.contains("未填写"))
        assertFalse(result.any { "已参加" in it.text })
    }
    @Test fun unicodeFragmentsPreserveAllMaterialAndRevisions() = runTest {
        val runtime = Runtime(); val body = "猫🙂散步。".repeat(300)
        LocalGroundedPipeline(runtime).generate(listOf(source(body = body)), {})
        assertTrue(runtime.users.size > 1)
        val groups = runtime.users.flatMap { Json.parseToJsonElement(it).jsonObject.getValue("sources").jsonArray }.map { it.jsonObject }
        val units = groups.flatMap { it.getValue("units").jsonArray }.map { it.jsonObject }
        assertEquals(body, units.joinToString("") { it.getValue("text").jsonPrimitive.content })
        assertTrue(groups.all { it.getValue("revision").jsonPrimitive.long == 7L })
    }
    @Test fun invalidMissingForeignOrExtraFieldsFailClosed() = runTest {
        listOf("{\"items\":[]}", "{\"items\":[],\"text\":\"编造\"}", "{\"items\":[{\"sourceId\":1,\"life\":999,\"moment\":0,\"reflection\":0,\"reminder\":0}]}").forEach { invalid ->
            try { LocalGroundedPipeline(Runtime().apply { mutate = { invalid } }).generate(listOf(source()), {}); fail() } catch (_: AiProtocolException) {}
        }
    }
    @Test fun indexFromAnotherDiaryCannotBeCitedAsTheFirstDiary() = runTest {
        val runtime = Runtime().apply { mutate = { it.replace("\"life\":1", "\"life\":2") } }
        try { LocalGroundedPipeline(runtime).generate(listOf(source(body = "上午。"), source(2, "晚上。")), {}); fail() } catch (_: AiProtocolException) {}
    }
    @Test fun workBudgetAndSourceLimitAreCheckedBeforeGeneration() = runTest {
        val runtime = Runtime()
        try { LocalGroundedPipeline(runtime, LocalAiBudget(maxTotalTokenWork = 1)).generate(listOf(source()), {}); fail() }
        catch (e: LocalAiException) { assertEquals(LocalAiFailure.BUDGET, e.code) }
        try { LocalGroundedPipeline(runtime).generate((1L..21L).map { source(it) }, {}); fail() }
        catch (e: LocalAiException) { assertEquals(LocalAiFailure.BUDGET, e.code) }
        assertTrue(runtime.users.isEmpty())
    }
    @Test fun incompleteOutputAndCancellationDoNotReturnPartialReview() = runTest {
        try { LocalGroundedPipeline(Runtime().apply { eos = false }).generate(listOf(source()), {}); fail() }
        catch (e: LocalAiException) { assertEquals(LocalAiFailure.INCOMPLETE, e.code) }
        val runtime = Runtime().apply { beforeGenerate = { throw CancellationException("cancel") } }
        try { LocalGroundedPipeline(runtime).generate(listOf(source()), {}); fail() } catch (_: CancellationException) {}
        assertTrue(runtime.cancelled)
    }
    @Test fun sourceChangeAfterGenerationIsRejected() = runTest {
        var invalid = false; val runtime = Runtime().apply { beforeGenerate = { invalid = true } }
        try { LocalGroundedPipeline(runtime).generate(listOf(source()), { if (invalid) throw AiStaleException() }); fail() }
        catch (_: AiStaleException) {}
    }
}
