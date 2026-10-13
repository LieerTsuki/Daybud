package com.example.lifemanager.ui.plan

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Prepared E workspace; production navigation activation is deliberately separate. */
@Composable
fun PlanWorkspace(list: PlanViewModel, editor: PlanEditorViewModel) {
    val state = editor.uiState.collectAsStateWithLifecycle().value
    val stamp = state.stamp
    BackHandler(enabled = state.pane != PlanPane.LIST) { editor.requestClose(stamp) }
    when (state.pane) {
        PlanPane.LIST -> PlanScreen(list, editor::openNew, editor::open,
            canNavigate = state.isAvailable && !state.isMaintaining && !state.isBusy,
            extraContent = { planFeedback(state, editor) })
        PlanPane.DETAIL -> PlanDetail(state, editor)
        PlanPane.EDIT -> PlanEditor(state, editor)
    }
}
