package com.example.lifemanager.domain.plan

data class PlanReminderState(val token: PlanReminderToken, val delivered: Boolean)

/** Local operational inventory, NOT delivery authorization. No business foreign key: cleanup
 * retains a deleted plan's system identity until cancellation succeeds. Caller owns module lock.
 */
interface PlanReminderStore {
    suspend fun get(planId: Long): PlanReminderState?
    suspend fun all(): List<PlanReminderState>
    suspend fun remember(token: PlanReminderToken)
    suspend fun markDelivered(token: PlanReminderToken): Boolean
    suspend fun forget(planId: Long)
}
fun interface PlanReminderPreferences { suspend fun enabled(): Boolean }
