package com.example.lifemanager.notification

import com.example.lifemanager.domain.backup.BackupPlanNotificationSource

/** Reuse the strict owned-card inventory, never enumerate arbitrary notification IDs as plans. */
class PlanBackupNotificationSource(private val inventory: PlanNotificationInventory) : BackupPlanNotificationSource {
    override fun tokens() = inventory.tokens().toList()
}
