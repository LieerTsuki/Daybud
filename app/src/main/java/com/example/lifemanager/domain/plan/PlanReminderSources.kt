package com.example.lifemanager.domain.plan

data class PlanReminderConfiguration(val planReminders: Boolean, val subscriptionReminders: Boolean)
data class LegacyPlanReminderIds(val todoIds: Set<Long> = emptySet(), val scheduleIds: Set<Long> = emptySet())
/** Read-only target facts. Signed legacy IDs are not new plan IDs or delivery authorization. */
interface PlanReminderSources {
    suspend fun configuration(): PlanReminderConfiguration
    suspend fun legacyIds(): LegacyPlanReminderIds
}
