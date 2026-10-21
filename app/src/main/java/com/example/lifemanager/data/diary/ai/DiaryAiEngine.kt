package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AiGenerationPreview(val snapshot: AiDiarySnapshot, val plan: AiInputPlan, val serviceOrigin: String, val configRevision: Long)

/** A fixed source snapshot is never refreshed underneath a user's confirmation. */
class DiaryAiEngine(
    private val source: DiaryAiDataSource,
    private val store: AiStateStore,
    private val gateway: AiCompletionGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val phone: PhoneAiRunner? = null,
) {
    private val generationMutex = Mutex()
    suspend fun preview(from: LocalDate, through: LocalDate): AiGenerationPreview {
        require(!through.isBefore(from) && !through.isAfter(LocalDate.now(clock))) { "请选择有效的日期范围，不能选择未来日期" }
        val config = store.config() ?: throw IllegalStateException("请先在AI服务中填写配置")
        val snapshot = source.snapshot(from, through)
        if (snapshot.from != from || snapshot.through != through || snapshot.sources.any { it.date < from || it.date > through }) throw AiStaleException()
        val plan = if (config.mode == AiExecutionMode.LOCAL) AiInputPolicy.planLocal(snapshot.sources) else AiInputPolicy.plan(snapshot.sources)
        return AiGenerationPreview(snapshot, plan, config.origin, config.revision)
    }

    suspend fun generate(preview: AiGenerationPreview, progress: (String) -> Unit = {}): AiDiaryReview = generationMutex.withLock { generateInternal(preview, null, null, progress) }

    private suspend fun guard(preview: AiGenerationPreview, consent: AiAutoConsent?): AiServiceConfig {
        currentCoroutineContext().ensureActive()
        val config = store.config() ?: throw AiStaleException()
        if (config.revision != preview.configRevision || config.origin != preview.serviceOrigin || !source.isCurrent(preview.snapshot)) throw AiStaleException()
        if (consent != null) {
            val current = store.consents().firstOrNull { it.kind == consent.kind }
            if (current != consent || !current.enabled || current.configRevision != config.revision || current.origin != config.origin || current.generation != preview.snapshot.generation) throw AiStaleException()
            val periods = AiSchedulePolicy.recentCompleted(current, LocalDateTime.now(clock))
            if (periods.none { it.from == preview.snapshot.from && it.through == preview.snapshot.through }) throw AiStaleException()
        }
        return config
    }

    private suspend fun generateInternal(preview: AiGenerationPreview, consent: AiAutoConsent?, beforeActualSend: (suspend () -> Unit)?, progress: (String) -> Unit = {}): AiDiaryReview {
        if (preview.plan.chunks.isEmpty()) throw AiNoContentException()
        val config = guard(preview, consent)
        val entries = preview.plan.chunks.flatten()
        val sections = if (config.mode == AiExecutionMode.LOCAL) {
            check(consent == null) { "手机离线模式仅支持手动回顾" }
            val runner = phone ?: throw IllegalStateException("手机离线引擎不可用，请检查应用安装")
            runner.generate(config, entries, { guard(preview, null) }, progress)
        } else if (preview.plan.chunks.size == 1) {
            gateway.complete(config, entries, emptyList()) { guard(preview, consent); beforeActualSend?.invoke(); guard(preview, consent) }
        } else {
            val partials = mutableListOf<AiReviewSection>()
            preview.plan.chunks.forEach { chunk ->
                val part = gateway.complete(config, chunk, emptyList()) { guard(preview, consent); beforeActualSend?.invoke(); guard(preview, consent) }
                AiResultPolicy.validate(part, chunk.map { it.id }.toSet())
                partials += part
                if (partials.sumOf { it.text.length } > 24_000) throw AiBudgetException()
            }
            // Integration request contains all IDs and all bounded partials, never a silently truncated subset.
            gateway.complete(config, entries.map { it.copy(title = "", body = "", mood = null) }, partials) {
                guard(preview, consent); beforeActualSend?.invoke(); guard(preview, consent)
            }
        }
        guard(preview, consent)
        if (config.mode == AiExecutionMode.LOCAL) GroundedAiResultPolicy.validate(sections, entries)
        else AiResultPolicy.validate(sections, entries.map { it.id }.toSet())
        val input = "${config.mode}/${config.localModelSha256.orEmpty()}/phone-excerpts-v2/${preview.snapshot.from}/${preview.snapshot.through}/" + entries.joinToString("|") { "${it.id}:${it.revision}:" } + ChatCompletionsProtocol.request(config.model, entries)
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        val model = if (config.mode == AiExecutionMode.LOCAL) "${config.model}@${config.localModelSha256}" else config.model
        return AiDiaryReview(UUID.randomUUID().toString(), preview.snapshot.from, preview.snapshot.through, Instant.now(clock), sections, entries.associate { it.id to it.revision }, provider = config.origin, model = model, inputHash = hash)
    }

    suspend fun save(review: AiDiaryReview, preview: AiGenerationPreview): Boolean {
        val config = guard(preview, null)
        if (review.from != preview.snapshot.from || review.through != preview.snapshot.through || review.sources != preview.plan.chunks.flatten().associate { it.id to it.revision }) throw AiStaleException()
        if (config.mode == AiExecutionMode.LOCAL) GroundedAiResultPolicy.validate(review.sections, preview.plan.chunks.flatten())
        else AiResultPolicy.validate(review.sections, review.sources.keys)
        return source.save(review, preview.snapshot)
    }

    suspend fun testConnection() {
        val config = store.config() ?: throw IllegalStateException("请先保存AI服务配置或选择离线模型")
        if (config.mode == AiExecutionMode.LOCAL) (phone ?: throw IllegalStateException("手机离线引擎不可用")).probe(config)
        else gateway.probe(config)
    }

    suspend fun setAutomatic(kind: AiPeriodKind, enabled: Boolean) {
        val config = store.config() ?: throw IllegalStateException("请先保存AI服务配置")
        check(!enabled || config.mode == AiExecutionMode.CLOUD) { "手机离线模式不启用云端自动回顾" }
        val generation=source.currentGeneration()
        store.setConsent(AiAutoConsent(kind, LocalDate.now(clock), config.revision, config.origin, enabled,generation))
    }

    /** One period in one run; sent-but-unknown is a durable terminal state, never a retry hint. */
    suspend fun runAutomatic() = generationMutex.withLock {
        val config = store.config() ?: return@withLock
        if (config.mode == AiExecutionMode.LOCAL) return@withLock
        val generation=source.currentGeneration()
        val consents = store.consents().filter { it.enabled && it.configRevision == config.revision && it.origin == config.origin && it.generation==generation }
        val jobs = store.jobs().associateBy { it.period.key }
        val candidates = consents.flatMap { consent -> AiSchedulePolicy.recentCompleted(consent, LocalDateTime.now(clock)).map { consent to it } }
            .sortedBy { it.second.from }
        val candidate = candidates.firstOrNull { (_, period) ->
            val old = jobs[period.key]
            old == null || (AiSchedulePolicy.mayAttempt(old.state, old.attempts) && Instant.now(clock).toEpochMilli() >= old.notBeforeMillis && old.configRevision == config.revision)
        } ?: return@withLock
        val (consent, period) = candidate
        val old = jobs[period.key]
        var job: AiAutoJob? = null
        var sent = false
        try {
            val preview = preview(period.from, period.through)
            if (old != null && old.generation != preview.snapshot.generation) {
                store.putJob(old.copy(state = AiJobState.PAUSED)); return@withLock
            }
            job = AiAutoJob(period, config.revision, preview.snapshot.generation, AiJobState.RETRYABLE_UNSENT, (old?.attempts ?: 0) + 1)
            store.putJob(job)
            if (preview.plan.chunks.isEmpty()) { store.putJob(job.copy(state = AiJobState.EMPTY)); return@withLock }
            val review = generateInternal(preview, consent, beforeActualSend = {
                // An automatic attempt may have multiple bounded chunks, but a process restart must never repeat it.
                if (!sent) { store.putJob(job!!.copy(state = AiJobState.SENT_UNKNOWN)); sent = true }
            })
            guard(preview, consent)
            if (!source.save(review, preview.snapshot)) throw AiStaleException()
            store.putJob(job.copy(state = AiJobState.SUCCEEDED))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val current = job
            val definitelyUnsent = !sent && error is AiNetworkException && !error.sent && error.retryable
            val knownRefusal = error is AiNetworkException && error.statusCode != null
            if (current != null) store.putJob(current.copy(
                state = if (knownRefusal) AiJobState.PAUSED else if (definitelyUnsent) AiJobState.RETRYABLE_UNSENT else if (sent) AiJobState.SENT_UNKNOWN else AiJobState.PAUSED,
                notBeforeMillis = Instant.now(clock).plusSeconds(6 * 3600).toEpochMilli(),
            ))
            if (error is AiProtocolException || error is AiNetworkException && error.pauseAutomatic) store.pauseAutomatic()
            // WorkManager success is deliberate: retries are governed solely by our noBackup job ledger.
        }
    }
}
