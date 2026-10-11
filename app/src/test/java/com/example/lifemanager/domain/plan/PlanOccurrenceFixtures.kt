package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun instant(value: String): Instant = Instant.parse(value)
internal fun day(value: String): LocalDate = LocalDate.parse(value)
internal fun window(first: String, lastExclusive: String, zone: String = "UTC") = PlanWindow(
    day(first).atStartOfDay(ZoneId.of(zone)).toInstant(),
    day(lastExclusive).atStartOfDay(ZoneId.of(zone)).toInstant(),
)
internal fun timedPlan(id: Long = 1, zone: String = "UTC") = Plan(
    id = id, title = "计划", timeMode = PlanTimeMode.TIMED,
    startAt = instant("2026-01-01T10:00:00Z"), endAt = instant("2026-01-01T11:00:00Z"),
    timeZone = zone, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)
internal fun allDayPlan(id: Long = 1, zone: String = "UTC") = timedPlan(id, zone).copy(
    timeMode = PlanTimeMode.ALL_DAY, allDayStartDate = day("2026-01-01"), allDayEndDate = day("2026-01-02"),
)
