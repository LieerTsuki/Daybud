package com.example.lifemanager.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lifemanager.ui.settings.displayDate
import java.time.LocalDate

/** All entries for the tapped civil date, using the same admitted month snapshot. */
@Composable
internal fun PlanDayDetails(day: LocalDate, state: PlanCalendarState, onDismiss: () -> Unit,
    onRetry: () -> Unit, onOpen: (Long) -> Unit) {
    val enabled = state.isAvailable && !state.isLoading && !state.isMaintaining
    val rows = state.days[day].orEmpty()
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 12.dp).testTag("plan-day-details"),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(displayDate(day), style = MaterialTheme.typography.titleLarge)
                        if (enabled) Text("当天共 ${rows.size} 项计划", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭当天详情") }
                }
                Text("长名称可左右滑动查看，点任务可打开与编辑", style = MaterialTheme.typography.bodySmall)
                when {
                    state.isMaintaining -> Text("正在维护数据，稍后再查看当天计划")
                    state.readError != null || !state.isAvailable -> {
                        Text(state.readError ?: "暂时无法读取当天计划", color = MaterialTheme.colorScheme.error)
                        TextButton(onRetry) { Text("重新读取日历") }
                    }
                    state.isLoading -> CircularProgressIndicator()
                    else -> LazyColumn(Modifier.weight(1f).testTag("plan-day-details-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (rows.isEmpty()) item { Text("这一天还没有计划") }
                        items(rows, key = { "${it.plan.id}-${it.occurrence?.occurrenceDate}" }) { row ->
                            val plan = row.plan
                            val originalTitle = plan.title.ifBlank { "（历史空标题）" }
                            val displayTitle = originalTitle.replace(Regex("[\\r\\n]+"), " ")
                            val palette = quadrantPalette(plan.priority)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, palette.container), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().testTag("plan-calendar-entry-${plan.id}")
                                    .clickable(enabled = enabled, onClickLabel = "打开计划详情") { onOpen(plan.id) }
                                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(color = palette.container, contentColor = palette.content,
                                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Text(displayTitle, maxLines = 1, softWrap = false,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp).semantics {
                                                if (originalTitle != displayTitle) contentDescription = originalTitle
                                            })
                                    }
                                    Text(priorityLabel(plan.priority), style = MaterialTheme.typography.labelMedium)
                                    Text(timeLabel(row, state.displayZone), style = MaterialTheme.typography.bodyMedium)
                                    Text(if (plan.isCompleted) "已完成" else "未完成", style = MaterialTheme.typography.labelMedium)
                                    if (!plan.description.isNullOrBlank()) Text(plan.description)
                                    Text(planRepeatSummary(plan), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
