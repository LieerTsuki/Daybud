package com.example.lifemanager.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun LazyListScope.planFeedback(state: PlanEditorState, model: PlanEditorViewModel) {
    val stamp = state.stamp
    if (state.isLoading) item { Text("正在读取计划与提醒设置…") }
    if (state.isMaintaining) item { Text("正在维护数据，草稿保留，暂不能修改") }
    state.message?.takeIf { it == "数据已替换，旧草稿已失效" }?.let { message -> item { Text(message) } }
    state.error?.let { error -> item { Column {
        Text(error, color = MaterialTheme.colorScheme.error)
        if (!state.isAvailable) TextButton(model::retryRead, enabled = !state.isMaintaining) { Text("重新读取") }
    } } }
    if (state.remindersEnabled == false) item { Text("计划提醒总开关已关闭，单项配置仍会保存但不会投递") }
    state.reminderFailures.forEach { (id, _) -> item(key = "editor-reminder-$id") { Column {
        Text("计划 #$id 已写入，提醒尚未确认；勿重复提交", color = MaterialTheme.colorScheme.error)
        TextButton({ model.retryReminder(stamp, id) }, enabled = state.isAvailable && !state.isBusy && !state.isMaintaining) { Text("仅重试 #$id 的提醒") }
    } } }
}

@Composable
internal fun PlanEditorDialogs(state: PlanEditorState, model: PlanEditorViewModel) {
    val stamp = state.stamp
    val enabled = state.isAvailable && !state.isBusy && !state.isMaintaining
    if (state.confirmClose) AlertDialog(onDismissRequest = { model.cancelClose(stamp) }, title = { Text("保留未保存的草稿？") },
        text = { DialogBody("放弃后不会写入数据库；数据维护期间也可以继续保留草稿。") },
        confirmButton = { TextButton({ model.confirmClose(stamp) }, enabled = !state.isBusy) { Text("放弃草稿") } },
        dismissButton = { TextButton({ model.cancelClose(stamp) }) { Text("继续编辑") } })
    else if (state.pendingMode != null) AlertDialog(onDismissRequest = { model.cancelMode(stamp) }, title = { Text("切换为${modeLabel(state.pendingMode)}？") },
        text = {
            val draft=state.draft
            val time=draft?.let { when(it.mode) {
                com.example.lifemanager.domain.plan.PlanTimeMode.NONE -> "未设日期"
                com.example.lifemanager.domain.plan.PlanTimeMode.DEADLINE -> if(it.dateOnly)it.dueDate else planInputDateTime(it.dueAt)
                com.example.lifemanager.domain.plan.PlanTimeMode.TIMED -> "${planInputDateTime(it.startAt)} — ${planInputDateTime(it.endAt)}"
                com.example.lifemanager.domain.plan.PlanTimeMode.ALL_DAY -> "${it.allDayStart} — ${it.allDayEnd}"
            } }.orEmpty()
            DialogBody("当前安排：$time\n切换后按新类型安排，原有设置会保留。请核对日期和重复设置后保存。")
        },
        confirmButton = { TextButton({ model.confirmMode(stamp) }, enabled = enabled) { Text("确认切换") } },
        dismissButton = { TextButton({ model.cancelMode(stamp) }) { Text("保留原类型") } })
    else if (state.conflicts != null) AlertDialog(onDismissRequest = { model.cancelConflict(stamp) }, title = { Text("检测到时间冲突") },
        text = { DialogBody("${state.conflicts.conflicts.joinToString("\n") { "#${it.id} ${it.title}" }}\n核查窗口：${state.conflicts.window.startInclusive} — ${state.conflicts.window.endExclusive}（不含结束）\n仅核查首次区间及至少 30 天窗口，不代表无限重复无冲突。确认保存时重新核对。") },
        confirmButton = { TextButton({ model.save(stamp, true) }, enabled = enabled) { Text("仍然保存") } },
        dismissButton = { TextButton({ model.cancelConflict(stamp) }, enabled = !state.isBusy) { Text("继续修改") } })
    else if (state.confirmDelete) AlertDialog(onDismissRequest = { model.cancelDelete(stamp) }, title = { Text("删除这条计划？") },
        text = { DialogBody("删除整个计划及其提醒与例外；子计划仅解除父关联，其内容保留。删除无法撤销。") },
        confirmButton = { TextButton({ model.confirmDelete(stamp) }, enabled = enabled) { Text("确认删除") } },
        dismissButton = { TextButton({ model.cancelDelete(stamp) }) { Text("取消删除") } })
}

@Composable
private fun DialogBody(message: String) {
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(message) }
}
