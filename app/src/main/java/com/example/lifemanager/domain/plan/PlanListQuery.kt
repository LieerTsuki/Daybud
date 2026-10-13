package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class PlanCategory { ALL, TODO, SCHEDULE }
enum class PlanStatus { UNFINISHED, COMPLETED, ALL }
enum class PlanDateFilter { ALL, TODAY, TOMORROW, WEEK }
enum class PlanSort { CREATED, DATE, PRIORITY }
data class PlanFilter(
    val category: PlanCategory = PlanCategory.ALL,
    val status: PlanStatus = PlanStatus.UNFINISHED,
    val date: PlanDateFilter = PlanDateFilter.ALL,
    val query: String = "",
    val priority: TodoPriority? = null,
    val tagId: Long? = null,
    val sort: PlanSort = PlanSort.CREATED,
)
data class PlanListRow(val plan: Plan, val occurrence: PlanOccurrence? = null)

object PlanListQuery {
    fun select(plans: List<Plan>, filter: PlanFilter, now: Instant, displayZone: ZoneId,
        exceptions: Map<Long, List<PlanException>> = emptyMap()): List<PlanListRow> {
        // Invalid hidden records are read failures too, not an apparently valid empty result.
        plans.forEach(::requireReadablePlan)
        val window = if (filter.category == PlanCategory.TODO) null else dateWindow(filter.date, now, displayZone)
        val query = filter.query.trim()
        val rows = plans.asSequence().filter { plan ->
            (filter.category == PlanCategory.ALL || plan.isScheduled == (filter.category == PlanCategory.SCHEDULE)) &&
                (filter.status == PlanStatus.ALL || plan.isCompleted == (filter.status == PlanStatus.COMPLETED)) &&
                (query.isEmpty() || plan.title.contains(query, ignoreCase = true) || plan.description?.contains(query, ignoreCase = true) == true) &&
                (filter.priority == null || plan.priority == filter.priority) &&
                (filter.tagId == null || filter.tagId in plan.tagIds)
        }.mapNotNull { plan ->
            val excluded = exceptions[plan.id].orEmpty()
            if (window != null) {
                val occurrence = PlanOccurrences.inWindow(plan, window, excluded, includeCompleted = true).firstOrNull()
                occurrence?.let { PlanListRow(plan, it) }
            } else PlanListRow(plan, referenceOccurrence(plan, now, excluded))
        }.toList()
        val order = when (filter.sort) {
            PlanSort.CREATED -> compareByDescending<PlanListRow> { it.plan.createdAt }
            PlanSort.PRIORITY -> compareByDescending<PlanListRow> { it.plan.priority.ordinal }
            PlanSort.DATE -> compareBy<PlanListRow> { !it.plan.isScheduled }
                .thenBy { it.occurrence == null }
                .thenBy { row -> row.occurrence?.time?.firstInstant(ZoneId.of(row.plan.timeZone)) }
        }
        return rows.sortedWith(order.thenBy { it.plan.id })
    }

    private fun dateWindow(filter: PlanDateFilter, now: Instant, zone: ZoneId): PlanWindow? {
        if (filter == PlanDateFilter.ALL) return null
        val today = now.atZone(zone).toLocalDate()
        val first = when (filter) {
            PlanDateFilter.TODAY -> today
            PlanDateFilter.TOMORROW -> today.plusDays(1)
            PlanDateFilter.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            PlanDateFilter.ALL -> error("handled above")
        }
        val last = first.plusDays(if (filter == PlanDateFilter.WEEK) 7 else 1)
        return PlanWindow(first.atStartOfDay(zone).toInstant(), last.atStartOfDay(zone).toInstant())
    }

    /** Reuse the shared seek/occurrence rules, never reminder eligibility (which depends on permission).
     * No-date values stay null. An exhausted series has no fabricated next occurrence.
     */
    private fun referenceOccurrence(plan: Plan, now: Instant, exceptions: List<PlanException>): PlanOccurrence? {
        if (!plan.isScheduled) return null
        val zone = ZoneId.of(plan.timeZone)
        if (!plan.isRepeating) return occurrenceAt(plan, anchorDate(plan, zone), zone)
        if (plan.isCompleted) return plan.completedAt?.let {
            PlanOccurrence(plan.id, it.atZone(zone).toLocalDate(), PlanOccurrenceTime.Completion(it))
        }
        val lower = when (plan.timeMode) {
            PlanTimeMode.TIMED -> backDays(now.minus(Duration.between(plan.startAt, plan.endAt)).atZone(zone).toLocalDate(), 2)
            PlanTimeMode.ALL_DAY -> backDays(now.atZone(zone).toLocalDate(), Math.addExact(ChronoUnit.DAYS.between(plan.allDayStartDate, plan.allDayEndDate), 2))
            else -> error("only intervals repeat")
        }
        val cancelled = cancelledDates(plan, exceptions)
        for (date in PlanRecurrence.starts(plan, lower, LocalDate.MAX, PlanExpansionBudget(10_000))) {
            if (date in cancelled) continue
            val occurrence = occurrenceAt(plan, date, zone)
            if (occurrence.time.interval(zone).second > now) return occurrence
        }
        return null
    }
}
