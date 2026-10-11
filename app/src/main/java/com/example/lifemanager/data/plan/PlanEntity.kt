package com.example.lifemanager.data.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode

@Entity(
    tableName = "plans",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("parentId"), Index("timeMode"), Index("dueAt"), Index("dueDate"), Index("startAt"), Index("allDayStartDate")],
)
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val priority: TodoPriority = TodoPriority.NONE,
    val color: Int = 0xFF246956.toInt(),
    val location: String? = null,
    val participants: String? = null,
    val timeMode: PlanTimeMode = PlanTimeMode.NONE,
    val dueAt: Long? = null,
    val dueDate: Long? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val allDayStartDate: Long? = null,
    val allDayEndDate: Long? = null,
    val timeZone: String,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val repeatRule: ScheduleRepeatRule = ScheduleRepeatRule.NONE,
    val repeatInterval: Int = 1,
    val repeatDaysOfWeek: String? = null,
    val repeatEndDate: Long? = null,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int? = 15,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
