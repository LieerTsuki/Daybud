package com.example.lifemanager.ui.diary.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.data.diary.ai.AiGenerationPreview
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiaryAiUiState(
    val from: String = LocalDate.now().withDayOfMonth(1).toString(), val through: String = LocalDate.now().toString(),
    val preview: AiGenerationPreview? = null, val result: AiDiaryReview? = null, val reviews: List<AiDiaryReview> = emptyList(),
    val busy: Boolean = false, val message: String? = null, val endpoint: String = "", val model: String = "",
    val serviceOrigin: String = "", val configured: Boolean = false, val consents: List<AiAutoConsent> = emptyList(),
    val jobs: List<AiAutoJob> = emptyList(), val generation: Long? = null,
)

/** Activity-owned by the root factory; no key, snapshot or result is written to SavedStateHandle. */
class DiaryAiViewModel(private val engine: DiaryAiEngine, private val source: DiaryAiDataSource, private val store: AiStateStore) : ViewModel() {
    private val mutableState = MutableStateFlow(DiaryAiUiState())
    val uiState = mutableState.asStateFlow()
    private var request = 0L
    private var work: Job? = null
    private var reviewedPreview: AiGenerationPreview? = null
    init {
        refreshSettings()
        viewModelScope.launch {
            try {
                source.observeReviewSnapshots().collect { (generation, reviews) ->
                    mutableState.update { it.copy(reviews = reviews, generation = generation) }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.update { it.copy(message = "回顾读取失败，请重新打开页面") } }
        }
        viewModelScope.launch {
            try {
                source.observeInvalidations().collectLatest {
                    val preview = mutableState.value.preview ?: return@collectLatest
                    if (!source.isCurrent(preview.snapshot)) {
                        cancel(); reviewedPreview = null
                        mutableState.update { it.copy(preview = null, result = null, message = "来源日记已变化，请重新检查范围") }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { cancel(); reviewedPreview = null; mutableState.update { it.copy(preview = null, result = null, message = "来源状态无法核对，请重新打开页面") } }
        }
    }
    fun range(from: String, through: String) {
        cancel(); reviewedPreview = null
        mutableState.update { it.copy(from = from, through = through, preview = null, result = null, message = null) }
    }
    fun preset(kind: AiPeriodKind) {
        val now = LocalDate.now()
        val from = if (kind == AiPeriodKind.WEEK) now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1) else now.withDayOfMonth(1).minusMonths(1)
        val through = if (kind == AiPeriodKind.WEEK) from.plusDays(6) else from.plusMonths(1).minusDays(1)
        range(from.toString(), through.toString())
    }
    fun prepare() = launchAction {
        val state = mutableState.value
        val from = try { LocalDate.parse(state.from) } catch (_: Exception) { throw IllegalArgumentException("日期格式为YYYY-MM-DD") }
        val through = try { LocalDate.parse(state.through) } catch (_: Exception) { throw IllegalArgumentException("日期格式为YYYY-MM-DD") }
        val preview = engine.preview(from, through)
        reviewedPreview = preview
        mutableState.update { it.copy(preview = preview, result = null, message = if (preview.plan.includedCount == 0) "这段时间还没有可总结的文字日记" else null) }
    }
    fun generate(preview: AiGenerationPreview) {
        if (mutableState.value.preview !== preview) return
        launchAction {
            val result = engine.generate(preview)
            if (mutableState.value.preview === preview) mutableState.update { it.copy(result = result, message = "模型生成内容，请结合来源核对") }
        }
    }
    fun save(result: AiDiaryReview) {
        if (mutableState.value.result !== result) return
        val preview = reviewedPreview ?: return
        launchAction {
            if (!engine.save(result, preview)) throw AiStaleException()
            mutableState.update { it.copy(message = "回顾已保存") }
        }
    }
    fun delete(id: String, generation: Long) = launchAction {
        source.deleteReview(id, generation)
        mutableState.update { it.copy(message = "回顾已删除") }
    }
    fun cancel() { request++; work?.cancel(); work = null; mutableState.update { it.copy(busy = false) } }
    fun refreshSettings() { viewModelScope.launch { try { readSettings() } catch (_: Exception) { mutableState.update { it.copy(configured = false, message = "AI配置或密钥不可用，请重新填写") } } } }
    private suspend fun readSettings() {
        val config = store.config(); val generation=source.currentGeneration()
        val consents = store.consents().map { if(it.generation==generation)it else it.copy(enabled=false) }; val jobs = store.jobs()
        mutableState.update { it.copy(endpoint = config?.endpoint.orEmpty(), model = config?.model.orEmpty(), serviceOrigin = config?.origin.orEmpty(), configured = config != null, consents = consents, jobs = jobs) }
    }
    fun saveConfiguration(address: String, model: String, key: String, onSaved: () -> Unit = {}) = launchAction {
        store.saveConfig(address, model, key)
        reviewedPreview = null
        mutableState.update { it.copy(preview = null, result = null) }
        readSettings(); onSaved()
        mutableState.update { it.copy(message = "配置已保存；自动总结已暂停，请重新确认开启") }
    }
    fun testConnection() = launchAction { engine.testConnection(); mutableState.update { it.copy(message = "连接检查通过，未发送日记") } }
    fun automatic(kind: AiPeriodKind, enabled: Boolean) = launchAction {
        engine.setAutomatic(kind, enabled); readSettings()
        mutableState.update { it.copy(message = if (enabled) "已开启，起点为当前所在周/月；当地09:00后由系统择机处理" else "自动总结已关闭，尚未发送的请求已失效") }
    }
    private fun launchAction(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        val token = ++request
        mutableState.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == token) mutableState.update { it.copy(message = when (error) {
                    is AiStaleException, is AiBudgetException, is AiNoContentException, is AiProtocolException, is AiNetworkException -> error.message
                    is IllegalArgumentException -> "请检查日期、HTTPS服务地址、模型和API Key"
                    else -> "操作未完成，请检查配置或稍后重试"
                }) }
            } finally { if (request == token) mutableState.update { it.copy(busy = false) } }
        }
    }
}
