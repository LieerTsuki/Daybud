package com.example.lifemanager.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.DayOfWeek

@Composable
fun PlanEditor(state: PlanEditorState, model: PlanEditorViewModel) {
    val draft = state.draft ?: return
    // Every callback carries the immutable stamp of the form that rendered it.
    val stamp = state.stamp
    val enabled = state.isAvailable && !state.isMaintaining && !state.isBusy
    val reminderOn = draft.reminderEnabled && draft.reminderMinutes.toIntOrNull()?.let { it >= 0 } == true
    key(stamp.generation, stamp.session) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PlanFormHeading(if (draft.base.id == 0L) "种下新计划" else "整理计划", "给想做的事，留一个位置") }
                planFeedback(state, model)
                item { PlanQuadrantMenu(draft.priority, enabled) { model.setPriority(it, stamp) } }
                item { PlanSection("") {
                    PlanInput("计划标题", draft.title, enabled, displayLabel = "计划名称") { model.change(PlanField.TITLE, it, stamp) }
                    PlanInput("计划备注", draft.description, enabled, singleLine = false, displayLabel = "备注") { model.change(PlanField.DESCRIPTION, it, stamp) }
                } }
                item { PlanSection("时间安排") {
                    PlanChoiceRow("") {
                        PlanTimeMode.entries.forEach { mode -> FilterChip(draft.mode == mode, { model.setMode(mode, stamp) }, enabled = enabled,
                            label = { Text(modeLabel(mode)) }) }
                    }
                    when (draft.mode) {
                        PlanTimeMode.NONE -> Text("未设日期", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PlanTimeMode.DEADLINE -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.dateOnly, { model.setDateOnly(it, stamp) }, enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = "仅日期截止" })
                                Text("只选日期")
                            }
                            if (draft.dateOnly) PlanDateInput("截止日期", "截止日期输入", draft.dueDate, enabled) { model.change(PlanField.DUE_DATE, it, stamp) }
                            else PlanDateInput("截止时间", "截止时间输入", draft.dueAt, enabled, withTime = true) { model.changeShanghai(PlanField.DUE_AT, it, stamp) }
                        }
                        PlanTimeMode.TIMED -> {
                            PlanDateInput("开始时间", "开始时间输入", draft.startAt, enabled, withTime = true) { model.changeShanghai(PlanField.START_AT, it, stamp) }
                            PlanDateInput("结束时间", "结束时间输入", draft.endAt, enabled, withTime = true) { model.changeShanghai(PlanField.END_AT, it, stamp) }
                        }
                        PlanTimeMode.ALL_DAY -> {
                            PlanDateInput("开始日期", "全天开始日期输入", draft.allDayStart, enabled) { model.change(PlanField.ALL_DAY_START, it, stamp) }
                            PlanDateInput("结束日期", "全天结束日期输入", draft.allDayEnd, enabled) { model.change(PlanField.ALL_DAY_END, it, stamp) }
                        }
                    }
                } }
                item { PlanSection("重复") {
                    if (draft.mode == PlanTimeMode.TIMED || draft.mode == PlanTimeMode.ALL_DAY) {
                        PlanChoiceRow("") { ScheduleRepeatRule.entries.forEach { rule -> FilterChip(draft.repeatRule == rule,
                            { model.setRepeat(rule, stamp) }, enabled = enabled, label = { Text(repeatLabel(rule)) }) } }
                        if (draft.repeatRule != ScheduleRepeatRule.NONE) {
                            PlanInput("重复间隔", draft.repeatInterval, enabled, displayLabel = "每隔") { model.change(PlanField.REPEAT_INTERVAL, it, stamp) }
                            if (draft.repeatRule == ScheduleRepeatRule.CUSTOM) PlanChoiceRow("选择星期") {
                                (listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }).forEach { day ->
                                    FilterChip(day in draft.repeatDays, { model.toggleDay(day, stamp) }, enabled = enabled,
                                        label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day.value - 1]) })
                                }
                            }
                            PlanDateInput("结束重复（可留空）", "重复结束日期", draft.repeatEnd, enabled) { model.change(PlanField.REPEAT_END, it, stamp) }
                        }
                    } else Text("不重复", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { PlanSection("") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("闹钟提醒", style = MaterialTheme.typography.titleMedium)
                            val lead = draft.reminderMinutes.toIntOrNull() ?: 0
                            Text(when {
                                !reminderOn -> "关闭提醒"
                                draft.mode == PlanTimeMode.NONE -> "设置日期后提醒"
                                lead > 0 -> "提前 $lead 分钟提醒"
                                else -> "到时提醒"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(reminderOn, { model.setReminder(it, stamp) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "单项计划提醒" })
                    }
                } }
                item { Button({ model.save(stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.isBusy) "正在保存…" else "保存计划") } }
                item { OutlinedButton({ model.requestClose(stamp) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回计划") } }
            }
        }
    }
    PlanEditorDialogs(state, model)
}

@Composable
private fun PlanQuadrantMenu(priority: TodoPriority, enabled: Boolean, onSelect: (TodoPriority) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = quadrantPalette(priority)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("象限", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Box {
            FilledTonalButton({ expanded = true }, enabled = enabled,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.container, contentColor = palette.content),
                modifier = Modifier.semantics { contentDescription = "选择计划象限" }) {
                Text(priorityLabel(priority)); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded && enabled, { expanded = false }) {
                listOf(TodoPriority.HIGH, TodoPriority.MEDIUM, TodoPriority.LOW, TodoPriority.NONE).forEach { item ->
                    DropdownMenuItem(text = { Text(priorityLabel(item)) }, onClick = { expanded = false; onSelect(item) })
                }
            }
        }
    }
}

@Composable
internal fun PlanInput(label: String, value: String, enabled: Boolean, hint: String? = null,
    singleLine: Boolean = true, displayLabel: String = label, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else 3,
        label = { Text(displayLabel) }, shape = MaterialTheme.shapes.medium,
        supportingText = hint?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
internal fun PlanChoiceRow(title: String, content: @Composable RowScope.() -> Unit) {
    Column {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

internal fun modeLabel(mode: PlanTimeMode): String = when (mode) {
    PlanTimeMode.NONE -> "无日期待办"; PlanTimeMode.DEADLINE -> "截止日期"; PlanTimeMode.TIMED -> "时间区间"; PlanTimeMode.ALL_DAY -> "全天日期"
}
internal fun repeatLabel(rule: ScheduleRepeatRule): String = when (rule) {
    ScheduleRepeatRule.NONE -> "不重复"; ScheduleRepeatRule.DAILY -> "每天"; ScheduleRepeatRule.WEEKLY -> "每周"
    ScheduleRepeatRule.MONTHLY -> "每月"; ScheduleRepeatRule.YEARLY -> "每年"; ScheduleRepeatRule.CUSTOM -> "自定义"
}
