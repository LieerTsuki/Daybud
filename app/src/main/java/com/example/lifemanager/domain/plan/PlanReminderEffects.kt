package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.maintenance.DataGeneration

/** Caller owns global admission then the plan module lock. Re-read current Room state;
 * First remove this plan's obsolete occurrence/trigger alarms AND notifications, then arrange
 * the currently qualified candidate. Completion/deletion/time edits/disabled/NONE all invalidate
 * old identities. Read errors propagate; they are not missing/disabled records. Never trust drafts.
 * Implementations must not re-acquire these locks or write business records.
 */
interface PlanReminderEffects {
    suspend fun reconcile(planId: Long, expected: DataGeneration)
}
