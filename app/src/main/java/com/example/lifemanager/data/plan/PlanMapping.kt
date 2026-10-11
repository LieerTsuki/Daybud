package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

fun PlanEntity.toDomain(tags: List<TagEntity> = emptyList()): Plan {
    require(id > 0 && (parentId == null || parentId > 0)) { "Invalid stored plan identity" }
    val plan = Plan(
        id = id,
        title = title,
        description = description,
        priority = priority,
        color = color,
        location = location,
        participants = participants,
        timeMode = timeMode,
        dueAt = dueAt?.let(Instant::ofEpochMilli),
        dueDate = dueDate?.let(LocalDate::ofEpochDay),
        startAt = startAt?.let(Instant::ofEpochMilli),
        endAt = endAt?.let(Instant::ofEpochMilli),
        allDayStartDate = allDayStartDate?.let(LocalDate::ofEpochDay),
        allDayEndDate = allDayEndDate?.let(LocalDate::ofEpochDay),
        timeZone = timeZone,
        isCompleted = isCompleted,
        completedAt = completedAt?.let(Instant::ofEpochMilli),
        repeatRule = repeatRule,
        repeatInterval = repeatInterval,
        repeatDaysOfWeek = parsePlanWeekdays(repeatDaysOfWeek),
        repeatEndDate = repeatEndDate?.let(LocalDate::ofEpochDay),
        reminderEnabled = reminderEnabled,
        reminderMinutes = reminderMinutes,
        parentId = parentId,
        sortOrder = sortOrder,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        tagIds = tags.map { it.id }.toSet(),
        tagNames = tags.map { it.name },
    )
    val error = PlanRules.validateForRead(plan)
    require(error == null) { "Invalid stored plan $id: $error" }
    return plan
}

internal fun parsePlanWeekdays(value: String?): Set<DayOfWeek> =
    if (value.isNullOrEmpty()) emptySet()
    else value.split(',').map { token ->
        val day = token.toIntOrNull()
        require(day != null && day in 1..7) { "Invalid stored weekday" }
        DayOfWeek.of(day)
    }.toSet()

fun PlanEntity.toRaw(): RawPlanRow = RawPlanRow(
    id = id,
    title = title,
    description = description,
    priority = priority,
    color = color,
    location = location,
    participants = participants,
    timeMode = timeMode,
    dueAt = dueAt,
    dueDate = dueDate,
    startAt = startAt,
    endAt = endAt,
    allDayStartDate = allDayStartDate,
    allDayEndDate = allDayEndDate,
    timeZone = timeZone,
    isCompleted = isCompleted,
    completedAt = completedAt,
    repeatRule = repeatRule,
    repeatInterval = repeatInterval,
    repeatDaysOfWeek = repeatDaysOfWeek,
    repeatEndDate = repeatEndDate,
    reminderEnabled = reminderEnabled,
    reminderMinutes = reminderMinutes,
    parentId = parentId,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RawPlanRow.toEntity(): PlanEntity = PlanEntity(
    id = id,
    title = title,
    description = description,
    priority = priority,
    color = color,
    location = location,
    participants = participants,
    timeMode = timeMode,
    dueAt = dueAt,
    dueDate = dueDate,
    startAt = startAt,
    endAt = endAt,
    allDayStartDate = allDayStartDate,
    allDayEndDate = allDayEndDate,
    timeZone = timeZone,
    isCompleted = isCompleted,
    completedAt = completedAt,
    repeatRule = repeatRule,
    repeatInterval = repeatInterval,
    repeatDaysOfWeek = repeatDaysOfWeek,
    repeatEndDate = repeatEndDate,
    reminderEnabled = reminderEnabled,
    reminderMinutes = reminderMinutes,
    parentId = parentId,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun Plan.toEntity(now: Instant, previous: PlanEntity?): PlanEntity = PlanEntity(
    id = id,
    title = title,
    description = description,
    priority = priority,
    color = color,
    location = location,
    participants = participants,
    timeMode = timeMode,
    dueAt = dueAt?.toEpochMilli(),
    dueDate = dueDate?.toEpochDay(),
    startAt = startAt?.toEpochMilli(),
    endAt = endAt?.toEpochMilli(),
    allDayStartDate = allDayStartDate?.toEpochDay(),
    allDayEndDate = allDayEndDate?.toEpochDay(),
    timeZone = timeZone,
    isCompleted = previous?.isCompleted ?: false,
    completedAt = previous?.completedAt,
    repeatRule = repeatRule,
    repeatInterval = repeatInterval,
    repeatDaysOfWeek = if (previous != null && repeatDaysOfWeek == parsePlanWeekdays(previous.repeatDaysOfWeek)) previous.repeatDaysOfWeek
        else repeatDaysOfWeek.map { it.value }.sorted().joinToString(",").ifEmpty { null },
    repeatEndDate = repeatEndDate?.toEpochDay(),
    reminderEnabled = reminderEnabled,
    reminderMinutes = reminderMinutes,
    parentId = parentId,
    sortOrder = sortOrder,
    createdAt = previous?.createdAt ?: now.toEpochMilli(),
    updatedAt = now.toEpochMilli(),
)

internal fun TagEntity.toPlanTag(): Tag = Tag(id, name, color, Instant.ofEpochMilli(createdAt))
