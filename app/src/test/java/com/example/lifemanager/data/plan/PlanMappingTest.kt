package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanMappingTest {
    @Test fun `all fields map to domain with correct millis days and joined tags`() {
        val row = PlanEntity(id = 7, title = " 原文 ", description = "", priority = TodoPriority.HIGH,
            color = Int.MIN_VALUE, location = " 地点 ", participants = "", timeMode = PlanTimeMode.ALL_DAY,
            dueAt = 9007199254740993L, dueDate = -1, startAt = -100, endAt = 100,
            allDayStartDate = 1, allDayEndDate = 2, timeZone = "+08:00", isCompleted = true,
            completedAt = 101, repeatRule = ScheduleRepeatRule.CUSTOM, repeatInterval = 3,
            repeatDaysOfWeek = "2,1,2", repeatEndDate = 5, reminderEnabled = false, reminderMinutes = null,
            parentId = 8, sortOrder = -7, createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE)
        val result = runCatching { row.toDomain(listOf(TagEntity(3, "乙", 1, 1), TagEntity(2, "甲", 2, 1))) }
        assertTrue(result.isSuccess)
        assertEquals(Plan(id = 7, title = " 原文 ", description = "", priority = TodoPriority.HIGH,
            color = Int.MIN_VALUE, location = " 地点 ", participants = "", timeMode = PlanTimeMode.ALL_DAY,
            dueAt = Instant.ofEpochMilli(9007199254740993L), dueDate = LocalDate.of(1969, 12, 31),
            startAt = Instant.ofEpochMilli(-100), endAt = Instant.ofEpochMilli(100),
            allDayStartDate = LocalDate.of(1970, 1, 2), allDayEndDate = LocalDate.of(1970, 1, 3),
            timeZone = "+08:00", isCompleted = true, completedAt = Instant.ofEpochMilli(101),
            repeatRule = ScheduleRepeatRule.CUSTOM, repeatInterval = 3,
            repeatDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), repeatEndDate = LocalDate.of(1970, 1, 6),
            reminderEnabled = false, reminderMinutes = null, parentId = 8, sortOrder = -7,
            createdAt = Instant.ofEpochMilli(Long.MIN_VALUE), updatedAt = Instant.ofEpochMilli(Long.MAX_VALUE),
            tagIds = setOf(3, 2), tagNames = listOf("乙", "甲")), result.getOrThrow())
    }

    @Test fun `raw entity round trip retains every nullable field and weekday representation`() {
        val row = PlanEntity(7, " 原文 ", description = "", timeZone = "+08:00", createdAt = Long.MIN_VALUE,
            updatedAt = Long.MAX_VALUE, dueAt = 9007199254740993L, repeatDaysOfWeek = "2,1,2",
            reminderMinutes = null, completedAt = 8, isCompleted = false, dueDate = -1,
            startAt = 1, endAt = 2, allDayStartDate = 3, allDayEndDate = 4)
        val result = runCatching { row.toRaw().toEntity() }
        assertTrue(result.isSuccess)
        assertEquals(row, result.getOrThrow())
        assertEquals("2,1,2", result.getOrThrow().repeatDaysOfWeek)
    }
}
