package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.PlanReminderToken

interface UnifiedReminderReconciliation {
    suspend fun reconcile(resetSystem: Boolean = false)
}
interface PlanReminderCalibration : PlanReminderSchedulerContract {
    suspend fun calibrate(planId: Long, expected: DataGeneration, resetSystem: Boolean)
    suspend fun cancelCaptured(token: PlanReminderToken)
}
interface PlanNotificationInventory {
    fun tokens(): List<PlanReminderToken>
    fun cancelCaptured(token: PlanReminderToken)
    fun clearStale(current: DataGeneration, validIds: Set<Long>)
}
fun interface SubscriptionCalibration { suspend fun reconcile(expected: DataGeneration, resetSystem: Boolean) }
fun interface SubscriptionReminderStateReader { suspend fun read(): Pair<DataGeneration, Boolean> }
