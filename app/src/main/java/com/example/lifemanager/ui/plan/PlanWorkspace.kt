package com.example.lifemanager.ui.plan

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.LocalDate

/** One board with an optional inline month; detail/editor retain the same guarded owners. */
@Composable
fun PlanWorkspace(list: PlanViewModel, editor: PlanEditorViewModel, calendar: PlanCalendarViewModel? = null) {
    val state = editor.uiState.collectAsStateWithLifecycle().value
    val month = if (calendar != null) calendar.uiState.collectAsStateWithLifecycle().value else null
    val stamp = state.stamp
    val rendered = stamp.generation
    var expanded by rememberSaveable { mutableStateOf(false) }
    var detailDay by rememberSaveable { mutableStateOf<String?>(null) }
    var detailGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    val navigationEnabled = state.isAvailable && !state.isLoading && rendered != null && !state.isMaintaining && !state.isBusy
    fun liveBoardAllowed(): Boolean {
        val current = editor.uiState.value
        return current.pane == PlanPane.LIST && current.isAvailable && !current.isLoading && !current.isMaintaining && !current.isBusy &&
            rendered != null && current.stamp.generation == rendered
    }
    fun openDay(date: LocalDate) {
        if (!liveBoardAllowed() || calendar?.canNavigate(rendered) != true) return
        detailDay = date.toString()
        detailGeneration = rendered?.value
        calendar.selectDate(date, rendered)
    }
    LaunchedEffect(month?.generation, state.pane) {
        if (state.pane != PlanPane.LIST || detailGeneration != month?.generation?.value) detailDay = null
    }
    if (expanded && state.pane == PlanPane.LIST && calendar != null) RefreshPlanOverview(calendar, calendar::refresh)
    val extra: LazyListScope.() -> Unit = {
        planFeedback(state, editor)
        if (calendar != null && month != null) {
            item(key = "month-toggle") {
                OutlinedButton(onClick = { if (liveBoardAllowed()) { expanded = !expanded; detailDay = null } },
                    enabled = navigationEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = if (expanded) "收起月历" else "展开月历"
                    }) {
                    Text(if (expanded) "收起月历" else "展开月历", Modifier.weight(1f))
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            if (expanded) planMonthCalendar(month,
                { date -> if (liveBoardAllowed()) calendar.selectDate(date, rendered) }, ::openDay,
                { direction -> if (liveBoardAllowed()) calendar.movePeriod(direction, rendered) },
                { if (liveBoardAllowed()) calendar.today(rendered) }, calendar::retryRead)
        }
    }
    BackHandler(enabled = state.pane != PlanPane.LIST) { editor.requestClose(stamp) }
    when (state.pane) {
        PlanPane.LIST -> PlanScreen(list, onOpen = editor::open, canNavigate = navigationEnabled,
            extraContent = extra, onNewInQuadrant = editor::openNewInQuadrant)
        PlanPane.DETAIL -> PlanDetail(state, editor)
        PlanPane.EDIT -> PlanEditor(state, editor)
    }
    val day = detailDay?.let(LocalDate::parse)
    val dayGeneration = detailGeneration?.let(::DataGeneration)
    if (day != null && dayGeneration != null && month != null && state.pane == PlanPane.LIST &&
        month.generation == dayGeneration && rendered == dayGeneration) {
        PlanDayDetails(day, month, onDismiss = { detailDay = null }, onRetry = calendar!!::retryRead) { id ->
            if (liveBoardAllowed() && calendar.canNavigate(dayGeneration) &&
                calendar.uiState.value.days[day].orEmpty().any { it.plan.id == id }) {
                detailDay = null
                editor.open(id, dayGeneration)
            }
        }
    }
}
