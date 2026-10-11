package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class PlanReminder(val occurrence: PlanOccurrence, val triggerAt: Instant)
object PlanReminderRules {
    /** Scheduling eligibility only. C2 must still re-read Room, generation, permissions and identity at delivery. */
    fun next(
        plan: Plan,
        now: Instant,
        exceptions: List<PlanException> = emptyList(),
        planRemindersEnabled: Boolean = true,
        notificationsAllowed: Boolean = true,
        maxCandidates: Int = 10_000,
    ): PlanReminder? {
        val budget = PlanExpansionBudget(maxCandidates)
        requireReadablePlan(plan)
        if (!planRemindersEnabled || !notificationsAllowed || !plan.reminderEnabled || plan.isCompleted || !plan.isScheduled) return null
        val minutes = plan.reminderMinutes ?: return null
        val zone = ZoneId.of(plan.timeZone)
        val leadSeconds = Math.multiplyExact(minutes.toLong(), 60)
        fun reminder(occurrence: PlanOccurrence): PlanReminder? {
            val anchor = when (val time = occurrence.time) {
                is PlanOccurrenceTime.DeadlineInstant -> time.at
                is PlanOccurrenceTime.DeadlineDate -> time.date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
                is PlanOccurrenceTime.Timed -> time.start
                is PlanOccurrenceTime.AllDay -> time.first.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
                is PlanOccurrenceTime.Completion -> return null
            }
            val trigger = anchor.minusSeconds(leadSeconds)
            return if (trigger > now) PlanReminder(occurrence, trigger) else null
        }
        if (!plan.isRepeating) {
            budget.consume()
            return reminder(occurrenceAt(plan, anchorDate(plan, zone), zone))
        }
        // Search by trigger, not today's calendar: tomorrow's occurrence may trigger tonight.
        val earliest = backDays(now.plusSeconds(leadSeconds).atZone(zone).toLocalDate(), 2)
        val cancelled = cancelledDates(plan, exceptions)
        for (date in PlanRecurrence.starts(plan, earliest, LocalDate.MAX, budget)) {
            if (date in cancelled) continue
            reminder(occurrenceAt(plan, date, zone))?.let { return it }
        }
        return null
    }
}
