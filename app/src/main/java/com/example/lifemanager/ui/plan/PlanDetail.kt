package com.example.lifemanager.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode
import com.example.lifemanager.ui.common.PlannerIllustration

@Composable
fun PlanDetail(state: PlanEditorState, model: PlanEditorViewModel) {
    val stamp = state.stamp
    val plan = state.detail
    val enabled = state.isAvailable && !state.isMaintaining && !state.isBusy && plan != null
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PlanFormHeading(plan?.title?.ifBlank { "（历史空标题）" } ?: "读取计划", "计划详情", plan?.priority) }
            planFeedback(state, model)
            if (plan != null) {
                item { PlanSection("备注") { Text(plan.description?.takeIf { it.isNotBlank() } ?: "暂无备注", style = MaterialTheme.typography.bodyLarge) } }
                item { PlanSection(if (plan.timeMode == PlanTimeMode.DEADLINE) "截止日期" else "时间安排") {
                    Text(planTimeSummary(plan), style = MaterialTheme.typography.bodyLarge)
                } }
                item { PlanSection("重复") { Text(planRepeatSummary(plan), style = MaterialTheme.typography.bodyLarge) } }
                item { Button({ model.beginEdit(stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("编辑计划") } }
                item { OutlinedButton({ model.setCompleted(!plan.isCompleted, stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (plan.isCompleted) "撤销完成" else "勾选完成")
                } }
                item { TextButton({ model.requestDelete(stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("删除计划", color = MaterialTheme.colorScheme.error) } }
            }
            item { OutlinedButton({ model.requestClose(stamp) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("返回计划") } }
        }
    }
    PlanEditorDialogs(state, model)
}

@Composable
internal fun PlanFormHeading(title: String, subtitle: String, priority: TodoPriority? = null) {
    val palette = priority?.let { quadrantPalette(it) }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(
        containerColor = palette?.container ?: MaterialTheme.colorScheme.primaryContainer,
        contentColor = palette?.content ?: MaterialTheme.colorScheme.onPrimaryContainer)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subtitle, style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (priority != null) Text(priorityLabel(priority), style = MaterialTheme.typography.labelMedium)
            }
            PlannerIllustration(Modifier.size(44.dp))
        }
    }
}

@Composable
internal fun PlanSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
