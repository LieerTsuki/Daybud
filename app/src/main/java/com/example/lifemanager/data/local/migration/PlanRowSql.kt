package com.example.lifemanager.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lifemanager.data.plan.PlanEntity
import com.example.lifemanager.data.plan.RawPlanRow
import com.example.lifemanager.data.plan.toRaw
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode

internal object PlanRowSql {
    fun insert(db: SupportSQLiteDatabase, row: RawPlanRow) {
        db.execSQL("INSERT INTO plans (id,title,description,priority,color,location,participants,timeMode,dueAt,dueDate,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,isCompleted,completedAt,repeatRule,repeatInterval,repeatDaysOfWeek,repeatEndDate,reminderEnabled,reminderMinutes,parentId,sortOrder,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            row.id,
            row.title,
            row.description,
            row.priority.name,
            row.color,
            row.location,
            row.participants,
            row.timeMode.name,
            row.dueAt,
            row.dueDate,
            row.startAt,
            row.endAt,
            row.allDayStartDate,
            row.allDayEndDate,
            row.timeZone,
            if (row.isCompleted) 1 else 0,
            row.completedAt,
            row.repeatRule.name,
            row.repeatInterval,
            row.repeatDaysOfWeek,
            row.repeatEndDate,
            if (row.reminderEnabled) 1 else 0,
            row.reminderMinutes,
            row.parentId,
            row.sortOrder,
            row.createdAt,
            row.updatedAt
        ))
    }

    fun read(db: SupportSQLiteDatabase, id: Long): RawPlanRow =
        db.query("SELECT * FROM plans WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst()) { "Missing converted row" }
            PlanEntity(
                id = c.sqlLong("id"),
                title = c.sqlText("title"),
                description = c.sqlNullableText("description"),
                priority = TodoPriority.valueOf(c.sqlText("priority")),
                color = c.sqlInt("color"),
                location = c.sqlNullableText("location"),
                participants = c.sqlNullableText("participants"),
                timeMode = PlanTimeMode.valueOf(c.sqlText("timeMode")),
                dueAt = c.sqlNullableLong("dueAt"),
                dueDate = c.sqlNullableLong("dueDate"),
                startAt = c.sqlNullableLong("startAt"),
                endAt = c.sqlNullableLong("endAt"),
                allDayStartDate = c.sqlNullableLong("allDayStartDate"),
                allDayEndDate = c.sqlNullableLong("allDayEndDate"),
                timeZone = c.sqlText("timeZone"),
                isCompleted = c.sqlFlag("isCompleted"),
                completedAt = c.sqlNullableLong("completedAt"),
                repeatRule = ScheduleRepeatRule.valueOf(c.sqlText("repeatRule")),
                repeatInterval = c.sqlInt("repeatInterval"),
                repeatDaysOfWeek = c.sqlNullableText("repeatDaysOfWeek"),
                repeatEndDate = c.sqlNullableLong("repeatEndDate"),
                reminderEnabled = c.sqlFlag("reminderEnabled"),
                reminderMinutes = c.sqlNullableInt("reminderMinutes"),
                parentId = c.sqlNullableLong("parentId"),
                sortOrder = c.sqlInt("sortOrder"),
                createdAt = c.sqlLong("createdAt"),
                updatedAt = c.sqlLong("updatedAt"),
            ).toRaw().also { check(!c.moveToNext()) }
        }
}
