package com.example.lifemanager.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shared list/detail/editor/calendar/statistics workspace over retained module owners. */
@Composable
fun PlanWorkspace(list: PlanViewModel, editor: PlanEditorViewModel, calendar:PlanCalendarViewModel?=null,statistics:PlanStatisticsViewModel?=null) {
    val state = editor.uiState.collectAsStateWithLifecycle().value
    val stamp = state.stamp
    var section by rememberSaveable { mutableStateOf(PlanSection.LIST) }
    val navigationEnabled=stamp.generation!=null && !state.isMaintaining && !state.isBusy
    // Section switches are local navigation only. Never expose them over a dirty editor.
    val extra:LazyListScope.()->Unit={
        if(calendar!=null || statistics!=null) item(key="plan-sections") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PlanSection.entries.filter { it==PlanSection.LIST || (it==PlanSection.CALENDAR && calendar!=null) || (it==PlanSection.STATISTICS && statistics!=null) }.forEach { target ->
                    FilterChip(section==target,{
                        val current=editor.uiState.value
                        if(current.pane==PlanPane.LIST && current.stamp.generation==stamp.generation && !current.isMaintaining && !current.isBusy)
                            section=target
                    },enabled=navigationEnabled,label={Text(when(target) { PlanSection.LIST -> "四象限";PlanSection.CALENDAR -> "日历";PlanSection.STATISTICS -> "统计" })})
                }
            }
        }
        planFeedback(state,editor)
    }
    BackHandler(enabled = state.pane != PlanPane.LIST) { editor.requestClose(stamp) }
    when (state.pane) {
        PlanPane.LIST -> when {
            section==PlanSection.CALENDAR && calendar!=null -> PlanCalendarScreen(calendar,editor::open,navigationEnabled,extra)
            section==PlanSection.STATISTICS && statistics!=null -> PlanStatisticsScreen(statistics,extra)
            else -> PlanScreen(list,onOpen=editor::open,
                canNavigate=state.isAvailable && navigationEnabled,extraContent=extra,onNewInQuadrant=editor::openNewInQuadrant)
        }
        PlanPane.DETAIL -> PlanDetail(state, editor)
        PlanPane.EDIT -> PlanEditor(state, editor)
    }
}

private enum class PlanSection { LIST, CALENDAR, STATISTICS }
