package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.Instant
import java.time.LocalDate

data class PlanReminderToken(val planId: Long, val generation: DataGeneration, val occurrenceDate: LocalDate, val triggerAt: Instant) {
    init {
        require(planId > 0) { "Reminder requires a persisted plan" }
        require(Instant.ofEpochMilli(triggerAt.toEpochMilli()) == triggerAt) { "Reminder trigger must preserve millisecond precision" }
    }
    companion object {
        fun from(reminder: PlanReminder, generation: DataGeneration): PlanReminderToken =
            PlanReminderToken(reminder.occurrence.planId, generation, reminder.occurrence.occurrenceDate, reminder.triggerAt)
    }
}
