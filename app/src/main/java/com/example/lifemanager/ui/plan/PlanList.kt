package com.example.lifemanager.ui.plan

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.settings.displayDate
import com.example.lifemanager.ui.settings.displayDateTime
import java.time.ZoneId

@Composable
internal fun PlanListItem(row: PlanListRow, enabled: Boolean, displayZone: ZoneId, onCompleted: (Long, Boolean) -> Unit,
    onOpen: ((Long) -> Unit)? = null) {
    val plan = row.plan
    val title = plan.title.ifBlank { "（历史空标题）" }
    val paper by animateColorAsState(if (plan.isCompleted) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "plan-paper")
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = paper),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = plan.isCompleted, enabled = enabled, onCheckedChange = { onCompleted(plan.id, it) },
                modifier = Modifier.semantics { contentDescription = "${if (plan.isCompleted) "撤销完成" else "完成"}：$title" })
            Column(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(enabled = enabled,
                onClickLabel = "打开计划详情") { onOpen(plan.id) } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (plan.isCompleted) TextDecoration.LineThrough else null)
                if (!plan.description.isNullOrEmpty()) Text(plan.description, style = MaterialTheme.typography.bodyMedium)
                Text(timeLabel(row, displayZone), style = MaterialTheme.typography.bodySmall)
                Text(priorityLabel(plan.priority), style = MaterialTheme.typography.labelMedium)
                if (plan.tagNames.isNotEmpty()) Text("标签：${plan.tagNames.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                if (plan.isRepeating) Text("重复系列 · 勾选完成将结束整个系列", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun priorityLabel(value: TodoPriority): String = when (value) {
    TodoPriority.NONE -> "无优先级"
    TodoPriority.LOW -> "低优先级"
    TodoPriority.MEDIUM -> "中优先级"
    TodoPriority.HIGH -> "高优先级"
}

@Composable
internal fun timeLabel(row: PlanListRow, zone: ZoneId): String = when (val time = row.occurrence?.time) {
    is PlanOccurrenceTime.DeadlineInstant -> "日程 · 截止 ${displayDateTime(time.at, zone)}"
    is PlanOccurrenceTime.DeadlineDate -> "日程 · 截止 ${displayDate(time.date)}（${row.plan.timeZone}）"
    is PlanOccurrenceTime.Timed -> "日程 · ${displayDateTime(time.start, zone)} — ${displayDateTime(time.end, zone)}"
    is PlanOccurrenceTime.AllDay -> "日程 · 全天 ${displayDate(time.first)} — ${displayDate(time.lastInclusive)}（${row.plan.timeZone}）"
    is PlanOccurrenceTime.Completion -> "系列已完成 · ${displayDateTime(time.at, zone)}"
    null -> if (row.plan.isScheduled) "日程 · 无后续发生" else "待办 · 无日期"
}
