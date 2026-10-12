package com.example.lifemanager.notification

import com.example.lifemanager.domain.plan.PlanReminderEffects
import com.example.lifemanager.domain.plan.PlanReminderToken

interface PlanReminderSchedulerContract : PlanReminderEffects {
    /** Called after matching delivered CAS, under the SAME outer permits. */
    suspend fun advance(token: PlanReminderToken)
}
