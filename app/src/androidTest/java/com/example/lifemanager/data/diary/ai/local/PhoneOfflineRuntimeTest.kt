package com.example.lifemanager.data.diary.ai.local

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.domain.diary.ai.*
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Explicitly invoked real JNI/private-process tests; no substitutes and no missing-model skips.
 * These prove structural runtime behavior, not factual quality or ARM handset performance.
 * Push the pinned real model to targetContext.cacheDir/offline-validation.gguf before invoking.
 * No Room writes, diary seeding, cloud configuration changes, uninstall or data clearing.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhoneOfflineRuntimeTest {
    @Test fun a_realProbeTokenizerAndPrivateProcessStaySeparateFromBusinessStorage() = measured("probeAndProcess") {
        val info = context.packageManager.getServiceInfo(ComponentName(context, LocalInferenceService::class.java), 0)
        assertFalse("Inference service must remain private", info.exported)
        assertEquals(context.packageName + ":diary_inference", info.processName)
        val start = SystemClock.elapsedRealtime()
        store.acquire(MODEL_ID, MODEL_SHA).use { model ->
            LocalAiGateway(AndroidLocalInferenceClient(context)).probe(model)
            openEventually(model).use { session ->
                val tokens = session.countTokens("你是日记助手。", "今天傍晚散步二十分钟，心情平静。🙂")
                assertTrue("Real model tokenizer must return a usable prompt budget", tokens in 1..4096)
                val process = ownedInferenceProcess()
                assertNotEquals("JNI must not execute inside the business process", Process.myPid(), process.pid)
                assertEquals(Process.myUid(), process.uid)
                val descriptors = File("/proc/${process.pid}/fd").listFiles()?.mapNotNull { file ->
                    runCatching { Os.readlink(file.path) }.getOrNull()
                }.orEmpty()
                if (descriptors.isNotEmpty()) assertTrue(
                    "Private inference process unexpectedly opened business databases",
                    descriptors.none { "/databases/" in it || it.endsWith(".db-wal") || it.endsWith(".db-shm") },
                )
                val threadNames = File("/proc/${process.pid}/task").listFiles()?.mapNotNull { task ->
                    runCatching { File(task, "comm").readText().trim() }.getOrNull()
                }.orEmpty()
                if (threadNames.isNotEmpty()) assertTrue(
                    "Private inference process unexpectedly started WorkManager threads",
                    threadNames.none { it.startsWith("WM.") || it.startsWith("WorkManager") },
                )
                val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
                record("probeAndProcess", buildJsonObject {
                    put("state", "passed"); put("elapsedMillis", SystemClock.elapsedRealtime() - start)
                    put("tokenCount", tokens); put("mainPid", Process.myPid()); put("inferencePid", process.pid)
                    put("inferenceProcess", process.processName); put("sameUid", process.uid == Process.myUid())
                    put("procFileDescriptorsObservable", descriptors.isNotEmpty())
                    put("observedOpenBusinessDatabase", descriptors.any { "/databases/" in it })
                    put("procThreadsObservable", threadNames.isNotEmpty())
                    put("observedWorkerThread", threadNames.any { it.startsWith("WM.") || it.startsWith("WorkManager") })
                    put("availableDeviceMemoryBytes", memory.availMem); put("deviceTotalMemoryBytes", memory.totalMem)
                    put("observationLimit", "FD/thread samples only; unavailable proc access is recorded, not counted as business initialization proof")
                })
            }
        }
    }

    @Test fun b_actualSummaryPipelineRequiresFiveSectionsEverySourceAndRealEos() = measured("structuralGeneration") {
        val sources = listOf(
            AiDiarySource(1, 7, LocalDate.of(2026, 10, 18), "散步", "今天傍晚沿操场散步二十分钟，回来后整理了两页课程笔记。", "平静"),
            AiDiarySource(2, 9, LocalDate.of(2026, 10, 19), "实验记录", "今天检查实验日志，发现一个输入问题，还没有解决。我计划明天继续检查。没有记录心情。", null),
        )
        val actualOutputs = mutableListOf<LocalInferenceOutput>()
        val started = SystemClock.elapsedRealtime()
        store.acquire(MODEL_ID, MODEL_SHA).use { model ->
            openEventually(model).use { delegate ->
                val recording = object : LocalInferenceSession {
                    override suspend fun countTokens(system: String, user: String) = delegate.countTokens(system, user)
                    override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
                        assertTrue("Summary generation must keep its short output budget", maxOutputTokens <= 320)
                        val material = Json.parseToJsonElement(user).jsonObject.getValue("sources").jsonArray
                        assertEquals("All sources must reach the real model in one request", 2, material.size)
                        return delegate.generate(system, user, grammar, maxOutputTokens).also {
                            actualOutputs += it
                            // Retain synthetic raw output even if structural policy later rejects it.
                            record("structuralNativeOutputs", buildJsonObject {
                                put("syntheticSources", true); put("semanticQualityAssessed", false)
                                put("nativeOutputs", nativeOutputsJson(actualOutputs))
                            })
                        }
                    }
                    override fun cancel() = delegate.cancel()
                    override fun close() = Unit // the outer use owns the real session
                }
                val sections = withTimeout(180_000) { LocalSummaryPipeline(recording).generate(sources, {}) }
                PhoneSummaryResultPolicy.validate(sections, sources)
                assertEquals(5, sections.size)
                assertTrue(sections.all { it.sourceIds == setOf(1L, 2L) })
                assertEquals("Summary pipeline must not silently split or replay sources", 1, actualOutputs.size)
                assertEquals("已填写的心情标签（不推测正文中的情绪）：\n2026-10-18 #1：平静\n2026-10-19 #2：未填写",
                    sections.single { it.kind == AiSectionKind.MOOD }.text)
                assertEquals("本次回顾来源（可打开原始日记核对）：\n2026-10-18 #1\n2026-10-19 #2",
                    sections.single { it.kind == AiSectionKind.MOMENTS }.text)
                assertTrue("Truncated native output cannot be accepted", actualOutputs.all { it.reachedEos && it.promptTokens > 0 && it.generatedTokens > 0 })
                record("structuralGeneration", buildJsonObject {
                    put("state", "passed"); put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                    put("semanticQualityAssessed", false); put("syntheticSources", true)
                    put("protocol", "phone-summary:v1"); put("singleCompleteRequest", true)
                    put("sections", sectionsJson(sections))
                    put("nativeOutputs", nativeOutputsJson(actualOutputs))
                })
            }
        }
    }

    @Test fun c_cancelRealGenerationWithinThreeSecondsAndReopenRuntime() = measured("cancellation") {
        store.acquire(MODEL_ID, MODEL_SHA).use { model ->
            val session = openEventually(model)
            try {
                supervisorScope {
                    val work = async(Dispatchers.IO) { longGeneration(session) }
                    delay(500)
                    assertTrue("Generation finished before the cancellation scenario began", work.isActive)
                    val cancelStarted = SystemClock.elapsedRealtime()
                    withTimeout(3000) { work.cancelAndJoin() }
                    val cancelMillis = SystemClock.elapsedRealtime() - cancelStarted
                    assertTrue("Cancellation exceeded three seconds: $cancelMillis ms", cancelMillis <= 3000)
                    session.close()
                    val reopenStarted = SystemClock.elapsedRealtime()
                    openEventually(model).use { reopened -> assertTrue(reopened.countTokens("检查", "取消后重新加载") > 0) }
                    record("cancellation", buildJsonObject {
                        put("state", "passed"); put("cancellationMillis", cancelMillis)
                        put("reopenAndCountMillis", SystemClock.elapsedRealtime() - reopenStarted)
                        put("resourcesReusableAfterCancellation", true)
                    })
                }
            } finally { session.close() }
        }
    }

    @Test fun d_killingOnlyOwnedInferenceProcessReturnsServiceDiedAndCanReopen() = measured("serviceDeath") {
        store.acquire(MODEL_ID, MODEL_SHA).use { model ->
            val session = openEventually(model)
            val oldProcess = ownedInferenceProcess()
            try {
                supervisorScope {
                    val work = async(Dispatchers.IO) { longGeneration(session) }
                    delay(500)
                    assertTrue("Generation finished before the process-death scenario began", work.isActive)
                    // Exact process name and same UID are checked immediately before the only kill.
                    val victim = ownedInferenceProcess()
                    assertEquals(oldProcess.pid, victim.pid)
                    assertNotEquals(Process.myPid(), victim.pid)
                    assertEquals(Process.myUid(), victim.uid)
                    Process.killProcess(victim.pid)
                    try {
                        withTimeout(10_000) { work.await() }
                        fail("A dead inference process must fail the current request")
                    } catch (failure: LocalAiException) {
                        assertEquals(LocalAiFailure.SERVICE_DIED, failure.code)
                    }
                    session.close()
                    openEventually(model).use { reopened ->
                        assertTrue(reopened.countTokens("检查", "推理进程恢复后重新加载") > 0)
                        val newProcess = ownedInferenceProcess()
                        assertNotEquals(oldProcess.pid, newProcess.pid)
                        record("serviceDeath", buildJsonObject {
                            put("state", "passed"); put("failure", LocalAiFailure.SERVICE_DIED.name)
                            put("oldInferencePid", oldProcess.pid); put("newInferencePid", newProcess.pid)
                            put("mainPid", Process.myPid()); put("mainProcessSurvived", true)
                        })
                    }
                }
            } finally { session.close() }
        }
    }

    private suspend fun longGeneration(session: LocalInferenceSession): LocalInferenceOutput = session.generate(
        "只按给定语法输出，不解释。", "输出一个包含三千个小写英文字母的JSON字符串。",
        """root ::= "\"" [a-z]{3000} "\""""", 1024,
    )

    // Exercise the production bounded OPEN admission policy, without a test-only retry loop.
    private suspend fun openEventually(model: LocalModelLease): LocalInferenceSession =
        LocalAiGateway(AndroidLocalInferenceClient(context)).openReady(model)

    @Test fun e_guardedEngineProcessesThreeSyntheticEntriesAndSavesOnlyExplicitly() = measured("guardedEngine") {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("offline-ai-synthetic-baseline.json")
            .bufferedReader().use { it.readText() }
        val sources = Json.parseToJsonElement(raw).jsonArray.map { element ->
            val value = element.jsonObject
            AiDiarySource(value.getValue("id").jsonPrimitive.long, value.getValue("revision").jsonPrimitive.long,
                LocalDate.parse(value.getValue("date").jsonPrimitive.content), value.getValue("title").jsonPrimitive.content,
                value.getValue("body").jsonPrimitive.content, value["mood"]?.jsonPrimitive?.contentOrNull)
        }
        val characters = sources.sumOf { source -> source.body.count { it in '\u4e00'..'\u9fff' } }
        assertTrue(characters >= 1000)
        val spec = LocalModelCatalog.models.single { it.id == MODEL_ID }
        val memory = LocalDeviceCapability.snapshot(context)
        val capability = LocalDeviceCapability.evaluate(memory, spec)
        assertTrue("Actual production resource guard refused this device: ${capability.reasons}", capability.allowed)
        var saved: AiDiaryReview? = null
        val source = object : DiaryAiDataSource {
            override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through, sources)
            override suspend fun isCurrent(snapshot: AiDiarySnapshot) = snapshot.generation == 1L
            override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean { saved = review; return true }
            override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
            override suspend fun deleteReview(id: String) = Unit
        }
        val config = AiServiceConfig.local(MODEL_ID, MODEL_SHA, 1)
        val state = object : AiStateStore {
            override suspend fun config() = config
            override suspend fun saveConfig(address: String, model: String, key: String) = error("No cloud configuration writes")
            override suspend fun consents() = emptyList<AiAutoConsent>()
            override suspend fun setConsent(consent: AiAutoConsent) = Unit
            override suspend fun jobs() = emptyList<AiAutoJob>()
            override suspend fun putJob(job: AiAutoJob) = Unit
            override suspend fun pauseAutomatic() = Unit
        }
        val engine = DiaryAiEngine(source, state, phone = PhoneAiRunnerAdapter(context, store, LocalAiGateway(AndroidLocalInferenceClient(context))))
        val preview = engine.preview(sources.minOf { it.date }, sources.maxOf { it.date })
        val started = SystemClock.elapsedRealtime()
        val result = engine.generate(preview)
        val millis = SystemClock.elapsedRealtime() - started
        assertNull(saved)
        assertEquals(sources.associate { it.id to it.revision }, result.sources)
        assertTrue(result.model.endsWith("@$MODEL_SHA")); assertEquals("phone-summary:v1:$MODEL_SHA", result.provider)
        PhoneSummaryResultPolicy.validate(result.sections, sources)
        assertTrue(result.sections.all { it.sourceIds == sources.map { source -> source.id }.toSet() })
        assertTrue(engine.save(result, preview)); assertEquals(result, saved)
        record("guardedEngine", buildJsonObject {
            put("state", "passed"); put("elapsedMillis", millis); put("sourceCount", sources.size); put("hanCharacters", characters)
            put("resourceGuardPassed", capability.allowed); put("availableMemoryBytes", memory.availableMemoryBytes)
            put("explicitSaveOnly", true); put("cloudGatewayDependencyPresent", false); put("semanticQualityAssessed", false)
            put("protocol", "phone-summary:v1")
            put("sections", sectionsJson(result.sections))
        })
    }

    private fun ownedInferenceProcess(): ActivityManager.RunningAppProcessInfo =
        activityManager.runningAppProcesses.orEmpty().singleOrNull {
            it.processName == context.packageName + ":diary_inference" && it.uid == Process.myUid() && it.pid != Process.myPid()
        } ?: throw AssertionError("Expected exactly one owned private inference process")

    private fun sectionsJson(sections: List<AiReviewSection>) = buildJsonArray {
        sections.forEach { section -> add(buildJsonObject {
            put("kind", section.kind.name); put("text", section.text)
            put("sourceIds", buildJsonArray { section.sourceIds.sorted().forEach { add(it) } })
        }) }
    }

    private fun nativeOutputsJson(outputs: List<LocalInferenceOutput>) = buildJsonArray {
        outputs.forEach { output -> add(buildJsonObject {
            put("text", output.text); put("promptTokens", output.promptTokens)
            put("generatedTokens", output.generatedTokens); put("reachedEos", output.reachedEos)
        }) }
    }

    private fun measured(name: String, operation: suspend () -> Unit) = runBlocking {
        record(name, buildJsonObject { put("state", "started") })
        try { operation() }
        catch (failure: Throwable) {
            record(name, buildJsonObject { put("state", "failed"); put("failureType", failure.javaClass.simpleName) })
            throw failure
        }
    }

    companion object {
        private val MODEL_ID get() = InstrumentationRegistry.getArguments().getString("modelId") ?: "qwen3-0.6b-q4km-b5501"
        private val MODEL_SHA get() = LocalModelCatalog.models.single { it.id == MODEL_ID }.sha256
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val activityManager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        private lateinit var store: LocalModelStore
        private val results = linkedMapOf<String, JsonElement>()

        @JvmStatic @BeforeClass fun preparePinnedRealModel() = runBlocking {
            val seed = File(context.cacheDir, "offline-validation.gguf")
            assertTrue("Missing real GGUF setup: push the pinned Q4 model to ${seed.absolutePath} before invoking PhoneOfflineRuntimeTest; no model test is skipped", seed.isFile)
            val spec = LocalModelCatalog.models.single { it.id == MODEL_ID }
            assertEquals("The runtime fixture must remain pinned to the validated bytes", MODEL_SHA, spec.sha256)
            assertEquals("Wrong or incomplete GGUF setup file", spec.expectedBytes, seed.length())
            store = LocalModelStore(context)
            val preexisting = store.readyModel(MODEL_ID) != null
            val ready = if (preexisting) store.readyModel(MODEL_ID)!! else store.importModel(MODEL_ID, { seed.inputStream() })
            assertEquals(MODEL_SHA, ready.sha256)
            record("setup", buildJsonObject {
                put("state", "passed"); put("modelId", MODEL_ID); put("modelSha256", MODEL_SHA); put("modelBytes", ready.bytes)
                put("preexistingReadyModel", preexisting); put("modelLeftReadyForReproduction", true)
                put("api", Build.VERSION.SDK_INT); put("manufacturer", Build.MANUFACTURER); put("deviceModel", Build.MODEL)
                put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })
                put("scope", "Real JNI/private-service structural runtime on this device only; not semantic quality, ARM handset coverage or market adaptation")
                put("businessDatabaseWritesByTest", false)
            })
        }

        @Synchronized private fun record(name: String, value: JsonElement) {
            results[name] = value
            File(context.cacheDir, "phone-offline-runtime-evidence.json").writeText(
                Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(results)),
            )
        }
    }
}
