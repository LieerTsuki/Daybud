package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.ReminderIdentities
import com.example.lifemanager.domain.plan.PlanReminderToken

/** Caller owns a drained maintenance session; capture both inventories before data commit. */
fun interface BackupReminderCapture { suspend fun snapshot(): ReminderIdentities }

/** System enumeration is outside the SQL transaction, still inside the frozen session. */
fun interface BackupPlanNotificationSource { fun tokens(): List<PlanReminderToken> }
