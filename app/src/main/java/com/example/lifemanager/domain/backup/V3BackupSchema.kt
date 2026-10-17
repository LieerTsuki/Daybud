package com.example.lifemanager.domain.backup

/** Explicit format3 / schema7 contract. Historical V2 whitelist remains frozen. */
object V3BackupSchema {
    val species = listOf("PINE", "GINKGO", "CHERRY", "MAPLE", "OAK", "BIRCH")
    val tables = V2BackupSchema.tables + listOf(
        BackupTable("habit_trees", listOf(BackupColumn("id", BackupKind.LONG), BackupColumn("habitId", BackupKind.LONG),
            BackupColumn("species", BackupKind.TEXT, choices = species.toSet()), BackupColumn("plantedAt", BackupKind.MILLIS)),
            listOf("id"), references = listOf(BackupReference("habitId", "habits"))),
        BackupTable("habit_waterings", listOf(BackupColumn("recordId", BackupKind.LONG), BackupColumn("treeId", BackupKind.LONG)),
            listOf("recordId"), references = listOf(BackupReference("recordId", "habit_records"), BackupReference("treeId", "habit_trees"))),
    )
    internal val maxRowMembers = tables.maxOf { it.columns.size }
}
