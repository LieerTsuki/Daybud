package com.example.lifemanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.V1SettingsRepository
import com.example.lifemanager.domain.repository.SettingsWriteCancellation
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class V1SettingsState(val settings:V1AppSettings?=null,val generation:DataGeneration?=null,
    val isAvailable:Boolean=false,val isLoading:Boolean=true,val isSaving:Boolean=false,
    val isMaintaining:Boolean=false,val errorMessage:String?=null,val remindersPending:Boolean=false,val committed:Boolean=false)
class V1SettingsViewModel(private val repository:V1SettingsRepository,private val access:GenerationAccess,
    private val effects:MaintenanceReminderEffects,private val dispatcher:CoroutineDispatcher):ViewModel() {
    private val state=MutableStateFlow(V1SettingsState());val uiState=state.asStateFlow()
    private val reload=MutableStateFlow(0L)
    private var active:Any?=null
    init { viewModelScope.launch(dispatcher) { reload.collectLatest { observe() } } }
    private suspend fun observe() {
        try {
            combine(access.generations,access.maintenance) { token,phase->token to phase }.collectLatest { (token,phase)->
                withContext(Dispatchers.Main.immediate) {
                    if(state.value.generation!=token) { active=null;state.value=V1SettingsState(generation=token) }
                    state.update { it.copy(isMaintaining=phase!=MaintenanceState.IDLE) }
                }
                if(phase!=MaintenanceState.IDLE)return@collectLatest
                try {
                    repository.observe().collect {
                        access.read({ repository.observe().first() }) { fresh,saved ->
                            if(state.value.generation!=fresh) { active=null;state.value=V1SettingsState(generation=fresh) }
                            state.update { it.copy(settings=saved,isAvailable=true,isLoading=false) }
                        }
                    }
                } catch(error:Exception) {
                    if(error is CancellationException)throw error
                    withContext(Dispatchers.Main.immediate) { if(state.value.generation==token)readFailed() }
                }
            }
        } catch(error:Exception) {
            if(error is CancellationException)throw error
            withContext(Dispatchers.Main.immediate) { readFailed() }
        }
    }
    private fun readFailed() { state.update { it.copy(isAvailable=false,isLoading=false,errorMessage=if(it.committed)"设置已保存，但重新读取失败，请重新读取"else"读取设置失败，请重新读取") } }
    fun retry() {
        if(access.maintenance.value!=MaintenanceState.IDLE || state.value.isSaving)return
        state.update { it.copy(isLoading=true,isAvailable=false,errorMessage=null) };reload.update { it+1 }
    }
    fun setTheme(value:ThemeMode,generation:DataGeneration?)=save(generation) { it.copy(theme=value) }
    fun setDateFormat(value:DateFormat,generation:DataGeneration?)=save(generation) { it.copy(dateFormat=value) }
    fun setCurrency(value:String,generation:DataGeneration?)=save(generation) { it.copy(defaultCurrency=value.trim().uppercase(Locale.ROOT)) }
    fun setReminderDay(day:Int,selected:Boolean,generation:DataGeneration?)=save(generation) {
        require(day in setOf(1,3,7)) { "订阅提醒仅支持提前 1、3、7 天" }
        it.copy(defaultReminderDays=if(selected)it.defaultReminderDays+day else it.defaultReminderDays-day)
    }
    fun setReminderEnabled(module:V1ReminderModule,value:Boolean,generation:DataGeneration?)=save(generation,true) {
        when(module) { V1ReminderModule.PLAN->it.copy(planReminders=value);V1ReminderModule.SUBSCRIPTION->it.copy(subscriptionReminders=value) }
    }
    private fun admitted(generation:DataGeneration?):DataGeneration? {
        val current=state.value
        if(!current.isAvailable || current.isLoading || current.isSaving || current.generation!=generation)return null
        return try { access.eventToken(generation) } catch(_:IllegalStateException) { null }
    }
    private fun save(generation:DataGeneration?,reminders:Boolean=false,transform:(V1AppSettings)->V1AppSettings) {
        val token=admitted(generation) ?: return;val operation=Any();active=operation
        state.update { it.copy(isSaving=true,errorMessage=null) }
        viewModelScope.launch(dispatcher) {
            var committed=false
            try {
                access.run(token) {
                    val writer=currentCoroutineContext().job
                    withContext(NonCancellable+SettingsWriteCancellation(writer)) {
                        val saved=repository.update(token,transform);committed=true
                        withContext(Dispatchers.Main.immediate) {
                            if(owns(operation,token))state.update { it.copy(settings=saved,committed=true,remindersPending=it.remindersPending || reminders) }
                        }
                    }
                }
                if(reminders)reconcile(operation,token)
            } catch(error:Exception) {
                if(error is CancellationException)throw error
                publish(operation,token) { state.update { it.copy(errorMessage=if(committed)"设置已保存，但后续核对失败，请重新读取"else error.message ?: "保存失败，请重新读取后再选择") } }
            } finally {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    if(committed && reminders)requestBackground(operation,token)
                    if(owns(operation,token))state.update { it.copy(isSaving=false,errorMessage=if(committed && reminders && it.remindersPending && it.errorMessage==null)"设置已保存，但提醒尚未确认，可仅重试提醒"else it.errorMessage) }
                    if(active===operation)active=null
                }
            }
        }
    }
    fun retryReminders(generation:DataGeneration?) {
        val token=admitted(generation) ?: return;if(!state.value.remindersPending)return
        val operation=Any();active=operation;state.update { it.copy(isSaving=true,errorMessage=null) }
        viewModelScope.launch(dispatcher) {
            var admitted=false
            try { access.run(token) { admitted=true };reconcile(operation,token) }
            catch(error:Exception) {
                if(error is CancellationException)throw error
                publish(operation,token) { state.update { it.copy(errorMessage="偏好已保存，提醒重试未获准，请重新读取设置") } }
            }
            finally { withContext(NonCancellable+Dispatchers.Main.immediate) {
                if(admitted)requestBackground(operation,token)
                if(owns(operation,token))state.update { it.copy(isSaving=false) }
                if(active===operation)active=null
            } }
        }
    }
    private suspend fun reconcile(operation:Any,token:DataGeneration) {
        // Unified reconciliation acquires its own admission; never hold a settings permit here.
        try {
            withTimeout(8_000) { effects.reconcile() }
            publish(operation,token) { state.update { it.copy(remindersPending=false) } }
        } catch(error:Exception) {
            currentCoroutineContext().ensureActive()
            publish(operation,token) { state.update { it.copy(remindersPending=true,errorMessage="设置已保存，但提醒校准失败，可仅重试提醒") } }
        }
    }
    private fun requestBackground(operation:Any,token:DataGeneration) {
        try { effects.requestReconciliation() } catch(_:Exception) {
            if(owns(operation,token))state.update { it.copy(remindersPending=true,errorMessage="设置已保存，但后台重试未能安排，请重新打开应用检查提醒") }
        }
    }
    private fun owns(operation:Any,token:DataGeneration)=active===operation && state.value.generation==token
    private suspend fun publish(operation:Any,token:DataGeneration,body:()->Unit) {
        try { access.publishResult(token) { if(owns(operation,token))body() } }
        catch(error:Exception) { if(error is CancellationException)throw error;withContext(Dispatchers.Main.immediate) { if(owns(operation,token))readFailed() } }
    }
}
