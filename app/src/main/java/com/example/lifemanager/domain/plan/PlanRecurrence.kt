package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Work, not returned rows: cancelled and out-of-window candidates consume the same budget. */
internal class PlanExpansionBudget(private val limit: Int) {
    init { require(limit in 1..10_000) { "Expansion budget must be between 1 and 10000" } }
    private var used = 0
    fun consume() {
        if (used >= limit) throw PlanExpansionLimitException()
        used++
    }
}

/** Directly seek near the requested window; never enumerate from an ancient series anchor. */
internal object PlanRecurrence {
    fun starts(plan: Plan, lower: LocalDate, upper: LocalDate, budget: PlanExpansionBudget): Sequence<LocalDate> = sequence {
        val base = anchorDate(plan, ZoneId.of(plan.timeZone))
        val first = maxOf(base, lower)
        val last = if (plan.isRepeating) minOf(upper, plan.repeatEndDate ?: upper) else upper
        if (first > last) return@sequence
        if (!plan.isRepeating) {
            if (base in first..last) { budget.consume(); yield(base) }
            return@sequence
        }
        val weekly = plan.repeatRule == ScheduleRepeatRule.WEEKLY ||
            (plan.repeatRule == ScheduleRepeatRule.CUSTOM && plan.repeatDaysOfWeek.isNotEmpty())
        val unit = when {
            weekly -> ChronoUnit.WEEKS
            plan.repeatRule == ScheduleRepeatRule.MONTHLY -> ChronoUnit.MONTHS
            plan.repeatRule == ScheduleRepeatRule.YEARLY -> ChronoUnit.YEARS
            else -> ChronoUnit.DAYS
        }
        fun periods(date: LocalDate): Long = when (unit) {
            ChronoUnit.MONTHS -> ChronoUnit.MONTHS.between(YearMonth.from(base), YearMonth.from(date))
            ChronoUnit.YEARS -> date.year.toLong() - base.year.toLong()
            ChronoUnit.WEEKS -> ChronoUnit.DAYS.between(base, date) / 7
            else -> ChronoUnit.DAYS.between(base, date)
        }
        val step = plan.repeatInterval.toLong()
        var index = (periods(first) / step) * step
        val lastIndex = periods(last)
        val weekdays = plan.repeatDaysOfWeek.ifEmpty { setOf(base.dayOfWeek) }
        while (index <= lastIndex) {
            val candidate = when (unit) {
                ChronoUnit.MONTHS -> YearMonth.from(base).plusMonths(index).let { it.atDay(minOf(base.dayOfMonth, it.lengthOfMonth())) }
                ChronoUnit.YEARS -> base.plusYears(index)
                ChronoUnit.WEEKS -> base.plusWeeks(index)
                else -> base.plusDays(index)
            }
            if (weekly) {
                // Legacy weeks are seven-day blocks starting at base, not calendar ISO weeks.
                for (offset in 0L..minOf(6L, ChronoUnit.DAYS.between(candidate, last))) {
                    val date = candidate.plusDays(offset)
                    if (date >= first && date.dayOfWeek in weekdays) { budget.consume(); yield(date) }
                }
            } else if (candidate >= first && candidate <= last) { budget.consume(); yield(candidate) }
            // Do not step past representable dates merely to discover that the window ended.
            if (lastIndex - index < step) break
            index += step
        }
    }
}
