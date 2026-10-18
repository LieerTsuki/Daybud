package com.example.lifemanager.ui.habit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.ForestTree

internal data class ForestPlanting(val tree: ForestTree, val habitName: String, val number: Int) {
    val identity: String get() = "$habitName，第${number}棵树"
}

/** One shared landscape, independent of the calendar's selected habit and month. */
internal fun LazyListScope.forestSection(state: HabitUiState, onWater: (Long) -> Unit, onPlant: (Long) -> Unit) {
    item(key = "shared_forest_section") { ForestSection(state, onWater, onPlant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForestSection(state: HabitUiState, onWater: (Long) -> Unit, onPlant: (Long) -> Unit) {
    val entries = remember(state.forest.trees, state.habits) {
        val names = state.habits.associate { it.id to it.name }
        val numbers = mutableMapOf<Long, Int>()
        state.forest.trees.sortedBy { it.id }.mapNotNull { tree ->
            names[tree.habitId]?.let { name ->
                val number = (numbers[tree.habitId] ?: 0) + 1
                numbers[tree.habitId] = number
                ForestPlanting(tree, name, number)
            }
        }
    }
    val canBrowse = state.isAvailable && !state.isMaintaining && !state.isLoading
    val canWrite = canBrowse && !state.isWorking && !state.editor.isSaving
    var selectedId by remember(state.generation, canBrowse) { mutableStateOf<Long?>(null) }
    var locating by remember(state.generation, canBrowse) { mutableStateOf(false) }
    var focusRequest by remember(state.generation) { mutableStateOf<Pair<Long, Int>?>(null) }
    val selected = entries.firstOrNull { it.tree.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的森林", style = MaterialTheme.typography.titleLarge)
                Text("把每一次坚持，种进同一片森林", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { locating = true }, enabled = canBrowse && entries.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "查找树木" }) { Text("找一棵树") }
        }
        Text("${entries.size} 棵树  ·  ${entries.count { it.tree.isMature }} 棵已长成  ·  ${state.forest.availableDrops.values.sum()} 滴水",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ForestScene(entries, selectedId, focusRequest, canBrowse, state.generation, onSelect = { selectedId = it })
        Text(if (entries.isEmpty()) "新增打卡任务，就会在这里种下第一颗种子。" else "点一点树木来浇水 · 拖动探索，双指缩放",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.habits.filter { habit -> entries.none { it.tree.habitId == habit.id } }.forEach { habit ->
            OutlinedButton(onClick = { onPlant(habit.id) }, enabled = canWrite,
                modifier = Modifier.semantics { contentDescription = "种第一棵：${habit.name}" }) {
                Text("为${habit.name}种下第一棵树")
            }
        }
    }
    if (selected != null && canBrowse) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TreeDetails(selected, state, canWrite, onWater, onPlant, onClose = { selectedId = null })
        }
    }
    if (locating && canBrowse) {
        AlertDialog(onDismissRequest = { locating = false }, title = { Text("找一棵树") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).testTag("forest_locator")) {
                items(entries, key = { it.tree.id }) { entry ->
                    TextButton(onClick = {
                        focusRequest = entry.tree.id to ((focusRequest?.second ?: 0) + 1)
                        selectedId = entry.tree.id
                        locating = false
                    }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "定位树木：${entry.identity}" }) {
                        Text("${entry.habitName} · ${entry.tree.species.label} · 第${entry.number}棵", Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { locating = false }) { Text("关闭") } })
    }
}

@Composable
private fun TreeDetails(entry: ForestPlanting, state: HabitUiState, canWrite: Boolean,
    onWater: (Long) -> Unit, onPlant: (Long) -> Unit, onClose: () -> Unit) {
    val tree = entry.tree
    val drops = state.forest.availableDrops[tree.habitId] ?: 0
    var previousWater by remember(tree.id) { mutableIntStateOf(tree.wateringCount) }
    val burst = remember(tree.id) { Animatable(0f) }
    LaunchedEffect(tree.wateringCount) {
        val grew = tree.wateringCount > previousWater
        previousWater = tree.wateringCount
        if (grew) { burst.snapTo(1f); burst.animateTo(0f, tween(1400)) }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                TreeIllustration(tree.species, tree.wateringCount, Modifier.fillMaxSize())
                WateringSparkles(burst.value, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.habitName, style = MaterialTheme.typography.titleLarge)
                Text("${tree.species.label} · 第${entry.number}棵", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${growthLabel(tree.wateringCount)} · ${tree.wateringCount}/20", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "关闭树木详情" }) { Text("关闭") }
        }
        LinearProgressIndicator(progress = { tree.wateringCount.coerceIn(0, 20) / 20f }, modifier = Modifier.fillMaxWidth())
        Text("可用水滴 $drops 滴 · 仅用于${entry.habitName}", style = MaterialTheme.typography.bodyMedium)
        if (!tree.isMature) {
            if (drops == 0) Text("收获水滴后再来浇水吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onWater(tree.id) }, enabled = canWrite && drops > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "浇水：${entry.identity}" }) {
                Text(if (state.isWorking) "正在浇水…" else "浇水 1 滴")
            }
        } else {
            Text("已经长成大树，继续留在这片森林里。", color = MaterialTheme.colorScheme.primary)
            if (state.forest.trees.filter { it.habitId == tree.habitId }.all { it.isMature }) {
                Button(onClick = { onPlant(tree.habitId) }, enabled = canWrite,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "再种一棵：${entry.habitName}" }) { Text("在森林里再种一棵") }
            }
        }
    }
}
