package com.example.lifemanager.data.plan

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Target-only operational table. delivered is a decided submission, NOT a user-read receipt.
 * C2c1 covers the shared migration/target fixture; production activation belongs to E. */
@Entity(tableName = "plan_reminder_state")
data class PlanReminderStateEntity(@PrimaryKey val planId: Long, val generation: Long,
    val occurrenceDate: Long, val triggerAt: Long, val delivered: Int)
