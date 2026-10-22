package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class AiGenerationPreview(val snapshot: AiDiarySnapshot, val plan: AiInputPlan, val serviceOrigin: String, val configRevision: Long,
                               val configModel: String, val configSha256: String)

/** Only the phone runner can generate new reviews. Historical providers remain readable data. */
class DiaryAiEngine(
    private val source: DiaryAiDataSource,
    private val store: AiStateStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val phone: PhoneAiRunner? = null,
) {
    /** Keeps old callers source-compatible without retaining a network execution dependency. */
    @Suppress("UNUSED_PARAMETER")
    constructor(source: DiaryAiDataSource, store: AiStateStore, gateway: AiCompletionGateway,
                clock: Clock = Clock.systemDefaultZone(), phone: PhoneAiRunner? = null) : this(source, store, clock, phone)

    private val generationMutex = Mutex()
    private var generatedReceipt: Pair<String, String>? = null

    suspend fun preview(from: LocalDate, through: LocalDate): AiGenerationPreview {
        require(!through.isBefore(from) && !through.isAfter(LocalDate.now(clock))) { "请选择有效的日期范围，不能选择未来日期" }
        val config = localConfiguration()
        val snapshot = source.snapshot(from, through)
        if (snapshot.from != from || snapshot.through != through || snapshot.sources.any { it.date < from || it.date > through }) throw AiStaleException()
        return AiGenerationPreview(snapshot, AiInputPolicy.planLocal(snapshot.sources), config.origin, config.revision,
            config.model, config.localModelSha256 ?: throw AiStaleException())
    }

    suspend fun generate(preview: AiGenerationPreview, progress: (String) -> Unit = {}): AiDiaryReview = generationMutex.withLock {
        generatedReceipt = null
        val config = guard(preview)
        val entries = preview.plan.chunks.flatten()
        if (entries.isEmpty()) throw AiNoContentException()
        val runner = phone ?: throw IllegalStateException("手机离线引擎不可用，请检查应用安装")
        val sections = runner.generate(config, entries, { guard(preview) }, progress)
        guard(preview)
        PhoneSummaryResultPolicy.validate(sections, entries)
        val sha = config.localModelSha256 ?: throw AiStaleException()
        val review = AiDiaryReview(UUID.randomUUID().toString(), preview.snapshot.from, preview.snapshot.through,
            Instant.now(clock), sections, entries.associate { it.id to it.revision },
            provider = PhoneSummaryResultPolicy.provider(sha), model = "${config.model}@$sha", inputHash = inputHash(config, preview))
        generatedReceipt = review.id to receipt(review)
        review
    }

    suspend fun save(review: AiDiaryReview, preview: AiGenerationPreview): Boolean = generationMutex.withLock {
        val config = guard(preview)
        val entries = preview.plan.chunks.flatten()
        if (review.from != preview.snapshot.from || review.through != preview.snapshot.through ||
            review.sources != entries.associate { it.id to it.revision }) throw AiStaleException()
        if (generatedReceipt != (review.id to receipt(review)) || review.needsUpdate ||
            review.provider != PhoneSummaryResultPolicy.provider(config.localModelSha256 ?: throw AiStaleException()) ||
            review.model != "${config.model}@${config.localModelSha256}" || review.inputHash != inputHash(config, preview)) throw AiProtocolException()
        PhoneSummaryResultPolicy.validate(review.sections, entries)
        store.withCurrentConfiguration(config) { source.save(review, preview.snapshot) }
    }

    suspend fun testConnection() {
        val config = localConfiguration()
        (phone ?: throw IllegalStateException("手机离线引擎不可用")).probe(config)
    }

    /** Old queued workers and old consent records can never restart cloud execution. */
    suspend fun runAutomatic() = Unit
    @Suppress("UNUSED_PARAMETER")
    suspend fun setAutomatic(kind: AiPeriodKind, enabled: Boolean) {
        check(!enabled) { "云端自动回顾已停用，请手动生成手机离线回顾" }
    }

    private suspend fun localConfiguration(): AiServiceConfig {
        val config = store.config() ?: throw IllegalStateException("请先选择并准备手机离线模型")
        check(config.mode == AiExecutionMode.LOCAL) { "云端AI已停用，请选择手机离线模型" }
        return config
    }

    private suspend fun guard(preview: AiGenerationPreview): AiServiceConfig {
        currentCoroutineContext().ensureActive()
        val config = store.config() ?: throw AiStaleException()
        checkConfiguration(preview, config)
        if (preview.plan != AiInputPolicy.planLocal(preview.snapshot.sources) || !source.isCurrent(preview.snapshot)) throw AiStaleException()
        // Source verification can suspend while another action selects or invalidates a model.
        currentCoroutineContext().ensureActive()
        val current = store.config() ?: throw AiStaleException()
        checkConfiguration(preview, current)
        currentCoroutineContext().ensureActive()
        return current
    }

    private fun checkConfiguration(preview: AiGenerationPreview, config: AiServiceConfig) {
        if (config.mode != AiExecutionMode.LOCAL || config.revision != preview.configRevision || config.origin != preview.serviceOrigin ||
            config.model != preview.configModel || config.localModelSha256 != preview.configSha256) throw AiStaleException()
    }

    private fun inputHash(config: AiServiceConfig, preview: AiGenerationPreview): String = hash(buildJsonObject {
        put("protocol", "phone-summary:v1"); put("model", config.model); put("sha256", config.localModelSha256)
        put("generation", preview.snapshot.generation); put("from", preview.snapshot.from.toString()); put("through", preview.snapshot.through.toString())
        put("input", Json.parseToJsonElement(PhoneSummaryResultPolicy.input(preview.plan.chunks.flatten())))
    }.toString())

    /** Edited generated prose or metadata cannot reuse a previously issued save action. */
    private fun receipt(review: AiDiaryReview): String = hash(buildJsonObject {
        put("id", review.id); put("from", review.from.toString()); put("through", review.through.toString()); put("createdAt", review.createdAt.toString())
        put("provider", review.provider); put("model", review.model); put("inputHash", review.inputHash); put("needsUpdate", review.needsUpdate)
        put("sources", buildJsonArray { review.sources.toSortedMap().forEach { (id, revision) -> add(buildJsonArray { add(id); add(revision) }) } })
        put("sections", buildJsonArray { review.sections.forEach { section -> add(buildJsonObject {
            put("kind", section.kind.name); put("text", section.text)
            put("sourceIds", buildJsonArray { section.sourceIds.sorted().forEach { add(it) } })
        }) } })
    }.toString())

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
