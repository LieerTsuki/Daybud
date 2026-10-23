package com.example.lifemanager.ui.navigation

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.plan.PlanEditorStamp
import com.example.lifemanager.ui.plan.PlanEditorState
import com.example.lifemanager.ui.subscription.SubscriptionUiState

/** Live immutable snapshots preserve the editor's generation, session and draft identity. */
interface PlanNavigationEditor {
    val snapshot: PlanEditorState
    fun open(id: Long, generation: DataGeneration)
    fun requestClose(stamp: PlanEditorStamp)
    fun confirmClose(stamp: PlanEditorStamp)
}

interface SubscriptionNavigationEditor {
    val snapshot: SubscriptionUiState
    fun openNotificationDetail(id: Long, generation: DataGeneration)
    fun closeEditor(generation: DataGeneration)
    fun closePaymentEditor(generation: DataGeneration)
}

internal val SubscriptionUiState.blocksNavigation: Boolean
    get() = isBusy || isMaintaining || isExporting || isExportPending || editor.isSaving || paymentEditor.isSaving
