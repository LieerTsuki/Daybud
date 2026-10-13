package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*

enum class PlanPane { LIST, DETAIL, EDIT }
data class PlanEditorStamp(val generation: DataGeneration?, val session: Long, val revision: Long)
data class PlanEditorState(
    val stamp: PlanEditorStamp = PlanEditorStamp(null,0,0),
    val pane: PlanPane = PlanPane.LIST,
    val detail: Plan? = null,
    val draft: PlanDraft? = null,
    val isAvailable: Boolean = false,
    val isLoading: Boolean = true,
    val isMaintaining: Boolean = false,
    val isBusy: Boolean = false,
    val remindersEnabled: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val pendingMode: PlanTimeMode? = null,
    val confirmClose: Boolean = false,
    val confirmDelete: Boolean = false,
    val conflicts: PlanConflictProof? = null,
    val reminderFailures: Map<Long,PlanWriteReceipt> = emptyMap(),
) {
    val reminderReceipt:PlanWriteReceipt? get()=reminderFailures.values.lastOrNull()
}
