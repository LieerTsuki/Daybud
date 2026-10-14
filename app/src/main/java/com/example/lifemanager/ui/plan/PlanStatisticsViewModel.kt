package com.example.lifemanager.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Independent unfiltered database observer, not derived from visible list/calendar rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanStatisticsViewModel(private val repository:PlanRepository,private val access:GenerationAccess,
    private val dispatcher:CoroutineDispatcher,private val clock:Clock=Clock.systemDefaultZone()):ViewModel() {
    private val state=MutableStateFlow(PlanStatisticsState(today=LocalDate.now(clock),displayZone=clock.zone))
    val uiState=state.asStateFlow()
    private val zone=MutableStateFlow(clock.zone)
    private val refreshes=MutableStateFlow(0L)
    init { viewModelScope.launch(dispatcher) { refreshes.collectLatest { observeStatistics() } } }
    private suspend fun observeStatistics() {
        try {
            combine(access.generations,access.maintenance,zone) { token,phase,displayZone -> Context(token,phase,displayZone) }.collectLatest { context ->
                withContext(Dispatchers.Main.immediate) {
                    if(state.value.generation!=context.token) state.value=PlanStatisticsState(generation=context.token,today=LocalDate.now(clock),displayZone=context.zone)
                    state.update { it.copy(isMaintaining=context.phase!=MaintenanceState.IDLE,isLoading=context.phase==MaintenanceState.IDLE) }
                }
                if(context.phase!=MaintenanceState.IDLE) return@collectLatest
                try {
                    repository.observeAll().collect {
                        access.read({
                            val plans=repository.observeAll().first()
                            val now=clock.instant()
                            val statistics=PlanStatisticsRules.calculate(plans,now,context.zone)
                            val exceptions=plans.filter { it.isRepeating }.associate { it.id to repository.getExceptions(it.id) }
                            Snapshot(statistics,PlanCalendarQuery.todayScheduledCount(plans,now,context.zone,exceptions),now.atZone(context.zone).toLocalDate())
                        }) { token,fresh -> state.update { it.copy(generation=token,statistics=fresh.statistics,todayScheduledCount=fresh.todayCount,
                            today=fresh.today,displayZone=context.zone,isAvailable=true,isLoading=false,readError=null) } }
                    }
                } catch(error:Exception) {
                    if(error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) { if(state.value.generation==context.token) fail("读取统计失败，请重新读取") }
                }
            }
        } catch(error:Exception) {
            if(error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) { fail("读取数据世代失败，请重新读取") }
        }
    }
    private fun fail(message:String) { state.update { it.copy(statistics=null,todayScheduledCount=null,isAvailable=false,isLoading=false,readError=message) } }
    fun canNavigate(rendered:DataGeneration?):Boolean {
        val current=state.value
        if(rendered!=current.generation || !current.isAvailable || current.isLoading || current.isMaintaining) return false
        return try { access.eventToken(rendered);true } catch(_:IllegalStateException) { false }
    }
    fun retryRead() { state.update { it.copy(isLoading=true) };refreshes.update { it+1 } }
    fun refresh(displayZone:ZoneId=clock.zone) { zone.value=displayZone;retryRead() }
    private data class Context(val token:DataGeneration,val phase:MaintenanceState,val zone:ZoneId)
    private data class Snapshot(val statistics:PlanStatistics,val todayCount:Int,val today:LocalDate)
}
