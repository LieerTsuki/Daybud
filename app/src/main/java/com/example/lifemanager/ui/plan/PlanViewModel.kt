package com.example.lifemanager.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Unified list owner; the Activity's retained factory supplies the production domain ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(private val repository: PlanRepository, private val useCases: PlanUseCases,
    private val access: GenerationAccess, private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemDefaultZone()) : ViewModel() {
    private val state = MutableStateFlow(PlanUiState())
    val uiState = state.asStateFlow()
    private val filter = MutableStateFlow(PlanFilter(status = PlanStatus.ALL))
    private val refreshes = MutableStateFlow(0L)
    private val zone = MutableStateFlow(clock.zone)
    // Owned by Main; an old operation must never release a same-ID operation in a new generation.
    private val operations = mutableMapOf<Long, ListOperation>()

    init {
        viewModelScope.launch(dispatcher) {
            refreshes.collectLatest { observeList() }
        }
    }

    private suspend fun observeList() {
        try {
            combine(access.generations, access.maintenance, filter, zone) { generation, phase, selected, displayZone ->
                ReadContext(generation, phase, selected, displayZone)
            }.collectLatest { context ->
                withContext(Dispatchers.Main.immediate) {
                    if (state.value.generation != context.generation) {
                        operations.clear()
                        state.value = PlanUiState(generation = context.generation, filter = context.filter)
                    }
                    state.update { it.copy(isMaintaining = context.phase != MaintenanceState.IDLE) }
                }
                if (context.phase != MaintenanceState.IDLE) return@collectLatest
                try {
                    // Streams invalidate only. Never stamp a buffered DTO with a newer token.
                    merge(repository.observeAll().map { Unit }, repository.observeTags().map { Unit }).collect {
                        access.read({
                            val plans = repository.observeAll().first()
                            val tags = repository.observeTags().first()
                            val exceptions = plans.filter { it.isScheduled }.associate { it.id to repository.getExceptions(it.id) }
                            Snapshot(PlanListQuery.select(plans, context.filter, clock.instant(), context.zone, exceptions), tags)
                        }) { token, fresh ->
                            if (state.value.generation != token) {
                                operations.clear()
                                state.value = PlanUiState(generation = token, filter = context.filter)
                            }
                            state.update { it.copy(rows = fresh.rows, tags = fresh.tags, filter = context.filter,
                                isAvailable = true, isLoading = false, readError = null, displayZone = context.zone) }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) {
                        if (state.value.generation == context.generation)
                            state.update { it.copy(isAvailable = false, isLoading = false, readError = "读取计划失败，请重新读取") }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) {
                state.update { it.copy(isAvailable = false, isLoading = false, readError = "读取数据世代失败，请重新读取") }
            }
        }
    }

    fun onFilterChanged(filter: PlanFilter, rendered: DataGeneration?) {
        if (eventToken(rendered) == null || this.filter.value == filter) return
        state.update { it.copy(filter = filter, isLoading = true) }
        this.filter.value = filter
    }

    fun setCompleted(id: Long, completed: Boolean, rendered: DataGeneration?) {
        val token = eventToken(rendered) ?: return
        val current = state.value.rows.find { it.plan.id == id }?.plan ?: return
        if (state.value.isLoading || current.isCompleted == completed || id in operations) return
        launchOperation(id, token) { operation ->
            useCases.setCompleted(id, completed, token) { receipt ->
                // Already inside the use-case's global admission. No nested GenerationAccess permit.
                // Record a provisional receipt before fallible reminder work; cancelled work can
                // only retry reminders, not repeat the business write. UI hides it while pending.
                withContext(Dispatchers.Main.immediate) {
                    operation.receipt = receipt
                    if (owns(id, operation, receipt.generation)) state.update { it.copy(
                        reminderFailures = it.reminderFailures + (id to receipt),
                        message = if (completed) "已完成，可在原象限展开已完成计划" else "已撤销完成，计划回到原象限",
                    ) }
                }
            }
        }
    }

    fun retryReminders(id: Long, rendered: DataGeneration?) {
        val token = eventToken(rendered) ?: return
        val receipt = state.value.reminderFailures[id] ?: return
        if (receipt.generation != token || id in operations) return
        launchOperation(id, token, receipt) { useCases.retryReminders(receipt) }
    }

    private fun launchOperation(id: Long, token: DataGeneration, receipt: PlanWriteReceipt? = null,
        write: suspend (ListOperation) -> PlanWriteResult) {
        val operation = ListOperation(receipt)
        operations[id] = operation
        state.update { it.copy(pendingIds = it.pendingIds + id) }
        viewModelScope.launch(dispatcher) {
            try {
                val result = write(operation)
                operation.receipt = result.receipt
                access.publishResult(token) {
                    if (owns(id, operation, token)) state.update { it.copy(
                        reminderFailures = if (result.reminders is PlanReminderUpdate.Applied) it.reminderFailures - id
                            else it.reminderFailures + (id to result.receipt),
                    ) }
                }
            } catch (error: PlanCommittedCancellationException) {
                // onCommitted handed off the receipt under protected admission before cancellation.
                // Do not wait on a possibly user-held maintenance freeze inside NonCancellable.
                throw error
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is PlanCommitHandoffException) operation.receipt = error.receipt
                try {
                    access.publishResult(token) {
                        if (owns(id, operation, token)) state.update { it.copy(
                            reminderFailures = operation.receipt?.let { saved -> it.reminderFailures + (id to saved) } ?: it.reminderFailures,
                            message = if (operation.receipt != null) "已保存，但后续状态核对失败；请重新读取，勿重复提交"
                                else "操作失败，未保存；请检查后重试",
                        ) }
                    }
                } catch (publication: Exception) {
                    if (publication is CancellationException) throw publication
                    withContext(Dispatchers.Main.immediate) {
                        if (owns(id, operation, token)) state.update { it.copy(isAvailable = false, readError = "无法核对数据世代，请重新读取") }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (owns(id, operation, token)) {
                        operations.remove(id)
                        state.update { it.copy(pendingIds = it.pendingIds - id) }
                    }
                }
            }
        }
    }

    private fun owns(id: Long, operation: ListOperation, token: DataGeneration) =
        state.value.generation == token && operations[id] === operation

    private fun eventToken(rendered: DataGeneration?): DataGeneration? {
        if (rendered != state.value.generation || !state.value.isAvailable) return null
        return try { access.eventToken(rendered) } catch (error: IllegalStateException) {
            state.update { it.copy(message = error.message) }; null
        }
    }

    fun retryRead() { refreshes.update { it + 1 } }
    fun refresh(displayZone: ZoneId = clock.zone) { zone.value = displayZone; retryRead() }
    fun dismissMessage() { state.update { it.copy(message = null) } }

    private data class ReadContext(val generation: DataGeneration, val phase: MaintenanceState, val filter: PlanFilter, val zone: ZoneId)
    private class ListOperation(var receipt: PlanWriteReceipt?)
    private data class Snapshot(val rows: List<PlanListRow>, val tags: List<com.example.lifemanager.domain.model.Tag>)
}
