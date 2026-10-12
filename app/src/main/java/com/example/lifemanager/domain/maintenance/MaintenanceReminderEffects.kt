package com.example.lifemanager.domain.maintenance

import com.example.lifemanager.domain.plan.PlanReminderToken
data class ReminderIdentities(
    val todoIds: List<Long>,
    val scheduleIds: List<Long>,
    val subscriptionIds: List<Long>,
    /** D captures BOTH inventories before committing; B's alarm row does not describe A's card. */
    val planAlarmTokens: List<PlanReminderToken> = emptyList(),
    val planNotificationTokens: List<PlanReminderToken> = emptyList(),
)

/** Platform work is deliberately outside the Room commit. */
interface MaintenanceReminderEffects {
    suspend fun clearPrevious(identities: ReminderIdentities)
    suspend fun reconcile()
    fun requestReconciliation()
}
