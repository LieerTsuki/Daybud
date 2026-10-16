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

/** Read-only calendar owner; detail/editor owns all business writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanCalendarViewModel(private val repository:PlanRepository,private val access:GenerationAccess,
    private val dispatcher:CoroutineDispatcher,private val clock:Clock=Clock.system(ZoneId.of("Asia/Shanghai"))):ViewModel() {
    private val state=MutableStateFlow(PlanCalendarState(selectedDate=LocalDate.now(clock),displayZone=clock.zone))
    val uiState=state.asStateFlow()
    private val selection=MutableStateFlow(Selection(LocalDate.now(clock),PlanCalendarMode.MONTH,true,clock.zone))
    private val refreshes=MutableStateFlow(0L)
    init { viewModelScope.launch(dispatcher) { refreshes.collectLatest { observeCalendar() } } }

    private suspend fun observeCalendar() {
        try {
            combine(access.generations,access.maintenance,selection) { token,phase,choice -> Context(token,phase,choice) }
                .distinctUntilChanged { old,new -> old.token==new.token && old.phase==new.phase && old.choice.sameQueryAs(new.choice) }
                .collectLatest { context ->
                withContext(Dispatchers.Main.immediate) {
                    if(state.value.generation!=context.token) state.value=PlanCalendarState(generation=context.token,
                        selectedDate=selection.value.date,mode=context.choice.mode,includeCompleted=context.choice.completed,displayZone=context.choice.zone)
                    state.update { it.copy(isMaintaining=context.phase!=MaintenanceState.IDLE,
                        isLoading=context.phase==MaintenanceState.IDLE,selectedDate=selection.value.date,mode=context.choice.mode,includeCompleted=context.choice.completed) }
                }
                if(context.phase!=MaintenanceState.IDLE) return@collectLatest
                try {
                    repository.observeAll().collect {
                        // An emission is invalidation only: fetch facts under the captured generation permit.
                        access.read({
                            val plans=repository.observeAll().first()
                            val exceptions=plans.filter { it.isRepeating }.associate { it.id to repository.getExceptions(it.id) }
                            PlanCalendarQuery.select(plans,context.choice.date,context.choice.mode,context.choice.zone,exceptions,context.choice.completed)
                        }) { token,days -> state.update { it.copy(generation=token,days=days,displayZone=context.choice.zone,
                            isAvailable=true,isLoading=false,readError=null) } }
                    }
                } catch(error:Exception) {
                    if(error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) {
                        if(state.value.generation==context.token) fail("读取日历失败，请重新读取")
                    }
                }
            }
        } catch(error:Exception) {
            if(error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) { fail("读取数据世代失败，请重新读取") }
        }
    }
    private fun fail(message:String) { state.update { it.copy(isAvailable=false,isLoading=false,days=emptyMap(),readError=message) } }
    fun canNavigate(rendered:DataGeneration?):Boolean {
        val current=state.value
        if(rendered!=current.generation || !current.isAvailable || current.isLoading || current.isMaintaining) return false
        return try { access.eventToken(rendered);true } catch(_:IllegalStateException) { false }
    }
    fun selectMode(mode:PlanCalendarMode,rendered:DataGeneration?) { change(rendered) { it.copy(mode=mode) } }
    fun selectDate(date:LocalDate,rendered:DataGeneration?) { change(rendered) { it.copy(date=date) } }
    fun showCompleted(show:Boolean,rendered:DataGeneration?) { change(rendered) { it.copy(completed=show) } }
    fun movePeriod(direction:Int,rendered:DataGeneration?) {
        if(direction !in listOf(-1,1)) return
        change(rendered) { old -> old.copy(date=when(old.mode) {
            PlanCalendarMode.MONTH -> old.date.plusMonths(direction.toLong())
            PlanCalendarMode.WEEK -> old.date.plusWeeks(direction.toLong())
            PlanCalendarMode.DAY -> old.date.plusDays(direction.toLong())
        }) }
    }
    fun today(rendered:DataGeneration?) { change(rendered) { it.copy(date=clock.instant().atZone(it.zone).toLocalDate()) } }
    private fun change(rendered:DataGeneration?,transform:(Selection)->Selection) {
        if(!canNavigate(rendered)) return
        try {
            val next=transform(selection.value)
            // Probe range and endpoint arithmetic before publishing the selection.
            PlanCalendarQuery.range(next.date,next.mode).second.plusDays(1)
            if(next!=selection.value) {
                val sameQuery=next.sameQueryAs(selection.value)
                state.update { it.copy(selectedDate=next.date,mode=next.mode,includeCompleted=next.completed,
                    isAvailable=it.isAvailable && sameQuery,isLoading=!sameQuery,
                    days=if(sameQuery) it.days else emptyMap(),message=null) }
                selection.value=next
            }
        } catch(_:DateTimeException) { state.update { it.copy(message="日期超出可显示范围") } }
    }
    fun retryRead() { state.update { it.copy(isLoading=true) };refreshes.update { it+1 } }
    fun refresh(displayZone:ZoneId=clock.zone) { selection.update { it.copy(zone=displayZone) };retryRead() }
    private data class Selection(val date:LocalDate,val mode:PlanCalendarMode,val completed:Boolean,val zone:ZoneId) {
        // Highlighting a day within the same verified range never changes the query's facts.
        fun sameQueryAs(other:Selection)=mode==other.mode && completed==other.completed && zone==other.zone &&
            PlanCalendarQuery.range(date,mode)==PlanCalendarQuery.range(other.date,other.mode)
    }
    private data class Context(val token:DataGeneration,val phase:MaintenanceState,val choice:Selection)
}
