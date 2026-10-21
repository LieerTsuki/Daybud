package com.example.lifemanager.ui.diary.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.data.diary.ai.AiGenerationPreview
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.data.diary.ai.AiModeStore
import com.example.lifemanager.data.diary.ai.local.*
import com.example.lifemanager.domain.diary.ai.*
import java.io.InputStream
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
    val mode: AiExecutionMode = AiExecutionMode.CLOUD,
    val localModels: List<LocalModelSpec> = emptyList(), val readyModelIds: Set<String> = emptySet(),
    val progress: String? = null,
    val localPreparationBlocked: Set<String> = emptySet(), val localDeviceMessage: String? = null,
)

/** Activity-owned by the root factory; no key, snapshot or result is written to SavedStateHandle. */
class DiaryAiViewModel(private val engine: DiaryAiEngine, private val source: DiaryAiDataSource, private val store: AiStateStore,
                       private val models: LocalModelStore? = null,
                       private val device: () -> LocalDeviceSnapshot? = { null }) : ViewModel() {
    private val mutableState = MutableStateFlow(DiaryAiUiState())
    val uiState = mutableState.asStateFlow()
    private var request = 0L
    private var work: Job? = null
    private var cancellation: Job? = null
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
            val token = request
            val result = engine.generate(preview) { progress -> if (request == token) mutableState.update { it.copy(progress = progress) } }
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
    fun cancel() {
        request++
        val previousWork = work
        previousWork?.cancel(); work = null
        val priorCancellation = cancellation
        cancellation = viewModelScope.launch { priorCancellation?.join(); models?.cancelImport(); previousWork?.join() }
        mutableState.update { it.copy(busy = false, progress = null) }
    }
    fun refreshSettings() {
        val token = request
        viewModelScope.launch {
            try { readSettings() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (request == token) mutableState.update { it.copy(configured = false, message = "AI配置或密钥不可用，请重新填写或选择模式") } }
        }
    }
    private suspend fun readSettings() {
        val token = request
        val config = store.config(); val generation=source.currentGeneration()
        val consents = store.consents().map { if(it.generation==generation)it else it.copy(enabled=false) }; val jobs = store.jobs()
        val ready = models?.readyModels()?.map { it.id }?.toSet().orEmpty()
        val snapshot = device()
        val blocked = if (snapshot == null) emptySet() else LocalModelCatalog.models.filter {
            !LocalDeviceCapability.evaluate(snapshot, it, forImport = true).allowed
        }.map { it.id }.toSet()
        val deviceMessage = when {
            snapshot == null -> null
            !snapshot.is64BitProcess || snapshot.supportedAbis.none { it in setOf("arm64-v8a", "x86_64") } -> "当前设备或应用进程不支持64位离线推理；日记等原有功能仍可使用。"
            snapshot.lowMemory -> "系统当前可用内存较少，建议关闭其他应用后再生成。"
            else -> "当前可用内存约${snapshot.availableMemoryBytes / (1024 * 1024)} MB；运行时会再次检查资源。"
        }
        val mode = config?.mode ?: (store as? AiModeStore)?.mode() ?: AiExecutionMode.CLOUD
        if (request == token) mutableState.update { it.copy(endpoint = config?.endpoint.orEmpty(), model = config?.model.orEmpty(), serviceOrigin = config?.origin.orEmpty(),
            configured = config != null && (mode == AiExecutionMode.CLOUD || config.model in ready), consents = consents, jobs = jobs,
            mode = mode, localModels = if (models == null) emptyList() else LocalModelCatalog.models, readyModelIds = ready,
            localPreparationBlocked = blocked, localDeviceMessage = deviceMessage) }
    }
    fun selectCloud() {
        cancel()
        launchAction {
            (store as? AiModeStore)?.selectCloud() ?: return@launchAction
            reviewedPreview = null
            mutableState.update { it.copy(preview = null, result = null) }
            readSettings()
        }
    }
    fun selectLocal(modelId: String? = null) {
        val current = mutableState.value
        if (current.mode == AiExecutionMode.LOCAL && (modelId == null || modelId == current.model)) return
        cancel()
        launchAction {
            val spec = LocalModelCatalog.models.firstOrNull { it.id == modelId } ?: LocalModelCatalog.models.first()
            (store as? AiModeStore)?.selectLocal(spec.id, spec.sha256) ?: return@launchAction
            reviewedPreview = null
            mutableState.update { it.copy(preview = null, result = null) }
            readSettings()
            mutableState.update { it.copy(message = "已选择手机离线；云端自动回顾已暂停。模型就绪后可断网生成。") }
        }
    }
    fun importLocal(modelId: String, open: () -> InputStream) = launchAction {
        val manager = models ?: return@launchAction
        val token = request
        manager.importModel(modelId, open) { copied, total ->
            if (request == token) mutableState.update { it.copy(progress = "正在准备模型 ${copied / (1024 * 1024)}/${total / (1024 * 1024)} MB") }
        }
        readSettings()
        mutableState.update { it.copy(message = "模型已校验并就绪，可检查模型后离线生成") }
    }
    fun downloadLocal(modelId: String) {
        val spec = LocalModelCatalog.models.firstOrNull { it.id == modelId } ?: return
        importLocal(modelId) { LocalModelDownload.open(spec) }
    }
    fun deleteLocal(modelId: String) = launchAction {
        models?.deleteModel(modelId)
        reviewedPreview = null
        mutableState.update { it.copy(preview = null, result = null) }
        readSettings()
        mutableState.update { it.copy(message = "离线模型已删除，日记和已保存回顾仍保留") }
    }
    fun saveConfiguration(address: String, model: String, key: String, onSaved: () -> Unit = {}) = launchAction {
        store.saveConfig(address, model, key)
        reviewedPreview = null
        mutableState.update { it.copy(preview = null, result = null) }
        readSettings(); onSaved()
        mutableState.update { it.copy(message = "配置已保存；自动总结已暂停，请重新确认开启") }
    }
    fun testConnection() = launchAction { engine.testConnection(); mutableState.update { it.copy(message = if (it.mode == AiExecutionMode.LOCAL) "离线模型加载和分词检查通过，未发送日记" else "连接检查通过，未发送日记") } }
    fun automatic(kind: AiPeriodKind, enabled: Boolean) = launchAction {
        engine.setAutomatic(kind, enabled); readSettings()
        mutableState.update { it.copy(message = if (enabled) "已开启，起点为当前所在周/月；当地09:00后由系统择机处理" else "自动总结已关闭，尚未发送的请求已失效") }
    }
    private fun launchAction(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        val token = ++request
        mutableState.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch {
            try { cancellation?.join(); action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == token) mutableState.update { it.copy(message = when (error) {
                    is AiStaleException, is AiBudgetException, is AiNoContentException, is AiProtocolException, is AiNetworkException,
                    is LocalAiException, is LocalModelStoreException, is PhoneAiUnavailableException -> error.message
                    is IllegalArgumentException -> "请检查日期、HTTPS服务地址、模型和API Key"
                    else -> "操作未完成，请检查配置或稍后重试"
                }) }
            } finally { if (request == token) mutableState.update { it.copy(busy = false, progress = null) } }
        }
    }
}
