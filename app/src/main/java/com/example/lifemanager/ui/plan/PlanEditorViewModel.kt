package com.example.lifemanager.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.Clock
import java.time.DayOfWeek
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Prepared workspace owner; production Hilt activation remains later in E. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditorViewModel(private val repository:PlanRepository,private val cases:PlanUseCases,
    private val access:GenerationAccess,private val sources:PlanReminderSources,
    private val dispatcher:CoroutineDispatcher,private val clock:Clock=Clock.systemDefaultZone()):ViewModel() {
    private val state=MutableStateFlow(PlanEditorState())
    val uiState=state.asStateFlow()
    private val target=MutableStateFlow<Target?>(null)
    private val refresh=MutableStateFlow(0L)
    // Main owns selection/draft/operations. Late callbacks are bound to generation AND session.
    private var sequence=0L
    private var pending:Target?=null
    private var active:Operation?=null

    init { viewModelScope.launch(dispatcher) { refresh.collectLatest { observe() } } }

    private suspend fun observe() {
        try {
            combine(access.generations,access.maintenance,target) { generation,phase,selected -> Context(generation,phase,selected) }
                .collectLatest { context ->
                    withContext(Dispatchers.Main.immediate) {
                        if(state.value.stamp.generation!=context.generation) invalidate(context.generation)
                        state.update { it.copy(isMaintaining=context.phase!=MaintenanceState.IDLE) }
                    }
                    if(context.phase!=MaintenanceState.IDLE || context.selected?.generation?.let { it!=context.generation }==true) return@collectLatest
                    try {
                        val invalidations=if(context.selected?.id!=null) repository.observeAll().map { Unit } else flowOf(Unit)
                        invalidations.collect {
                            access.read({ Snapshot(context.selected?.id?.let { repository.get(it) },sources.configuration().planReminders) }) { token,fresh ->
                                if(context.selected?.generation?.let { it!=token }==true) { invalidate(token);return@read }
                                if(target.value!=context.selected) return@read
                                if(state.value.stamp.generation!=token) invalidate(token)
                                val missing=context.selected?.id!=null && fresh.plan==null
                                state.update {
                                    val changed=it.pane==PlanPane.DETAIL && it.detail!=null && it.detail!=fresh.plan
                                    it.copy(detail=fresh.plan,isAvailable=!missing,isLoading=false,remindersEnabled=fresh.enabled,
                                    stamp=if(changed)it.stamp.copy(revision=it.stamp.revision+1)else it.stamp,
                                    confirmDelete=if(changed)false else it.confirmDelete,
                                    error=if(missing) "记录已删除，旧表单不能保存" else if(it.isAvailable)it.error else null) }
                            }
                        }
                    } catch(error:Exception) {
                        if(error is CancellationException) throw error
                        withContext(Dispatchers.Main.immediate) {
                            if(state.value.stamp.generation==context.generation && target.value==context.selected)
                                state.update { it.copy(isAvailable=false,isLoading=false,remindersEnabled=null,error="读取计划或提醒设置失败，请重新读取") }
                        }
                    }
                }
        } catch(error:Exception) {
            if(error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) { state.update { it.copy(isAvailable=false,isLoading=false,error="读取世代失败，请重新读取") } }
        }
    }

    private fun invalidate(token:DataGeneration) {
        val hadDraft=state.value.draft!=null
        pending=null;active=null;target.value=null
        state.value=PlanEditorState(stamp=PlanEditorStamp(token,++sequence,0),message=if(hadDraft)"数据已替换，旧草稿已失效"else null)
    }

    fun openNew(rendered:DataGeneration?)=requestTarget(null,rendered)
    fun open(id:Long,rendered:DataGeneration?) { if(id>0)requestTarget(id,rendered) }
    private fun requestTarget(id:Long?,rendered:DataGeneration?) {
        val current=state.value
        if(rendered!=current.stamp.generation || !allowed(current.stamp,false)) return
        val selected=Target(id,checkNotNull(rendered),++sequence)
        if(dirty()) { pending=selected;state.update { it.copy(confirmClose=true,stamp=it.stamp.copy(revision=it.stamp.revision+1)) } } else select(selected)
    }
    private fun select(selected:Target) {
        val zone=clock.zone.id.takeIf { it in java.time.ZoneId.getAvailableZoneIds() } ?: "UTC"
        val draft=if(selected.id==null)PlanDraft(Plan(title="",timeZone=zone,createdAt=clock.instant(),updatedAt=clock.instant()))else null
        state.update { it.copy(stamp=PlanEditorStamp(selected.generation,selected.session,0),pane=if(draft==null)PlanPane.DETAIL else PlanPane.EDIT,
            detail=null,draft=draft,isAvailable=false,isLoading=true,error=null,pendingMode=null,confirmClose=false,confirmDelete=false,conflicts=null) }
        pending=null;target.value=selected
    }

    fun beginEdit(stamp:PlanEditorStamp) {
        if(!allowed(stamp,true) || state.value.pane!=PlanPane.DETAIL) return
        val original=state.value.detail ?: return
        val selected=Target(original.id,checkNotNull(stamp.generation),++sequence)
        state.update { it.copy(pane=PlanPane.EDIT,draft=PlanDraft(original),stamp=PlanEditorStamp(selected.generation,selected.session,0),error=null,conflicts=null) }
        target.value=selected
    }
    private fun edit(stamp:PlanEditorStamp,transform:(PlanDraft)->PlanDraft) {
        if(!allowed(stamp,false) || state.value.pane!=PlanPane.EDIT) return
        val draft=state.value.draft ?: return
        pending=null
        state.update { it.copy(draft=transform(draft),stamp=it.stamp.copy(revision=it.stamp.revision+1),error=null,
            pendingMode=null,conflicts=null,confirmClose=false,confirmDelete=false) }
    }
    fun change(field:PlanField,value:String,stamp:PlanEditorStamp)=edit(stamp) { PlanDraftRules.edit(it,field,value) }
    fun setDateOnly(value:Boolean,stamp:PlanEditorStamp)=edit(stamp) { it.copy(dateOnly=value) }
    fun setPriority(value:TodoPriority,stamp:PlanEditorStamp)=edit(stamp) { it.copy(priority=value) }
    fun setRepeat(value:ScheduleRepeatRule,stamp:PlanEditorStamp)=edit(stamp) { it.copy(repeatRule=value) }
    fun toggleDay(value:DayOfWeek,stamp:PlanEditorStamp)=edit(stamp) { it.copy(repeatDays=if(value in it.repeatDays)it.repeatDays-value else it.repeatDays+value) }
    fun setReminder(value:Boolean,stamp:PlanEditorStamp)=edit(stamp) { it.copy(reminderEnabled=value) }
    fun addTag(stamp:PlanEditorStamp)=edit(stamp) {
        val name=it.newTag.trim();if(name.isEmpty())it else it.copy(tags=(it.tags+name).distinct(),newTag="")
    }
    fun removeTag(value:String,stamp:PlanEditorStamp)=edit(stamp) { it.copy(tags=it.tags.filterNot { name->name==value }) }
    fun setMode(mode:PlanTimeMode,stamp:PlanEditorStamp) {
        if(!allowed(stamp,true)) return
        val draft=state.value.draft ?: return
        if(draft.mode==mode) return
        val hasPreservedInterval=listOf(draft.startAt,draft.endAt,draft.allDayStart,draft.allDayEnd).any { it.isNotBlank() } || draft.repeatRule!=ScheduleRepeatRule.NONE
        if(draft.mode!=PlanTimeMode.NONE || (mode in listOf(PlanTimeMode.TIMED,PlanTimeMode.ALL_DAY) && hasPreservedInterval))
            state.update { it.copy(pendingMode=mode,stamp=it.stamp.copy(revision=it.stamp.revision+1),conflicts=null) }
        else edit(stamp) { it.copy(mode=mode) }
    }
    fun confirmMode(stamp:PlanEditorStamp) {
        if(!allowed(stamp,true)) return
        val mode=state.value.pendingMode ?: return
        edit(stamp) { it.copy(mode=mode) }
    }
    fun cancelMode(stamp:PlanEditorStamp) { if(matches(stamp,true))state.update { it.copy(pendingMode=null,stamp=it.stamp.copy(revision=it.stamp.revision+1)) } }
    fun cancelConflict(stamp:PlanEditorStamp) {
        if(matches(stamp,true) && !state.value.isBusy)state.update { it.copy(conflicts=null,error=null,stamp=it.stamp.copy(revision=it.stamp.revision+1)) }
    }

    fun save(stamp:PlanEditorStamp,confirmConflict:Boolean=false) {
        if(!allowed(stamp,true) || state.value.pendingMode!=null || state.value.confirmClose) return
        val draft=state.value.draft ?: return
        val proof=if(confirmConflict)state.value.conflicts ?: return else null
        val candidate=try { PlanDraftRules.build(draft) } catch(error:Exception) {
            state.update { it.copy(error="表单无效：${error.message.orEmpty()}") };return
        }
        launchOperation(stamp) { op ->
            cases.saveChecked(candidate,candidate.tagNames,checkNotNull(stamp.generation),PlanEditPolicy(draft.base.takeIf { it.id>0 },proof)) { receipt ->
                acknowledge(op,receipt,"已保存到${if(candidate.isScheduled)"日程"else"待办"}；当前列表筛选保持不变",receipt.planId)
            }
        }
    }
    fun setCompleted(value:Boolean,stamp:PlanEditorStamp) {
        if(!allowed(stamp,true) || state.value.pane!=PlanPane.DETAIL) return
        val plan=state.value.detail ?: return
        launchOperation(stamp) { op -> cases.setCompleted(plan.id,value,checkNotNull(stamp.generation)) {
            acknowledge(op,it,if(value) if(plan.isRepeating)"整个重复系列已完成"else"已完成" else "已撤销完成",plan.id)
        } }
    }
    fun requestDelete(stamp:PlanEditorStamp) { if(allowed(stamp,true) && state.value.pane==PlanPane.DETAIL)state.update { it.copy(confirmDelete=true,stamp=it.stamp.copy(revision=it.stamp.revision+1)) } }
    fun confirmDelete(stamp:PlanEditorStamp) {
        if(!allowed(stamp,true) || !state.value.confirmDelete) return
        val confirmed=state.value.detail ?: return
        launchOperation(stamp) { op -> cases.deleteChecked(confirmed,checkNotNull(stamp.generation)) { acknowledge(op,it,"已删除；子计划仅解除关联",null) } }
    }
    fun cancelDelete(stamp:PlanEditorStamp) { if(matches(stamp,true))state.update { it.copy(confirmDelete=false,stamp=it.stamp.copy(revision=it.stamp.revision+1)) } }

    private suspend fun acknowledge(op:Operation,receipt:PlanWriteReceipt,message:String,id:Long?) {
        op.receipt=receipt
        withContext(Dispatchers.Main.immediate) {
            if(!owns(op)) return@withContext
            val selected=id?.let { Target(it,receipt.generation,op.stamp.session) }
            state.update { it.copy(stamp=it.stamp.copy(revision=it.stamp.revision+1),pane=if(id==null)PlanPane.LIST else PlanPane.DETAIL,
                draft=null,detail=null,isLoading=true,message=message,error=null,pendingMode=null,conflicts=null,confirmClose=false,confirmDelete=false,
                reminderFailures=it.reminderFailures+(receipt.planId to receipt)) }
            target.value=selected
            // Same-ID edits may not change the selection flow; explicitly refresh the actual detail.
            retryRead()
        }
    }
    fun retryReminder(stamp:PlanEditorStamp,id:Long) {
        if(!allowed(stamp,true)) return
        val receipt=state.value.reminderFailures[id] ?: return
        launchOperation(stamp,receipt) { cases.retryReminders(receipt) }
    }
    private fun launchOperation(stamp:PlanEditorStamp,receipt:PlanWriteReceipt?=null,write:suspend (Operation)->PlanWriteResult) {
        val op=Operation(stamp,receipt);active=op
        state.update { it.copy(isBusy=true,error=null) }
        viewModelScope.launch(dispatcher) {
            try {
                val result=write(op);op.receipt=result.receipt
                access.publishResult(checkNotNull(stamp.generation)) {
                    if(owns(op))state.update { it.copy(reminderFailures=if(result.reminders is PlanReminderUpdate.Applied)it.reminderFailures-result.receipt.planId
                        else it.reminderFailures+(result.receipt.planId to result.receipt)) }
                }
            } catch(error:PlanCommittedCancellationException) { throw error
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(error is PlanCommitHandoffException)op.receipt=error.receipt
                try { access.publishResult(checkNotNull(stamp.generation)) {
                    if(owns(op))state.update {
                        val proof=(error as? PlanConflictConfirmationRequired)?.proof
                        it.copy(conflicts=proof,
                        stamp=if(proof!=null || it.conflicts!=null)it.stamp.copy(revision=it.stamp.revision+1)else it.stamp,
                        error=if(op.receipt!=null)"已保存，但后续核对失败；勿重复提交"else error.message ?: "操作失败，草稿已保留",
                        reminderFailures=op.receipt?.let { saved->it.reminderFailures+(saved.planId to saved) } ?: it.reminderFailures) }
                } } catch(publication:Exception) {
                    if(publication is CancellationException)throw publication
                    withContext(Dispatchers.Main.immediate) { if(owns(op))state.update { it.copy(isAvailable=false,error="无法核对世代，请重新读取") } }
                }
            } finally {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    if(owns(op)) { active=null;state.update { it.copy(isBusy=false) } }
                }
            }
        }
    }
    private fun owns(op:Operation)=active===op && matches(op.stamp,false)
    private fun matches(stamp:PlanEditorStamp,exact:Boolean)=stamp.generation==state.value.stamp.generation && stamp.session==state.value.stamp.session &&
        (!exact || stamp.revision==state.value.stamp.revision)
    private fun allowed(stamp:PlanEditorStamp,exact:Boolean):Boolean {
        if(!matches(stamp,exact) || !state.value.isAvailable || state.value.isBusy) return false
        return try { access.eventToken(stamp.generation);true } catch(error:IllegalStateException) { state.update { it.copy(error=error.message) };false }
    }
    private fun dirty()=state.value.draft?.let { it!=PlanDraft(it.base) }==true
    fun requestClose(stamp:PlanEditorStamp) {
        if(!matches(stamp,true) || state.value.isBusy || access.maintenance.value!=MaintenanceState.IDLE) return
        pending=null
        if(dirty())state.update { it.copy(confirmClose=true,stamp=it.stamp.copy(revision=it.stamp.revision+1)) }else close()
    }
    fun confirmClose(stamp:PlanEditorStamp) {
        if(!matches(stamp,true) || state.value.isBusy || access.maintenance.value!=MaintenanceState.IDLE || !state.value.confirmClose) return
        val next=pending;if(next==null)close()else select(next)
    }
    fun cancelClose(stamp:PlanEditorStamp) { if(matches(stamp,true)) { pending=null;state.update { it.copy(confirmClose=false,stamp=it.stamp.copy(revision=it.stamp.revision+1)) } } }
    private fun close() {
        state.update { it.copy(pane=PlanPane.LIST,detail=null,draft=null,isLoading=true,error=null,conflicts=null,pendingMode=null,
            confirmClose=false,confirmDelete=false,stamp=it.stamp.copy(revision=it.stamp.revision+1)) }
        target.value=null;retryRead()
    }
    fun retryRead() { refresh.update { it+1 } }
    private data class Target(val id:Long?,val generation:DataGeneration,val session:Long)
    private data class Context(val generation:DataGeneration,val phase:MaintenanceState,val selected:Target?)
    private data class Snapshot(val plan:Plan?,val enabled:Boolean)
    private class Operation(val stamp:PlanEditorStamp,var receipt:PlanWriteReceipt?)
}
