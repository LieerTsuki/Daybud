package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.ZoneId

object PlanConflicts {
    /** Result covers this window, not an assertion about infinitely many future occurrences. */
    fun find(candidate: Plan, existing: List<Plan>, window: PlanWindow, exceptions: List<PlanException> = emptyList(), maxCandidates: Int = 10_000): List<Plan> {
        val budget = PlanExpansionBudget(maxCandidates)
        requireReadablePlan(candidate)
        existing.forEach(::requireReadablePlan)
        if (!candidate.blocksTime()) return emptyList()
        val first = intervals(candidate, window, exceptions, budget)
        return existing.filter { it.blocksTime() && !(candidate.id != 0L && it.id == candidate.id) }
            .filter { overlaps(first, intervals(it, window, exceptions, budget)) }
    }

    private fun Plan.blocksTime(): Boolean = !isCompleted && (timeMode == PlanTimeMode.TIMED || timeMode == PlanTimeMode.ALL_DAY)
    private fun intervals(plan: Plan, window: PlanWindow, exceptions: List<PlanException>, budget: PlanExpansionBudget): List<Pair<Instant, Instant>> =
        PlanOccurrences.inWindow(plan, window, exceptions, false, budget).map { it.time.interval(ZoneId.of(plan.timeZone)) }

    private fun overlaps(first: List<Pair<Instant, Instant>>, second: List<Pair<Instant, Instant>>): Boolean {
        var i = 0
        var j = 0
        while (i < first.size && j < second.size) {
            val a = first[i]
            val b = second[j]
            when {
                a.second <= b.first -> i++
                b.second <= a.first -> j++
                else -> return true
            }
        }
        return false
    }
}
