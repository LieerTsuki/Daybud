package com.example.lifemanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.BuildConfig
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class V1BackupOutcome { NONE, UNCHANGED, EXPORTED, COMMITTED, UNKNOWN }

data class V1BackupUiState(
    val operationId: String? = null,
    val action: BackupAction? = null,
    val step: BackupStep = BackupStep.IDLE,
    val rowCounts: Map<String, Int> = emptyMap(),
    val sourceDate: String? = null,
    val sourceFormat: BackupSourceFormat? = null,
    val legacyTodoTimeZone: String? = null,
    val protectionVerified: Boolean = false,
    val outcome: V1BackupOutcome = V1BackupOutcome.NONE,
    val remindersPending: Boolean = false,
    val checkingOutcome: Boolean = false,
    val message: String? = null,
) {
    val busy get() = checkingOutcome || step !in setOf(BackupStep.IDLE, BackupStep.FINISHED, BackupStep.ERROR)
    val canCancel get() = busy && !checkingOutcome && step !in setOf(BackupStep.COMMITTING, BackupStep.RECONCILING)
}

/** All injected ports must share the application's database/coordinator. */
class V1BackupSettingsViewModel(
    private val files: V2BackupFileUseCases,
    private val workflow: V2BackupMaintenanceWorkflow,
    private val snapshots: V2BackupRepository,
    private val access: GenerationAccess,
    private val dispatcher: CoroutineDispatcher,
    private val legacyZone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val state = MutableStateFlow(V1BackupUiState())
    val uiState = state.asStateFlow()
    private var active: Operation? = null

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun start(action: BackupAction, generation: DataGeneration?) {
        if (state.value.busy || state.value.outcome == V1BackupOutcome.UNKNOWN) return
        val token = try { access.eventToken(generation) } catch (_: IllegalStateException) { return }
        val operation = Operation(UUID.randomUUID().toString(), action, token)
        active = operation
        state.value = V1BackupUiState(operation.id, action, BackupStep.PREPARING)
        // Enter cleanup even if cancellation wins before dispatcher entry; no I/O before the guard.
        operation.job = viewModelScope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                checkOriginal(operation)
                when (action) {
                    BackupAction.EXPORT -> export(operation)
                    BackupAction.RESTORE -> restore(operation)
                    BackupAction.CLEAR -> clear(operation)
                }
            } catch (error: CancellationException) {
                val outcome = when {
                    error is BackupCommitOutcomeUnknownCancellationException -> V1BackupOutcome.UNKNOWN
                    operation.committed || error is BackupCommittedCancellationException -> V1BackupOutcome.COMMITTED
                    else -> V1BackupOutcome.UNCHANGED
                }
                finish(operation, outcome, when (outcome) {
                    V1BackupOutcome.UNKNOWN -> "取消时无法确认提交结果。请先重新读取当前数据，勿重复恢复或清空。"
                    V1BackupOutcome.COMMITTED -> "${done(operation)}；后续提醒处理被中断，请重新打开应用检查提醒。"
                    else -> "已取消，原数据和草稿已保留。未完成的输出文件可能仍存在，请自行核对。"
                }, pending = outcome == V1BackupOutcome.COMMITTED)
                throw error
            } catch (error: Exception) {
                val unknown = error is BackupCommitOutcomeUnknownException
                val message = when (error) {
                    is BackupCommitOutcomeUnknownException -> "无法确认提交结果。请先重新读取当前数据，勿重复恢复或清空。"
                    is BackupValidationException -> "备份无法使用，未执行恢复；请检查文件格式、完整性或大小。"
                    is BackupFileException -> "文件操作未完成，未执行数据替换；请核对读写权限和输出文件。"
                    is StaleGenerationException -> "数据已更换，本次旧操作已失效，请重新开始。"
                    is MaintenanceBusyException -> "正在处理另一项数据维护，本次操作未执行，请稍后重新开始。"
                    else -> "操作未完成，请重新读取当前数据后检查。"
                }
                finish(operation, if (unknown) V1BackupOutcome.UNKNOWN else V1BackupOutcome.UNCHANGED, message, failed = true)
            }
        }
    }

    fun sourceSelected(id: String, location: String?) {
        matching(id, BackupStep.PICK_SOURCE)?.source?.complete(location?.let(::BackupLocation))
    }
    fun outputSelected(id: String, location: String?) {
        matching(id, BackupStep.PICK_OUTPUT)?.output?.complete(location?.let(::BackupLocation))
    }
    fun answerFirst(id: String, choice: Boolean?) {
        val operation = active?.takeIf { it.id == id } ?: return
        if (state.value.step in setOf(BackupStep.EXPORT_NOTICE, BackupStep.RESTORE_PREVIEW, BackupStep.CLEAR_CHOICE)) operation.first.complete(choice)
    }
    fun answerFinal(id: String, confirmed: Boolean) {
        matching(id, BackupStep.FINAL_CONFIRM)?.final?.complete(confirmed)
    }
    fun pickerFailed(id: String) {
        val error = BackupFileException("系统文件选择器不可用")
        matching(id, BackupStep.PICK_SOURCE)?.source?.completeExceptionally(error)
        matching(id, BackupStep.PICK_OUTPUT)?.output?.completeExceptionally(error)
    }
    fun cancel(id: String) {
        active?.takeIf { it.id == id && state.value.canCancel }?.job?.cancel()
    }

    /** Explicit acknowledgement only: verify current persisted generation AND all snapshot facts.
     * Never retry the mutation, import generation, or infer rollback from an unreadable row. */
    fun acknowledgeUnknown() {
        if (state.value.outcome != V1BackupOutcome.UNKNOWN || state.value.busy) return
        val id = state.value.operationId
        state.update { it.copy(checkingOutcome = true) }
        viewModelScope.launch(dispatcher) {
            try {
                access.read({ snapshots.snapshot().also { payload ->
                    V3BackupValidator.validate(document(payload))
                    payload.diary?.let(com.example.lifemanager.domain.diary.DiaryBackupValidator::validate)
                } }) { _, _ ->
                    if (state.value.operationId == id) state.value = V1BackupUiState(message = "已重新读取当前数据。请先核对记录，原操作不会自动重试。")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                state.update { if (it.operationId == id) it.copy(message = "当前数据仍无法读取，提交结果未确认；禁止重复恢复或清空。") else it }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    state.update { if (it.operationId == id) it.copy(checkingOutcome = false) else it }
                }
            }
        }
    }

    private suspend fun export(operation: Operation) {
        publish(operation, BackupStep.EXPORT_NOTICE)
        if (operation.first.await() != true) return unchanged(operation)
        checkOriginal(operation)
        val output = chooseOutput(operation) ?: return unchanged(operation)
        publish(operation, BackupStep.WRITING_BACKUP)
        files.export(output, BuildConfig.VERSION_NAME, Instant.now(clock).toString(), operation.generation)
        finish(operation, V1BackupOutcome.EXPORTED, "完整 V4 备份已导出并重读验证，包含日记和媒体。文件未加密，请保存到可信位置。")
    }
    private suspend fun restore(operation: Operation) {
        // Capture once per operation: device zone changes must not re-interpret a confirmed source.
        val zone = legacyZone()
        publish(operation, BackupStep.PICK_SOURCE)
        val source = operation.source.await() ?: return unchanged(operation)
        checkOriginal(operation)
        publish(operation, BackupStep.READING_SOURCE)
        val preview = files.preview(source, zone)
        try {
            checkOriginal(operation)
            state.update { if (it.operationId == operation.id) it.copy(rowCounts = preview.rowCounts,
                sourceDate = preview.document.exportedAt, sourceFormat = preview.sourceFormat,
                legacyTodoTimeZone = preview.legacyTodoTimeZone) else it }
            publish(operation, BackupStep.RESTORE_PREVIEW)
            if (operation.first.await() != true) return unchanged(operation)
            maintain(operation, V2BackupMaintenanceRequest.Restore(operation.generation, source, preview.document))
        } finally { files.releasePreview(preview.document) }
    }
    private suspend fun clear(operation: Operation) {
        publish(operation, BackupStep.CLEAR_CHOICE)
        val backup = operation.first.await() ?: return unchanged(operation)
        maintain(operation, V2BackupMaintenanceRequest.Clear(operation.generation, backup))
    }
    private suspend fun maintain(operation: Operation, request: V2BackupMaintenanceRequest) {
        val result = workflow.execute(request, BuildConfig.VERSION_NAME, Instant.now(clock).toString(), object : BackupMaintenanceInteraction {
            override fun progress(stage: BackupMaintenanceStage) {
                publish(operation, when (stage) {
                    BackupMaintenanceStage.PREPARING, BackupMaintenanceStage.CHOOSING_PROTECTION, BackupMaintenanceStage.CONFIRMING -> BackupStep.PREPARING
                    BackupMaintenanceStage.WRITING_PROTECTION -> BackupStep.WRITING_BACKUP
                    BackupMaintenanceStage.COMMITTING -> BackupStep.COMMITTING
                    BackupMaintenanceStage.RECONCILING -> BackupStep.RECONCILING
                })
            }
            override suspend fun chooseProtection() = chooseOutput(operation)
            override suspend fun confirm(protection: BackupLocation?): Boolean {
                state.update { if (it.operationId == operation.id) it.copy(protectionVerified = protection != null) else it }
                publish(operation, BackupStep.FINAL_CONFIRM)
                return operation.final.await()
            }
        })
        // Durable outcome before any cancellable UI dispatcher bridge; never redo SQL for effects.
        operation.committed = result.committed
        if (!result.committed) unchanged(operation)
        else finish(operation, V1BackupOutcome.COMMITTED,
            if (result.remindersPending) "${done(operation)}；部分提醒未确认，请重新打开应用检查提醒。"
            else "${done(operation)}，提醒已重新校准。", pending = result.remindersPending)
    }
    private suspend fun chooseOutput(operation: Operation): BackupLocation? {
        operation.output = CompletableDeferred()
        publish(operation, BackupStep.PICK_OUTPUT)
        return operation.output!!.await()
    }
    private suspend fun checkOriginal(operation: Operation) = access.run(operation.generation) { currentCoroutineContext().ensureActive() }
    private suspend fun unchanged(operation: Operation) = finish(operation, V1BackupOutcome.UNCHANGED, "已取消，原数据和草稿已保留。")
    private fun done(operation: Operation) = if (operation.action == BackupAction.RESTORE) "数据已恢复" else "业务数据已清空，设置已保留"
    private fun publish(operation: Operation, step: BackupStep) {
        state.update { if (active === operation && it.operationId == operation.id) it.copy(step = step, message = null) else it }
    }
    private suspend fun finish(operation: Operation, outcome: V1BackupOutcome, message: String, pending: Boolean = false, failed: Boolean = false) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (active !== operation) return@withContext
            active = null
            state.update { it.copy(step = if (failed) BackupStep.ERROR else BackupStep.FINISHED,
                outcome = outcome, remindersPending = pending, message = message) }
        }
    }
    private fun matching(id: String, step: BackupStep) = active?.takeIf { it.id == id && state.value.step == step }
    private fun document(payload: V2BackupPayload) = V2BackupDocument(BuildConfig.VERSION_NAME, Instant.now(clock).toString(), payload)
    private class Operation(val id: String, val action: BackupAction, val generation: DataGeneration) {
        var job: Job? = null
        var committed = false
        val source = CompletableDeferred<BackupLocation?>()
        var output: CompletableDeferred<BackupLocation?>? = null
        val first = CompletableDeferred<Boolean?>()
        val final = CompletableDeferred<Boolean>()
    }
}
