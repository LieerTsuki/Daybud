package com.example.lifemanager.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.PlannerIllustration
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Lifecycle-aware unified list bound to the Activity's retained production owner. */
@Composable
fun PlanScreen(viewModel: PlanViewModel, onNew: ((DataGeneration?) -> Unit)? = null,
    onOpen: ((Long, DataGeneration?) -> Unit)? = null, canNavigate: Boolean = true,
    extraContent: LazyListScope.() -> Unit = {},
    onNewInQuadrant: ((TodoPriority, DataGeneration?) -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Delegated state getters are live. Old UI actions must not borrow a replacement's token.
    val renderedGeneration = state.generation
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { viewModel.refresh(ZoneId.systemDefault()); delay(60_000) }
        }
    }
    PlanScreen(state, { viewModel.onFilterChanged(it, renderedGeneration) },
        { id, completed -> viewModel.setCompleted(id, completed, renderedGeneration) },
        viewModel::retryRead, { viewModel.retryReminders(it, renderedGeneration) }, viewModel::dismissMessage, state.displayZone,
        onNew?.let { action -> { action(renderedGeneration) } }, onOpen?.let { action -> { id -> action(id, renderedGeneration) } }, canNavigate, extraContent,
        onNewInQuadrant?.let { action -> { priority -> action(priority, renderedGeneration) } })
}

@Composable
fun PlanScreen(state: PlanUiState, onFilterChanged: (PlanFilter) -> Unit,
    onCompleted: (Long, Boolean) -> Unit, onRetryRead: () -> Unit,
    onRetryReminders: (Long) -> Unit, onDismissMessage: () -> Unit,
    displayZone: ZoneId = state.displayZone, onNew: (() -> Unit)? = null, onOpen: ((Long) -> Unit)? = null,
    canNavigate: Boolean = true, extraContent: LazyListScope.() -> Unit = {},
    onNewInQuadrant: ((TodoPriority) -> Unit)? = null) {
    val enabled = state.isAvailable && !state.isMaintaining
    val filter = state.filter
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("计划手账", style = MaterialTheme.typography.headlineMedium)
                        Text("按重要与紧急，安排每一天", style = MaterialTheme.typography.bodyMedium)
                    }
                    PlannerIllustration(Modifier.size(48.dp))
                }
            }
            extraContent()
            if (state.isMaintaining) item(key = "maintenance") { Text("正在维护数据，暂不能修改") }
            if (state.readError != null) item(key = "read-error") {
                Column {
                    Text(state.readError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetryRead, enabled = !state.isMaintaining) { Text("重新读取") }
                }
            }
            if (state.message != null) item(key = "message") {
                Column {
                    Text(state.message)
                    TextButton(onClick = onDismissMessage) { Text("收起提示") }
                }
            }
            state.reminderFailures.filterKeys { it !in state.pendingIds }.forEach { (id, _) ->
                item(key = "reminder-$id") {
                    Column {
                        Text("计划 #$id 已保存，但提醒更新失败", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onRetryReminders(id) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "重试提醒：$id" }) { Text("重试提醒") }
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(value = filter.query,
                    onValueChange = { onFilterChanged(PlanFilter(status = PlanStatus.ALL, query = it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("搜索计划") }, enabled = enabled, singleLine = true)
            }
            if (state.isLoading) item(key = "loading") {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "正在读取计划" })
            }
            val quadrants = listOf(TodoPriority.HIGH, TodoPriority.MEDIUM, TodoPriority.LOW, TodoPriority.NONE)
            quadrants.chunked(2).forEachIndexed { index, pair ->
                item(key = "quadrant-row-$index") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { priority ->
                            QuadrantCard(priority, state.rows.filter { it.plan.priority == priority }, state,
                                enabled && !state.isLoading, displayZone, onCompleted, if (canNavigate) onOpen else null,
                                if (onNewInQuadrant != null || onNew != null) ({ onNewInQuadrant?.invoke(priority) ?: onNew?.invoke() }) else null,
                                canNavigate, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantCard(priority: TodoPriority, rows: List<PlanListRow>, state: PlanUiState,
    enabled: Boolean, zone: ZoneId, onCompleted: (Long, Boolean) -> Unit, onOpen: ((Long) -> Unit)?,
    onAdd: (() -> Unit)?, canNavigate: Boolean, modifier: Modifier = Modifier) {
    // Keep each expansion local to its quadrant and data generation, never a database filter.
    var showCompleted by rememberSaveable(priority, state.generation?.value) { mutableStateOf(false) }
    var showAll by rememberSaveable(priority, state.generation?.value, state.filter.query) { mutableStateOf(false) }
    val active = rows.filterNot { it.plan.isCompleted }
    val completed = rows.filter { it.plan.isCompleted }
    val palette = quadrantPalette(priority)
    Card(modifier.testTag("plan-quadrant-${priority.name}"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 224.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(color = palette.container, contentColor = palette.content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(priorityLabel(priority), style = MaterialTheme.typography.titleSmall)
                    Text("${active.size} 项待完成", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (onAdd != null) TextButton(onClick = onAdd, enabled = enabled && canNavigate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "添加计划：${priorityLabel(priority)}"
                }) { Text("＋ 添加计划") }
            if (active.isEmpty() && state.isAvailable && !state.isLoading) {
                Text(if (state.filter.query.isBlank()) "留一点空间，开始新计划" else "没有匹配的计划",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (if (showAll) active else active.take(3)).forEach { row ->
                QuadrantEntry(row, enabled && row.plan.id !in state.pendingIds, zone, onCompleted, onOpen)
            }
            if (active.size > 3) TextButton({ showAll = !showAll }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !state.isMaintaining) { Text(if (showAll) "收起计划" else "展开其余 ${active.size - 3} 项") }
            if (completed.isNotEmpty()) {
                TextButton({ showCompleted = !showCompleted }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "${priorityLabel(priority)}：已完成 ${completed.size} 项" },
                    enabled = !state.isMaintaining) { Text("已完成 ${completed.size} 项 ${if (showCompleted) "⌃" else "⌄"}") }
                if (showCompleted) completed.forEach { row ->
                    QuadrantEntry(row, enabled && row.plan.id !in state.pendingIds, zone, onCompleted, onOpen)
                }
            }
        }
    }
}

@Composable
private fun QuadrantEntry(row: PlanListRow, enabled: Boolean, zone: ZoneId,
    onCompleted: (Long, Boolean) -> Unit, onOpen: ((Long) -> Unit)?) {
    val plan = row.plan
    val title = plan.title.ifBlank { "（历史空标题）" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("quadrant-plan-${plan.id}")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            textDecoration = if (plan.isCompleted) TextDecoration.LineThrough else null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onOpen != null) Modifier.clickable(
                enabled = enabled, onClickLabel = "打开计划详情") { onOpen(plan.id) } else Modifier).padding(top = 8.dp))
        if (plan.isRepeating) Text("重复系列 · 勾选完成将结束整个系列",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (plan.isScheduled) timeLabel(row, zone) else "未设日期", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Checkbox(plan.isCompleted, { onCompleted(plan.id, it) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${if (plan.isCompleted) "撤销完成" else "完成"}：$title" })
        }
    }
}
