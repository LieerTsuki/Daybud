package com.example.lifemanager.ui.habit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.ForestTree

/** All habits share this forest; the month calendar's selection never filters these trees. */
internal fun LazyListScope.forestSection(
    state: HabitUiState,
    columns: Int,
    onWater: (Long) -> Unit,
    onPlant: (Long) -> Unit,
) {
    val canWrite = state.isAvailable && !state.isMaintaining && !state.isWorking && !state.editor.isSaving
    item(key = "forest_heading") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("我的林场", style = MaterialTheme.typography.titleLarge)
            Text("每次打卡收获 1 滴水，手动浇给这个任务的树。20 次浇水长成一棵大树。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.forest.trees.isNotEmpty()) {
                Text("${state.forest.trees.size} 棵树 · ${state.forest.trees.count { it.isMature }} 棵已长成",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (state.forest.trees.isEmpty()) {
        item(key = "forest_empty") {
            Text("种下第一棵树，再用打卡收获的水滴慢慢养大。",
                modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.habits.forEach { habit ->
        val trees = state.forest.trees.filter { it.habitId == habit.id }
        val drops = state.forest.availableDrops[habit.id] ?: 0
        item(key = "forest_habit_${habit.id}") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(habit.name, style = MaterialTheme.typography.titleMedium)
                Text("可用水滴 $drops 滴 · 仅用于${habit.name}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trees.all { it.isMature }) {
                    OutlinedButton(onClick = { onPlant(habit.id) }, enabled = canWrite,
                        modifier = Modifier.semantics { contentDescription = "${if (trees.isEmpty()) "种第一棵" else "再种一棵"}：${habit.name}" }) {
                        Text(if (trees.isEmpty()) "种第一棵" else "再种一棵")
                    }
                }
            }
        }
        items(trees.withIndex().toList().chunked(columns), key = { "forest_row_${it.first().value.id}" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (index, tree) ->
                    ForestTreeCard(tree, habit.name, index + 1, drops, canWrite, { onWater(tree.id) }, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ForestTreeCard(
    tree: ForestTree,
    habitName: String,
    number: Int,
    drops: Int,
    canWrite: Boolean,
    onWater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TreeIllustration(tree.species, tree.wateringCount, Modifier.size(100.dp))
            Text("${tree.species.label} · 第${number}棵", style = MaterialTheme.typography.titleSmall)
            Text("${growthLabel(tree.wateringCount)} · ${tree.wateringCount}/20", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { tree.wateringCount.coerceIn(0, 20) / 20f }, modifier = Modifier.fillMaxWidth())
            if (tree.isMature) {
                Text("已长成，留在林场", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 10.dp))
            } else {
                Button(onClick = onWater, enabled = canWrite && drops > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "浇水：${habitName}，第${number}棵树" }) {
                    Text("浇水 1 滴")
                }
            }
        }
    }
}
