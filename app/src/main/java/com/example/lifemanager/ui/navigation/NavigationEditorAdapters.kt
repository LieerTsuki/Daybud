package com.example.lifemanager.ui.navigation

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.plan.PlanEditorStamp
import com.example.lifemanager.ui.plan.PlanEditorViewModel
import com.example.lifemanager.ui.subscription.SubscriptionViewModel

/** Adapters retain the Activity-owned editors and never cache or relabel a rendered snapshot. */
class PlanNavigationEditorAdapter(private val editor: PlanEditorViewModel) : PlanNavigationEditor {
    override val snapshot get() = editor.uiState.value
    override fun open(id: Long, generation: DataGeneration) = editor.open(id, generation)
    override fun requestClose(stamp: PlanEditorStamp) = editor.requestClose(stamp)
    override fun confirmClose(stamp: PlanEditorStamp) = editor.confirmClose(stamp)
}

class SubscriptionNavigationEditorAdapter(private val subscriptions: SubscriptionViewModel) : SubscriptionNavigationEditor {
    override val snapshot get() = subscriptions.uiState.value
    override fun openNotificationDetail(id: Long, generation: DataGeneration) = subscriptions.openNotificationDetail(id, generation)
    override fun closeEditor(generation: DataGeneration) = subscriptions.closeEditor(generation)
    override fun closePaymentEditor(generation: DataGeneration) = subscriptions.closePaymentEditor(generation)
}
