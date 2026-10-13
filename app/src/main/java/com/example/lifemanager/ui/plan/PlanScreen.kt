package com.example.lifemanager.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.PlannerIllustration
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** E preparation adapter. Root navigation/Hilt activation is deliberately deferred. */
@Composable
fun PlanScreen(viewModel: PlanViewModel) {
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
        viewModel::retryRead, { viewModel.retryReminders(it, renderedGeneration) }, viewModel::dismissMessage, state.displayZone)
}

@Composable
fun PlanScreen(state: PlanUiState, onFilterChanged: (PlanFilter) -> Unit,
    onCompleted: (Long, Boolean) -> Unit, onRetryRead: () -> Unit,
    onRetryReminders: (Long) -> Unit, onDismissMessage: () -> Unit,
    displayZone: ZoneId = state.displayZone) {
    val enabled = state.isAvailable && !state.isMaintaining
    val filter = state.filter
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("计划手账", style = MaterialTheme.typography.headlineMedium)
                        Text("把长远目标与每日安排放在一起", style = MaterialTheme.typography.bodyMedium)
                    }
                    PlannerIllustration(Modifier.size(72.dp))
                }
            }
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
                OutlinedTextField(value = filter.query, onValueChange = { onFilterChanged(filter.copy(query = it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("搜索标题或备注") }, enabled = enabled, singleLine = true)
            }
            item(key = "category") { FilterStrip("分类") {
                PlanCategory.entries.forEach { value ->
                    FilterChip(selected = filter.category == value, onClick = { onFilterChanged(filter.copy(category = value)) }, enabled = enabled,
                        label = { Text(when (value) { PlanCategory.ALL -> "全部分类"; PlanCategory.TODO -> "待办"; PlanCategory.SCHEDULE -> "日程" }) })
                }
            } }
            item(key = "status") { FilterStrip("状态") {
                PlanStatus.entries.forEach { value ->
                    FilterChip(selected = filter.status == value, onClick = { onFilterChanged(filter.copy(status = value)) }, enabled = enabled,
                        label = { Text(when (value) { PlanStatus.UNFINISHED -> "未完成"; PlanStatus.COMPLETED -> "已完成"; PlanStatus.ALL -> "全部状态" }) })
                }
            } }
            item(key = "date") {
                if (filter.category == PlanCategory.TODO) Text("无日期待办不适用日期筛选")
                FilterStrip("日期") {
                    PlanDateFilter.entries.forEach { value ->
                        FilterChip(selected = filter.date == value, onClick = { onFilterChanged(filter.copy(date = value)) },
                            enabled = enabled && filter.category != PlanCategory.TODO,
                            label = { Text(when (value) { PlanDateFilter.ALL -> "全部日期"; PlanDateFilter.TODAY -> "今天"; PlanDateFilter.TOMORROW -> "明天"; PlanDateFilter.WEEK -> "本周" }) })
                    }
                }
            }
            item(key = "priority") { FilterStrip("优先级") {
                FilterChip(selected = filter.priority == null, onClick = { onFilterChanged(filter.copy(priority = null)) }, enabled = enabled, label = { Text("全部优先级") })
                TodoPriority.entries.reversed().forEach { value ->
                    FilterChip(selected = filter.priority == value, onClick = { onFilterChanged(filter.copy(priority = value)) }, enabled = enabled,
                        label = { Text(priorityLabel(value)) })
                }
            } }
            if (state.tags.isNotEmpty() || filter.tagId != null) item(key = "tags") { FilterStrip("标签") {
                FilterChip(selected = filter.tagId == null, onClick = { onFilterChanged(filter.copy(tagId = null)) }, enabled = enabled, label = { Text("全部标签") })
                state.tags.forEach { tag ->
                    FilterChip(selected = filter.tagId == tag.id, onClick = { onFilterChanged(filter.copy(tagId = tag.id)) }, enabled = enabled, label = { Text(tag.name) })
                }
                if (filter.tagId != null && state.tags.none { it.id == filter.tagId }) Text("所选标签已不存在，请清除筛选")
            } }
            item(key = "sort") { FilterStrip("排序") {
                PlanSort.entries.forEach { value ->
                    FilterChip(selected = filter.sort == value, onClick = { onFilterChanged(filter.copy(sort = value)) }, enabled = enabled,
                        label = { Text(when (value) { PlanSort.CREATED -> "创建时间"; PlanSort.DATE -> "截止／发生时间"; PlanSort.PRIORITY -> "优先级排序" }) })
                }
            } }
            if (state.isLoading) item(key = "loading") { CircularProgressIndicator(Modifier.semantics { contentDescription = "正在读取计划" }) }
            if (state.isAvailable && !state.isLoading) item(key = "count") { Text("当前筛选：${state.rows.size} 项（非全库统计）") }
            if (state.rows.isEmpty() && state.isAvailable && !state.isLoading) item(key = "empty") { Text("当前筛选下暂无计划") }
            items(state.rows, key = { "plan-${it.plan.id}" }) { row ->
                PlanListItem(row, enabled && !state.isLoading && row.plan.id !in state.pendingIds, displayZone, onCompleted)
            }
        }
    }
}

@Composable
private fun FilterStrip(title: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
