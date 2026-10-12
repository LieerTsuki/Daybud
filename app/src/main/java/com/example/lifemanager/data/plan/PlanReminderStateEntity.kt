package com.example.lifemanager.data.plan

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Target-only operational table: 0 repair/unconfirmed, 1 decided submission, 2 awaiting arrival.
 * The historical INTEGER name remains; neither 1 nor 2 is a user-read/OS acknowledgement.
 * C2c1 covers the shared migration/target fixture; production activation belongs to E. */
@Entity(tableName = "plan_reminder_state")
data class PlanReminderStateEntity(@PrimaryKey val planId: Long, val generation: Long,
    val occurrenceDate: Long, val triggerAt: Long, val delivered: Int)
