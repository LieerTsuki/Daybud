package com.example.lifemanager.domain.model

import java.time.Instant

enum class TreeSpecies(val label: String) {
    PINE("松树"), GINKGO("银杏"), CHERRY("樱花"), MAPLE("枫树"), OAK("橡树"), BIRCH("白桦");

    val key: String get() = "tree:$name"

    companion object {
        fun fromHabit(habit: Habit): TreeSpecies = entries.firstOrNull { it.key == habit.iconKey }
            ?: entries[Math.floorMod(habit.id, entries.size.toLong()).toInt()]
    }
}

data class ForestTree(
    val id: Long,
    val habitId: Long,
    val species: TreeSpecies,
    val plantedAt: Instant,
    val wateringCount: Int,
) {
    val isMature: Boolean get() = wateringCount >= 20
}

data class HabitForest(
    val trees: List<ForestTree> = emptyList(),
    val availableDrops: Map<Long, Int> = emptyMap(),
)
