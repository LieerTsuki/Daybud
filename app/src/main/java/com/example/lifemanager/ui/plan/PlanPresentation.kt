package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanListRow
import com.example.lifemanager.domain.plan.PlanOccurrenceTime
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Product presentation is fixed to Shanghai; stored historical instants remain unchanged. */
internal val planDisplayZone: ZoneId = ZoneId.of("Asia/Shanghai")
private val planDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val planTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun planDateTimeText(value: Instant): String = planDateTimeFormat.format(value.atZone(planDisplayZone))

/** A partial or invalid entry stays visible so validation can explain it without losing input. */
internal fun planInputDateTime(value: String): String {
    if (value.isBlank()) return value
    val normalized = value.trim().replace(' ', 'T')
    return runCatching { planDateTimeText(OffsetDateTime.parse(normalized).toInstant()) }
        .getOrElse { runCatching { planDateTimeFormat.format(LocalDateTime.parse(normalized)) }.getOrDefault(value) }
}

private fun timeRange(start: Instant?, end: Instant?): String {
    if (start == null || end == null) return "未设时间"
    val first = start.atZone(planDisplayZone)
    val last = end.atZone(planDisplayZone)
    return "${planDateTimeText(start)} — ${if (first.toLocalDate() == last.toLocalDate()) planTimeFormat.format(last) else planDateTimeText(end)}"
}

private fun dateRange(start: LocalDate?, end: LocalDate?): String = when {
    start == null || end == null -> "未设日期"
    start == end -> "$start · 全天"
    else -> "$start — $end · 全天"
}

internal fun planTimeSummary(plan: Plan): String = when (plan.timeMode) {
    PlanTimeMode.NONE -> "未设日期"
    PlanTimeMode.DEADLINE -> plan.dueDate?.toString() ?: plan.dueAt?.let(::planDateTimeText) ?: "未设日期"
    PlanTimeMode.TIMED -> timeRange(plan.startAt, plan.endAt)
    PlanTimeMode.ALL_DAY -> dateRange(plan.allDayStartDate, plan.allDayEndDate)
}

internal fun planTimeSummary(row: PlanListRow): String = when (val time = row.occurrence?.time) {
    is PlanOccurrenceTime.DeadlineInstant -> planDateTimeText(time.at)
    is PlanOccurrenceTime.DeadlineDate -> time.date.toString()
    is PlanOccurrenceTime.Timed -> timeRange(time.start, time.end)
    is PlanOccurrenceTime.AllDay -> dateRange(time.first, time.lastInclusive)
    is PlanOccurrenceTime.Completion -> "系列已完成"
    null -> planTimeSummary(row.plan)
}

internal fun planRepeatSummary(plan: Plan): String {
    if (!plan.isRepeating) return "不重复"
    val interval = plan.repeatInterval
    val rule = when (plan.repeatRule) {
        ScheduleRepeatRule.NONE -> "不重复"
        ScheduleRepeatRule.DAILY -> if (interval == 1) "每天" else "每 $interval 天"
        ScheduleRepeatRule.WEEKLY -> if (interval == 1) "每周" else "每 $interval 周"
        ScheduleRepeatRule.MONTHLY -> if (interval == 1) "每月" else "每 $interval 个月"
        ScheduleRepeatRule.YEARLY -> if (interval == 1) "每年" else "每 $interval 年"
        ScheduleRepeatRule.CUSTOM -> {
            val days = plan.repeatDaysOfWeek.sortedBy { it.value % 7 }.joinToString("、") {
                "周${listOf("一", "二", "三", "四", "五", "六", "日")[it.value - 1]}"
            }
            "${if (interval == 1) "每周" else "每 $interval 周"}${if (days.isBlank()) "" else " · $days"}"
        }
    }
    return rule + (plan.repeatEndDate?.let { " · 至 $it" } ?: "")
}
