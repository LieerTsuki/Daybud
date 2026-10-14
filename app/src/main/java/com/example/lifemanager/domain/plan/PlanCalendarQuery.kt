package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class PlanCalendarMode { MONTH, WEEK, DAY }

object PlanCalendarQuery {
    fun select(plans:List<Plan>,selected:LocalDate,mode:PlanCalendarMode,displayZone:ZoneId,
        exceptions:Map<Long,List<PlanException>> = emptyMap(),includeCompleted:Boolean=false,maxCandidates:Int=10_000):Map<LocalDate,List<PlanListRow>> {
        val (first,last)=range(selected,mode)
        val days=linkedMapOf<LocalDate,MutableList<PlanListRow>>()
        for (offset in 0..ChronoUnit.DAYS.between(first,last)) days[first.plusDays(offset)]=mutableListOf()
        val budget=PlanExpansionBudget(maxCandidates)
        for (plan in plans) {
            requireReadablePlan(plan)
            // Saved dates are civil dates, not midnight Instants in the display zone.
            val dateOnly=plan.timeMode==PlanTimeMode.ALL_DAY ||
                (plan.timeMode==PlanTimeMode.DEADLINE && plan.dueAt==null)
            val comparisonZone=if(dateOnly && !(plan.isCompleted && plan.isRepeating)) ZoneId.of(plan.timeZone) else displayZone
            val window=PlanWindow(first.atStartOfDay(comparisonZone).toInstant(),last.plusDays(1).atStartOfDay(comparisonZone).toInstant())
            for (occurrence in PlanOccurrences.inWindow(plan,window,exceptions[plan.id].orEmpty(),includeCompleted,budget)) {
                val (start,end)=when(val time=occurrence.time) {
                    is PlanOccurrenceTime.DeadlineDate -> time.date to time.date
                    is PlanOccurrenceTime.AllDay -> time.first to time.lastInclusive
                    is PlanOccurrenceTime.Timed -> time.start.atZone(displayZone).toLocalDate() to time.end.minusNanos(1).atZone(displayZone).toLocalDate()
                    is PlanOccurrenceTime.DeadlineInstant -> time.at.atZone(displayZone).toLocalDate().let { it to it }
                    is PlanOccurrenceTime.Completion -> time.at.atZone(displayZone).toLocalDate().let { it to it }
                }
                val lower=maxOf(first,start);val upper=minOf(last,end)
                if(lower<=upper) for(offset in 0..ChronoUnit.DAYS.between(lower,upper))
                    days.getValue(lower.plusDays(offset)).add(PlanListRow(plan,occurrence))
            }
        }
        return days.mapValues { (_,rows) -> rows.sortedWith(compareBy<PlanListRow> {
            it.occurrence!!.time.firstInstant(ZoneId.of(it.plan.timeZone))
        }.thenBy { it.plan.id }.thenBy { it.occurrence!!.occurrenceDate }) }
    }

    fun range(selected:LocalDate,mode:PlanCalendarMode):Pair<LocalDate,LocalDate> = when(mode) {
        PlanCalendarMode.MONTH -> YearMonth.from(selected).let { it.atDay(1) to it.atEndOfMonth() }
        PlanCalendarMode.WEEK -> selected.minusDays((selected.dayOfWeek.value-1).toLong()).let { it to it.plusDays(6) }
        PlanCalendarMode.DAY -> selected to selected
    }
    fun todayScheduledCount(plans:List<Plan>,now:Instant,displayZone:ZoneId,
        exceptions:Map<Long,List<PlanException>> = emptyMap()):Int {
        val today=now.atZone(displayZone).toLocalDate()
        val window=PlanWindow(today.atStartOfDay(displayZone).toInstant(),today.plusDays(1).atStartOfDay(displayZone).toInstant())
        val budget=PlanExpansionBudget(10_000)
        return plans.count { plan ->
            PlanOccurrences.inWindow(plan,window,exceptions[plan.id].orEmpty(),true,budget)
                .any { it.time !is PlanOccurrenceTime.Completion }
        }
    }
}
