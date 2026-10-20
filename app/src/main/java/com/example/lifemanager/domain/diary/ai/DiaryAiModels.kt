package com.example.lifemanager.domain.diary.ai

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow

data class AiDiarySource(val id: Long, val revision: Long, val date: LocalDate, val title: String, val body: String, val mood: String?)
data class AiDiarySnapshot(val generation: Long, val from: LocalDate, val through: LocalDate, val sources: List<AiDiarySource>)
enum class AiSectionKind(val title: String) {
    LIFE("生活回顾"), MOOD("心情变化"), MOMENTS("重要时刻"), GROWTH("个人成长"), SUPPORT("温暖寄语")
}
data class AiReviewSection(val kind: AiSectionKind, val text: String, val sourceIds: Set<Long>)
data class AiDiaryReview(
    val id: String, val from: LocalDate, val through: LocalDate, val createdAt: Instant,
    val sections: List<AiReviewSection>, val sources: Map<Long, Long>, val needsUpdate: Boolean = false,
    val provider: String = "", val model: String = "", val inputHash: String = "",
)

/** Implementations must check generation and every source revision atomically when saving. */
interface DiaryAiDataSource {
    suspend fun snapshot(from: LocalDate, through: LocalDate): AiDiarySnapshot
    suspend fun isCurrent(snapshot: AiDiarySnapshot): Boolean
    suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean
    fun observeReviews(): Flow<List<AiDiaryReview>>
    fun observeInvalidations(): Flow<Unit> = emptyFlow()
    fun observeReviewSnapshots(): Flow<Pair<Long, List<AiDiaryReview>>> = observeReviews().map { currentGeneration() to it }
    suspend fun deleteReview(id: String)
    suspend fun currentGeneration(): Long = snapshot(LocalDate.now(), LocalDate.now()).generation
    /** Android adapter overrides this with atomic generation admission around deletion. */
    suspend fun deleteReview(id: String, expectedGeneration: Long) {
        if (currentGeneration() != expectedGeneration) throw AiStaleException()
        deleteReview(id)
    }
}

/** Deliberately not a data class: a secret must never leak through generated toString/copy. */
class AiServiceConfig private constructor(val endpoint: String, val model: String, internal val apiKey: String, val revision: Long) {
    val origin: String get() { val uri = URI(endpoint); return "https://${uri.host.lowercase()}" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}" }
    override fun toString() = "AiServiceConfig(revision=$revision)"
    companion object {
        fun checked(address: String, model: String, key: String, revision: Long): AiServiceConfig {
            val uri = try { URI(address.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的HTTPS服务地址") }
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "服务地址必须为HTTPS且不能包含账号、查询或片段" }
            require(uri.port == -1 || uri.port in 1..65535) { "服务端口无效" }
            require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写有效模型名" }
            require(key.isNotBlank() && key.length <= 8192 && key.none { it.isISOControl() }) { "请重新填写有效API Key" }
            val path = uri.path.orEmpty().trimEnd('/')
            require(!path.contains("..")) { "服务地址路径无效" }
            val endpointPath = if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
            val endpoint = URI("https", null, uri.host.lowercase(), uri.port, endpointPath, null, null).toASCIIString()
            return AiServiceConfig(endpoint, model.trim(), key.trim(), revision)
        }
    }
}

class AiBudgetException : IllegalArgumentException("所选文字超出处理预算，请缩小日期范围；未发送任何日记")
class AiNoContentException : IllegalStateException("这段时间还没有可总结的文字日记")
class AiStaleException : IllegalStateException("日记、配置或授权已变化，请重新确认范围并生成")
class AiProtocolException : IllegalStateException("服务返回的五段结构或来源无效，请检查兼容接口或重新生成")
class AiNetworkException(val sent: Boolean, val retryable: Boolean, val pauseAutomatic: Boolean = false) : IllegalStateException(
    if (sent) "请求可能已发送，结果未知；不会自动重发，请检查服务后手动重试" else "尚未发送日记，请检查网络和服务配置",
)

interface AiCompletionGateway {
    suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection> = emptyList(), beforeSend: suspend () -> Unit): List<AiReviewSection>
    suspend fun probe(config: AiServiceConfig)
}

enum class AiPeriodKind { WEEK, MONTH }
data class AiAutoConsent(val kind: AiPeriodKind, val enabledOn: LocalDate, val configRevision: Long, val origin: String, val enabled: Boolean,
    val generation: Long = 0)
data class AiPeriod(val kind: AiPeriodKind, val from: LocalDate, val through: LocalDate) { val key get() = "${kind.name}:$from" }
enum class AiJobState { RETRYABLE_UNSENT, SENT_UNKNOWN, SUCCEEDED, PAUSED, EMPTY }
data class AiAutoJob(val period: AiPeriod, val configRevision: Long, val generation: Long, val state: AiJobState, val attempts: Int, val notBeforeMillis: Long = 0)
interface AiStateStore {
    suspend fun config(): AiServiceConfig?
    suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig
    suspend fun consents(): List<AiAutoConsent>
    suspend fun setConsent(consent: AiAutoConsent)
    suspend fun jobs(): List<AiAutoJob>
    suspend fun putJob(job: AiAutoJob)
    suspend fun pauseAutomatic()
}
