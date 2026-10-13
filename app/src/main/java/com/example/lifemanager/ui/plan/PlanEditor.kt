package com.example.lifemanager.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    // Capture a value, never a delegated/live state getter, in every rendered callback.
    val stamp = state.stamp
    val enabled = state.isAvailable && !state.isMaintaining && !state.isBusy
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PlanFormHeading(if (draft.base.id == 0L) "种下新计划" else "整理计划", "无日期是待办，有日期是日程；转换保留同一条记录") }
            item { PlanInput("计划标题", draft.title, enabled) { model.change(PlanField.TITLE, it, stamp) } }
            item { PlanInput("计划备注", draft.description, enabled, singleLine = false) { model.change(PlanField.DESCRIPTION, it, stamp) } }
            planFeedback(state, model)
            item { PlanChoiceRow("时间类型") {
                PlanTimeMode.entries.forEach { mode -> FilterChip(draft.mode == mode, { model.setMode(mode, stamp) }, enabled = enabled,
                    label = { Text(modeLabel(mode)) }) }
            } }
            when (draft.mode) {
                PlanTimeMode.NONE -> item { Text("长期目标不设日期。历史时间、重复和提醒参数保留，但不会生成日程或提醒。") }
                PlanTimeMode.DEADLINE -> {
                    item { Row { Checkbox(draft.dateOnly, { model.setDateOnly(it, stamp) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "仅日期截止" }); Text("仅日期截止（不转换为午夜时间）") } }
                    item { if (draft.dateOnly) PlanInput("截止日期输入", draft.dueDate, enabled, "日期：2026-10-20") { model.change(PlanField.DUE_DATE, it, stamp) }
                        else PlanInput("截止时间输入", draft.dueAt, enabled, "时间：2026-10-20T12:00+08:00") { model.change(PlanField.DUE_AT, it, stamp) } }
                    item { Text("截止日期与时刻互斥，保存时仅保留所选截止字段。截止日程不生成重复；历史重复参数保留。") }
                }
                PlanTimeMode.TIMED -> {
                    item { PlanInput("开始时间输入", draft.startAt, enabled, "时间：2026-10-20T12:00+08:00") { model.change(PlanField.START_AT, it, stamp) } }
                    item { PlanInput("结束时间输入", draft.endAt, enabled, "时间：2026-10-20T13:00+08:00") { model.change(PlanField.END_AT, it, stamp) } }
                }
                PlanTimeMode.ALL_DAY -> {
                    item { PlanInput("全天开始日期输入", draft.allDayStart, enabled, "日期：2026-10-20") { model.change(PlanField.ALL_DAY_START, it, stamp) } }
                    item { PlanInput("全天结束日期输入", draft.allDayEnd, enabled, "日期：2026-10-20（含当天）") { model.change(PlanField.ALL_DAY_END, it, stamp) } }
                }
            }
            item { PlanInput("IANA 时区", draft.timeZone, enabled, "如 Asia/Shanghai；更换时区保留未改动时间的实际时刻") { model.change(PlanField.ZONE, it, stamp) } }
            item { PlanChoiceRow("优先级") { TodoPriority.entries.forEach { priority -> FilterChip(draft.priority == priority,
                { model.setPriority(priority, stamp) }, enabled = enabled, label = { Text(priorityLabel(priority)) }) } } }
            item { PlanInput("地点", draft.location, enabled) { model.change(PlanField.LOCATION, it, stamp) } }
            item { PlanInput("参与人", draft.participants, enabled) { model.change(PlanField.PARTICIPANTS, it, stamp) } }
            item { PlanInput("标记颜色", draft.color, enabled, "RRGGBB 或 AARRGGBB") { model.change(PlanField.COLOR, it, stamp) } }
            item { PlanInput("新标签", draft.newTag, enabled) { model.change(PlanField.NEW_TAG, it, stamp) }
                TextButton({ model.addTag(stamp) }, enabled = enabled && draft.newTag.isNotBlank()) { Text("添加标签") } }
            if (draft.tags.isNotEmpty()) item { PlanChoiceRow("标签（点击移除）") { draft.tags.forEach { tag ->
                InputChip(true, { model.removeTag(tag, stamp) }, enabled = enabled, label = { Text(tag) }) } } }
            if (draft.mode == PlanTimeMode.TIMED || draft.mode == PlanTimeMode.ALL_DAY) {
                item { PlanChoiceRow("重复规则") { ScheduleRepeatRule.entries.forEach { rule -> FilterChip(draft.repeatRule == rule,
                    { model.setRepeat(rule, stamp) }, enabled = enabled, label = { Text(repeatLabel(rule)) }) } } }
                if (draft.repeatRule != ScheduleRepeatRule.NONE) {
                    item { PlanInput("重复间隔", draft.repeatInterval, enabled, "正整数；自定义按周间隔") { model.change(PlanField.REPEAT_INTERVAL, it, stamp) } }
                    if (draft.repeatRule == ScheduleRepeatRule.CUSTOM) item { PlanChoiceRow("自定义星期") { DayOfWeek.entries.forEach { day ->
                        FilterChip(day in draft.repeatDays, { model.toggleDay(day, stamp) }, enabled = enabled,
                            label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day.value - 1]) }) } } }
                    item { PlanInput("重复结束日期", draft.repeatEnd, enabled, "可留空；日期含当天") { model.change(PlanField.REPEAT_END, it, stamp) } }
                    item { Text("完成操作结束整个重复系列，不是单次打卡。") }
                }
            }
            item { Row { Switch(draft.reminderEnabled, { model.setReminder(it, stamp) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "单项计划提醒" }); Text("单项计划提醒") } }
            item { PlanInput("提前分钟", draft.reminderMinutes, enabled, "非负整数；留空为不投递。无日期、已完成不投递") { model.change(PlanField.REMINDER_MINUTES, it, stamp) } }
            item { Text("未启用的时间／重复参数会原样保留。父计划：${draft.base.parentId ?: "无"}；排序：${draft.base.sortOrder}") }
            item { Button({ model.save(stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state.isBusy) "正在保存…" else "保存计划") } }
            item { OutlinedButton({ model.requestClose(stamp) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("返回计划") } }
        }
    }
    PlanEditorDialogs(state, model)
}

@Composable
internal fun PlanInput(label: String, value: String, enabled: Boolean, hint: String? = null,
    singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, singleLine = singleLine, label = { Text(label) },
        supportingText = hint?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
internal fun PlanChoiceRow(title: String, content: @Composable RowScope.() -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

internal fun modeLabel(mode: PlanTimeMode): String = when (mode) {
    PlanTimeMode.NONE -> "无日期待办"; PlanTimeMode.DEADLINE -> "截止日期"; PlanTimeMode.TIMED -> "时间区间"; PlanTimeMode.ALL_DAY -> "全天日期"
}
internal fun repeatLabel(rule: ScheduleRepeatRule): String = when (rule) {
    ScheduleRepeatRule.NONE -> "不重复"; ScheduleRepeatRule.DAILY -> "每天"; ScheduleRepeatRule.WEEKLY -> "每周"
    ScheduleRepeatRule.MONTHLY -> "每月"; ScheduleRepeatRule.YEARLY -> "每年"; ScheduleRepeatRule.CUSTOM -> "自定义"
}
