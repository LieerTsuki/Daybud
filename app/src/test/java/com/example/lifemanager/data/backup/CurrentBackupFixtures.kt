package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*

/** Current production fixtures; historical V2 fixtures remain the exact schema6 contract. */
internal object CurrentBackupFixtures {
    fun empty() = forest(V2BackupFixtures.empty())
    fun full() = forest(V2BackupFixtures.full())
    fun document(payload: V2BackupPayload = full()) = V2BackupFixtures.document(payload)
    fun forest(payload: V2BackupPayload) = payload.copy(tables = payload.tables + mapOf(
        "habit_trees" to emptyList<BackupRow>(), "habit_waterings" to emptyList<BackupRow>()))
}
