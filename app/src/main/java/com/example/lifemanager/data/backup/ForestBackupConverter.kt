package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*

/** Called only after the source codec has checked its original hash and exact old contract. */
internal class ForestBackupConverter(private val limits: BackupLimits) {
    fun convert(source: V2BackupDocument): V2BackupDocument {
        V2BackupValidator.validate(source, limits)
        val habits = source.payload.tables.getValue("habits")
        checkBackup(source.payload.tables.values.sumOf { it.size.toLong() } + habits.size <= limits.maxRows,
            "转换后的备份超过 ${limits.maxRows} 行限制（包含种子）")
        val trees = habits.sortedBy { (it.fields.getValue("id") as BackupValue.Integer).value }.mapIndexed { index, habit ->
            val id = (habit.fields.getValue("id") as BackupValue.Integer).value
            val key = (habit.fields.getValue("iconKey") as BackupValue.Text).value
            val selected = key.removePrefix("tree:").takeIf { key.startsWith("tree:") && it in V3BackupSchema.species }
                ?: V3BackupSchema.species[Math.floorMod(id, V3BackupSchema.species.size.toLong()).toInt()]
            BackupRow(mapOf("id" to BackupValue.Integer(index.toLong() + 1), "habitId" to BackupValue.Integer(id),
                "species" to BackupValue.Text(selected), "plantedAt" to habit.fields.getValue("createdAt")))
        }
        return source.copy(payload = source.payload.copy(tables = source.payload.tables + mapOf(
            "habit_trees" to trees, "habit_waterings" to emptyList<BackupRow>()))).also {
            V3BackupValidator.validate(it, limits)
            CanonicalBackupJson.byteCount(V3BackupJsonMapping.envelope(it), limits.maxBytes)
        }
    }
}
