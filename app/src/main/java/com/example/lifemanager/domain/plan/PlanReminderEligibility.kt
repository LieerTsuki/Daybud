package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.ZoneId

object PlanReminderEligibility {
    fun matches(plan: Plan, exceptions: List<PlanException>, token: PlanReminderToken, now: Instant): Boolean {
        requireReadablePlan(plan)
        if (plan.id != token.planId || now < token.triggerAt) return false
        // C1 is strictly future-facing: query just before THIS trigger, not the arrival time.
        val reminder = PlanReminderRules.next(plan, token.triggerAt.minusNanos(1), exceptions) ?: return false
        if (PlanReminderToken.from(reminder, token.generation) != token) return false
        val zone = ZoneId.of(plan.timeZone)
        return when (val time = reminder.occurrence.time) {
            is PlanOccurrenceTime.DeadlineInstant -> now < time.at
            is PlanOccurrenceTime.DeadlineDate -> now.atZone(zone).toLocalDate() <= time.date
            is PlanOccurrenceTime.Timed -> now < time.end
            is PlanOccurrenceTime.AllDay -> now.atZone(zone).toLocalDate() <= time.lastInclusive
            is PlanOccurrenceTime.Completion -> false
        }
    }
}
