package com.example.lifemanager.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lifemanager.ui.common.PlannerIllustration

@Composable
fun PlanDetail(state: PlanEditorState, model: PlanEditorViewModel) {
    val stamp = state.stamp
    val plan = state.detail
    val enabled = state.isAvailable && !state.isMaintaining && !state.isBusy && plan != null
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PlanFormHeading(plan?.title?.ifBlank { "（历史空标题）" } ?: "读取计划", "计划详情 · 同一条记录，随生活变化") }
            planFeedback(state, model)
            if (plan != null) {
                item { Text(plan.description ?: "无备注") }
                item { Text("${modeLabel(plan.timeMode)} · ${if (plan.isCompleted) "已完成" else "未完成"} · ${priorityLabel(plan.priority)}") }
                item { Text("时区：${plan.timeZone}\n截止时刻：${instantText(plan.dueAt, plan.timeZone).ifEmpty { "无" }}\n截止日期：${plan.dueDate ?: "无"}") }
                item { Text("区间：${instantText(plan.startAt, plan.timeZone).ifEmpty { "无" }} — ${instantText(plan.endAt, plan.timeZone).ifEmpty { "无" }}\n全天：${plan.allDayStartDate ?: "无"} — ${plan.allDayEndDate ?: "无"}（含结束日）") }
                item { Text("重复：${repeatLabel(plan.repeatRule)} · 间隔 ${plan.repeatInterval}\n星期：${plan.repeatDaysOfWeek.joinToString()}\n结束日期：${plan.repeatEndDate ?: "无"}\n未启用模式的参数仅保留，不产生发生或提醒。") }
                item { Text("地点：${plan.location ?: "无"}\n参与人：${plan.participants ?: "无"}\n标签：${plan.tagNames.joinToString(" · ").ifEmpty { "无" }}") }
                item { Text("单项提醒：${if (plan.reminderEnabled) "开启" else "关闭"} · 提前 ${plan.reminderMinutes?.toString() ?: "未配置"} 分钟\n无日期／已完成计划不投递提醒。") }
                item { Text("记录 #${plan.id} · 父计划 ${plan.parentId ?: "无"} · 排序 ${plan.sortOrder}\n创建：${plan.createdAt}\n更新：${plan.updatedAt}\n完成：${plan.completedAt ?: "未完成"}") }
                if (plan.isRepeating) item { Text("勾选完成将结束整个重复系列，不是单次打卡。") }
                item { Button({ model.beginEdit(stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("编辑计划") } }
                item { OutlinedButton({ model.setCompleted(!plan.isCompleted, stamp) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (plan.isCompleted) "撤销完成" else "勾选完成") } }
                item { TextButton({ model.requestDelete(stamp) }, enabled = enabled) { Text("删除计划", color = MaterialTheme.colorScheme.error) } }
            }
            item { OutlinedButton({ model.requestClose(stamp) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("返回计划") } }
        }
    }
    PlanEditorDialogs(state, model)
}

@Composable
internal fun PlanFormHeading(title: String, subtitle: String) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle) }
            PlannerIllustration(Modifier.size(56.dp))
        }
    }
}
