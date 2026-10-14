package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderToken
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class V1Destination { PLAN, HABIT, SUBSCRIPTION, SETTINGS }
sealed interface PlanNotificationClick {
    data class Detail(val token:PlanReminderToken):PlanNotificationClick
    data object Legacy:PlanNotificationClick
}
data class PlanNavigationAction(val ticket:Long,val destination:V1Destination,val generation:DataGeneration,
    val planId:Long?=null,val legacy:Boolean=false,val rendered:PlanEditorStamp?=null)
data class PlanNavigationConfirmation(val ticket:Long,val stamp:PlanEditorStamp)
data class PlanNavigationState(val destination:V1Destination=V1Destination.PLAN,val generation:DataGeneration?=null,
    val pending:PlanNavigationAction?=null,val confirmation:PlanNavigationConfirmation?=null,
    val waitingForDraft:Boolean=false,val isMaintaining:Boolean=false,val error:String?=null,val message:String?=null)
class PlanNavigationViewModel(private val access:GenerationAccess,private val dispatcher:CoroutineDispatcher):ViewModel() {
    private val state=MutableStateFlow(PlanNavigationState());val uiState=state.asStateFlow()
    private var sequence=0L
    private val reload=MutableStateFlow(0L)
    fun retryRead() { if(access.maintenance.value==MaintenanceState.IDLE)reload.update { it+1 } }
    init { viewModelScope.launch(dispatcher) { reload.collectLatest { observe() } } }
    private suspend fun observe() {
        try {
            combine(access.generations,access.maintenance) { _,phase->phase }.collectLatest { phase->
                withContext(Dispatchers.Main.immediate) {
                    if(phase!=MaintenanceState.IDLE)state.update { it.copy(pending=null,confirmation=null,waitingForDraft=false,isMaintaining=true) }
                    else state.update { it.copy(isMaintaining=false) }
                }
                if(phase!=MaintenanceState.IDLE)return@collectLatest
                // The stream invalidates only; buffered tokens must not replace a strict live read.
                access.read({ Unit }) { token,_ ->state.update { current ->
                    val rejected=current.pending?.generation?.let { it!=token }==true
                    current.copy(generation=token,destination=if(current.generation!=token)V1Destination.PLAN else current.destination,
                        pending=if(rejected)null else current.pending,confirmation=if(rejected)null else current.confirmation,
                        waitingForDraft=if(rejected)false else current.waitingForDraft,error=null)
                } }
            }
        } catch(error:Exception) {
            if(error is CancellationException)throw error
            withContext(Dispatchers.Main.immediate) { state.update { it.copy(generation=null,pending=null,confirmation=null,waitingForDraft=false,error="读取导航世代失败，请重新读取") } }
        }
    }
    fun requestDestination(target:V1Destination,stamp:PlanEditorStamp) {
        if(target==state.value.destination)return
        val generation=stamp.generation ?: return
        if(generation!=state.value.generation || access.maintenance.value!=MaintenanceState.IDLE)return
        enqueue(PlanNavigationAction(nextTicket(),target,generation,rendered=stamp))
    }
    fun receive(click:PlanNotificationClick) {
        if(access.maintenance.value!=MaintenanceState.IDLE)return
        val ticket=nextTicket()
        viewModelScope.launch(dispatcher) {
            try {
                val token=when(click) { is PlanNotificationClick.Detail->click.token.generation;PlanNotificationClick.Legacy->access.capture() }
                access.run(token) { withContext(Dispatchers.Main.immediate) {
                    if(ticket!=sequence)return@withContext
                    state.update { it.copy(pending=PlanNavigationAction(ticket,V1Destination.PLAN,token,
                        planId=(click as? PlanNotificationClick.Detail)?.token?.planId,legacy=click==PlanNotificationClick.Legacy),
                        confirmation=null,waitingForDraft=false,error=null) }
                } }
            } catch(error:Exception) { if(error is CancellationException)throw error }
        }
    }
    private fun nextTicket():Long {
        val ticket=++sequence
        // Supersession ends old dialog ownership even if the new request later fails admission.
        // Keep the editor's draft intact; do not leave a visible dialog with inert callbacks.
        state.update { it.copy(pending=null,confirmation=null,waitingForDraft=false,error=null) }
        return ticket
    }
    private fun enqueue(action:PlanNavigationAction) {
        viewModelScope.launch(dispatcher) {
            try { access.run(action.generation) { withContext(Dispatchers.Main.immediate) {
                if(sequence==action.ticket)state.update { it.copy(pending=action,confirmation=null,waitingForDraft=false,error=null) }
            } } } catch(error:Exception) { if(error is CancellationException)throw error }
        }
    }
    suspend fun deliver(editor:PlanEditorViewModel):Boolean {
        val action=state.value.pending ?: return false
        return effect(action) {
            val current=editor.uiState.value
            if(!ready(current,action))return@effect false
            if(action.rendered!=null && current.stamp!=action.rendered) { reject(action);return@effect false }
            if(state.value.waitingForDraft && current.pane==PlanPane.EDIT)return@effect false
            if(dirty(current)) {
                state.update { it.copy(confirmation=PlanNavigationConfirmation(action.ticket,current.stamp)) };false
            } else apply(action,editor)
        }
    }
    suspend fun confirmLeave(confirmation:PlanNavigationConfirmation,editor:PlanEditorViewModel):Boolean {
        val action=state.value.pending ?: return false
        if(state.value.confirmation!=confirmation || confirmation.ticket!=action.ticket || confirmation.ticket!=sequence)return false
        return effect(action) {
            val current=editor.uiState.value
            if(!ready(current,action) || current.stamp!=confirmation.stamp)return@effect false
            editor.requestClose(current.stamp)
            if(editor.uiState.value.confirmClose)editor.confirmClose(editor.uiState.value.stamp)
            if(editor.uiState.value.pane!=PlanPane.LIST)return@effect false
            // A detail open needs the fresh post-close snapshot; retain only the original event.
            state.update { it.copy(confirmation=null,waitingForDraft=false,pending=action.copy(rendered=null)) }
            if(action.planId!=null)false else finish(action)
        }
    }
    fun cancelLeave(confirmation:PlanNavigationConfirmation) {
        if(state.value.confirmation!=confirmation || confirmation.ticket!=sequence)return
        val action=state.value.pending ?: return
        val notification=action.rendered==null
        state.update { it.copy(confirmation=null,pending=if(notification)action else null,waitingForDraft=notification) }
    }
    private fun ready(current:PlanEditorState,action:PlanNavigationAction)=current.stamp.generation==action.generation &&
        (action.planId==null || (current.isAvailable && !current.isLoading)) && !current.isBusy && !current.isMaintaining && !current.confirmClose &&
        current.pendingMode==null && current.conflicts==null && !current.confirmDelete
    private fun dirty(current:PlanEditorState)=current.draft?.let { it!=PlanDraft(it.base) }==true
    private fun apply(action:PlanNavigationAction,editor:PlanEditorViewModel):Boolean {
        if(action.planId!=null) {
            val before=editor.uiState.value.stamp
            editor.open(action.planId,action.generation)
            val accepted=editor.uiState.value
            if(accepted.pane!=PlanPane.DETAIL || accepted.stamp.session==before.session)return false
        } else if(action.legacy && editor.uiState.value.pane!=PlanPane.LIST) {
            editor.requestClose(editor.uiState.value.stamp)
            if(editor.uiState.value.pane!=PlanPane.LIST)return false
        }
        return finish(action)
    }
    private fun finish(action:PlanNavigationAction):Boolean {
        state.update { it.copy(destination=action.destination,pending=null,confirmation=null,waitingForDraft=false,
            message=if(action.legacy)"原待办／日程通知已失效，请从统一计划列表查看"else null) };return true
    }
    private fun reject(action:PlanNavigationAction) {
        if(state.value.pending?.ticket==action.ticket)state.update { it.copy(pending=null,confirmation=null,waitingForDraft=false) }
    }
    private suspend fun effect(action:PlanNavigationAction,body:()->Boolean):Boolean=try {
        access.run(action.generation) { withContext(Dispatchers.Main.immediate) {
            if(state.value.pending?.ticket!=action.ticket || sequence!=action.ticket)false else body()
        } }
    } catch(error:Exception) {
        if(error is CancellationException)throw error
        withContext(Dispatchers.Main.immediate) { reject(action) };false
    }
}
