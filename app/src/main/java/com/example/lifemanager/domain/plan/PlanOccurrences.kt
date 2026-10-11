package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Comparison-only half-open window; LocalDate values remain dates in the occurrence. */
data class PlanWindow(val startInclusive: Instant, val endExclusive: Instant) {
    init { require(startInclusive < endExclusive) { "Plan window must be nonempty" } }
}
sealed interface PlanOccurrenceTime {
    data class DeadlineInstant(val at: Instant) : PlanOccurrenceTime
    data class DeadlineDate(val date: LocalDate) : PlanOccurrenceTime
    data class Timed(val start: Instant, val end: Instant) : PlanOccurrenceTime
    data class AllDay(val first: LocalDate, val lastInclusive: LocalDate) : PlanOccurrenceTime
    data class Completion(val at: Instant) : PlanOccurrenceTime
}
data class PlanOccurrence(val planId: Long, val occurrenceDate: LocalDate, val time: PlanOccurrenceTime)
class PlanExpansionLimitException : IllegalStateException("Plan expansion limit exceeded")

object PlanOccurrences {
    fun inWindow(
        plan: Plan,
        window: PlanWindow,
        exceptions: List<PlanException> = emptyList(),
        includeCompleted: Boolean = false,
        maxCandidates: Int = 10_000,
    ): List<PlanOccurrence> = inWindow(plan, window, exceptions, includeCompleted, PlanExpansionBudget(maxCandidates))

    internal fun inWindow(plan: Plan, window: PlanWindow, exceptions: List<PlanException>, includeCompleted: Boolean, budget: PlanExpansionBudget): List<PlanOccurrence> {
        requireReadablePlan(plan)
        if (!plan.isScheduled || (plan.isCompleted && !includeCompleted)) return emptyList()
        val zone = ZoneId.of(plan.timeZone)
        if (plan.isCompleted && plan.isRepeating) {
            val completed = plan.completedAt ?: return emptyList()
            budget.consume()
            return if (completed >= window.startInclusive && completed < window.endExclusive)
                listOf(PlanOccurrence(plan.id, completed.atZone(zone).toLocalDate(), PlanOccurrenceTime.Completion(completed))) else emptyList()
        }
        if (!plan.isRepeating) {
            budget.consume()
            val single = occurrenceAt(plan, anchorDate(plan, zone), zone)
            return if (single.time.intersects(window, zone)) listOf(single) else emptyList()
        }
        val lower = when (plan.timeMode) {
            PlanTimeMode.TIMED -> backDays(window.startInclusive.minus(Duration.between(plan.startAt, plan.endAt)).atZone(zone).toLocalDate(), 2)
            PlanTimeMode.ALL_DAY -> backDays(window.startInclusive.atZone(zone).toLocalDate(), Math.addExact(ChronoUnit.DAYS.between(plan.allDayStartDate, plan.allDayEndDate), 2))
            else -> error("Only interval plans repeat")
        }
        val upper = window.endExclusive.minusNanos(1).atZone(zone).toLocalDate()
        val cancelled = cancelledDates(plan, exceptions)
        return PlanRecurrence.starts(plan, lower, upper, budget)
            .filter { it !in cancelled }
            .map { occurrenceAt(plan, it, zone) }
            .filter { it.time.intersects(window, zone) }
            .toList()
            .sortedWith(compareBy<PlanOccurrence> { it.time.firstInstant(zone) }.thenBy { it.occurrenceDate })
    }
}

internal fun requireReadablePlan(plan: Plan) {
    val error = PlanRules.validateForRead(plan)
    require(error == null) { "Invalid plan ${plan.id}: $error" }
}

internal fun cancelledDates(plan: Plan, exceptions: List<PlanException>): Set<LocalDate> =
    exceptions.asSequence().filter { it.planId == plan.id && it.isCancelled }.map { it.occurrenceDate }.toSet()

internal fun backDays(date: LocalDate, days: Long): LocalDate =
    LocalDate.ofEpochDay(maxOf(LocalDate.MIN.toEpochDay(), Math.subtractExact(date.toEpochDay(), days)))

internal fun anchorDate(plan: Plan, zone: ZoneId): LocalDate = when (plan.timeMode) {
    PlanTimeMode.DEADLINE -> plan.dueDate ?: plan.dueAt!!.atZone(zone).toLocalDate()
    PlanTimeMode.TIMED -> plan.startAt!!.atZone(zone).toLocalDate()
    PlanTimeMode.ALL_DAY -> plan.allDayStartDate!!
    PlanTimeMode.NONE -> error("Undated plan has no occurrence anchor")
}

internal fun occurrenceAt(plan: Plan, date: LocalDate, zone: ZoneId): PlanOccurrence {
    val time = when (plan.timeMode) {
        PlanTimeMode.DEADLINE -> plan.dueAt?.let { PlanOccurrenceTime.DeadlineInstant(it) }
            ?: PlanOccurrenceTime.DeadlineDate(plan.dueDate!!)
        PlanTimeMode.TIMED -> {
            // Never re-resolve the original ambiguous local time with a different offset.
            val original = plan.startAt!!
            val start = if (date == original.atZone(zone).toLocalDate()) original
                else date.atTime(original.atZone(zone).toLocalTime()).atZone(zone).toInstant()
            PlanOccurrenceTime.Timed(start, start.plus(Duration.between(original, plan.endAt!!)))
        }
        PlanTimeMode.ALL_DAY -> PlanOccurrenceTime.AllDay(date,
            date.plusDays(ChronoUnit.DAYS.between(plan.allDayStartDate, plan.allDayEndDate)))
        PlanTimeMode.NONE -> error("Undated plan has no occurrence")
    }
    return PlanOccurrence(plan.id, date, time)
}

internal fun PlanOccurrenceTime.firstInstant(zone: ZoneId): Instant = when (this) {
    is PlanOccurrenceTime.DeadlineInstant -> at
    is PlanOccurrenceTime.DeadlineDate -> date.atStartOfDay(zone).toInstant()
    is PlanOccurrenceTime.Timed -> start
    is PlanOccurrenceTime.AllDay -> first.atStartOfDay(zone).toInstant()
    is PlanOccurrenceTime.Completion -> at
}

internal fun PlanOccurrenceTime.interval(zone: ZoneId): Pair<Instant, Instant> = when (this) {
    is PlanOccurrenceTime.Timed -> start to end
    is PlanOccurrenceTime.AllDay -> first.atStartOfDay(zone).toInstant() to lastInclusive.plusDays(1).atStartOfDay(zone).toInstant()
    is PlanOccurrenceTime.DeadlineDate -> date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()
    else -> error("Point occurrences are not intervals")
}

private fun PlanOccurrenceTime.intersects(window: PlanWindow, zone: ZoneId): Boolean = when (this) {
    is PlanOccurrenceTime.DeadlineInstant, is PlanOccurrenceTime.Completion -> firstInstant(zone).let { it >= window.startInclusive && it < window.endExclusive }
    else -> interval(zone).let { (start, end) -> start < end && start < window.endExclusive && window.startInclusive < end }
}
