package com.example.lifemanager.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.backup.BackupSourceFormat
import java.time.LocalDate

/** Picker tickets survive recreation, but never reconstruct an operation or carry backup bytes. */
@Composable
fun V1BackupSettingsSection(model: V1BackupSettingsViewModel, enabled: Boolean, onStart: (BackupAction) -> Unit) {
    val state = model.uiState.collectAsStateWithLifecycle().value
    var sourceRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var outputRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLaunched by rememberSaveable { mutableStateOf<String?>(null) }
    var outputLaunched by rememberSaveable { mutableStateOf<String?>(null) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        sourceRequest?.let { id -> sourceRequest = null; model.sourceSelected(id, uri?.toString()) }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        outputRequest?.let { id -> outputRequest = null; model.outputSelected(id, uri?.toString()) }
    }
    LaunchedEffect(state.operationId, state.step) {
        val id = state.operationId ?: return@LaunchedEffect
        if (model.uiState.value.operationId != id || model.uiState.value.step != state.step) return@LaunchedEffect
        when (state.step) {
            BackupStep.PICK_SOURCE -> if (sourceLaunched != id) {
                // An older native picker still owns its eventual result. Never relabel it as new.
                if (sourceRequest != null) { model.pickerFailed(id); return@LaunchedEffect }
                sourceRequest = id
                sourceLaunched = id
                try { sourcePicker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")) }
                catch (_: Exception) { sourceRequest = null; model.pickerFailed(id) }
            }
            BackupStep.PICK_OUTPUT -> if (outputLaunched != id) {
                if (outputRequest != null) { model.pickerFailed(id); return@LaunchedEffect }
                outputRequest = id
                outputLaunched = id
                val prefix = if (state.action == BackupAction.EXPORT) "daybud-v4-backup" else "daybud-before-change"
                try { outputPicker.launch("$prefix-${LocalDate.now()}.zip") }
                catch (_: Exception) { outputRequest = null; model.pickerFailed(id) }
            }
            else -> Unit
        }
    }
    V1BackupSettingsControls(state, enabled, onStart, { state.operationId?.let(model::cancel) }, model::acknowledgeUnknown)
    V1BackupSettingsDialogs(state, model::answerFirst, model::answerFinal)
}

@Composable
fun V1BackupSettingsControls(state: V1BackupUiState, enabled: Boolean, onStart: (BackupAction) -> Unit,
    onCancel: () -> Unit, onInspect: () -> Unit) {
    val actionable = enabled && !state.busy && state.outcome != V1BackupOutcome.UNKNOWN
    SettingsGroup("数据") {
        SettingsAction("备份数据", { onStart(BackupAction.EXPORT) }, actionable)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsAction("恢复备份", { onStart(BackupAction.RESTORE) }, actionable)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsAction("清空数据", { onStart(BackupAction.CLEAR) }, actionable, MaterialTheme.colorScheme.error)
    }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when {
            state.checkingOutcome -> "正在重新读取当前数据，不会重试原操作"
            state.step == BackupStep.PICK_SOURCE -> "请选择恢复来源文件"
            state.step == BackupStep.READING_SOURCE -> "正在读取并严格校验备份"
            state.step == BackupStep.PICK_OUTPUT -> if (state.action == BackupAction.EXPORT) "请选择导出保存位置" else "请选择独立的保护备份位置"
            state.step == BackupStep.WRITING_BACKUP -> "正在写入、关闭并重读验证备份"
            state.step == BackupStep.FINAL_CONFIRM -> "保护步骤已完成，等待最终确认"
            state.step == BackupStep.COMMITTING -> "正在提交数据，请稍候"
            state.step == BackupStep.RECONCILING -> "正在校准本地提醒"
            else -> "正在准备，请按提示操作"
        })
        if (state.canCancel) TextButton(onCancel) { Text("取消本次操作") }
    }
    state.message?.let { Text(it, color = if (state.step == BackupStep.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    if (state.outcome == V1BackupOutcome.UNKNOWN) {
        TextButton(onInspect, enabled = !state.busy) { Text("重新读取当前数据，不重试操作") }
    }
}

@Composable
fun V1BackupSettingsDialogs(state: V1BackupUiState, answerFirst: (String, Boolean?) -> Unit, answerFinal: (String, Boolean) -> Unit) {
    val id = state.operationId ?: return
    when (state.step) {
        BackupStep.EXPORT_NOTICE -> AlertDialog(onDismissRequest = { answerFirst(id, false) },
            title = { Text("导出明文备份") }, text = { Text("包含计划、打卡、订阅、日记、草稿、照片、视频、历史回顾及设置。V4归档未加密，任何获得文件的人都可以读取；请保存到可信位置，勿安装旧版降级。") },
            confirmButton = { TextButton({ answerFirst(id, true) }) { Text("选择保存位置") } },
            dismissButton = { TextButton({ answerFirst(id, false) }) { Text("取消") } })
        BackupStep.RESTORE_PREVIEW -> AlertDialog(onDismissRequest = { answerFirst(id, false) },
            title = { Text("恢复备份预览") }, text = {
                BackupDialogColumn {
                    Text(when(state.sourceFormat) {
                        BackupSourceFormat.V1 -> "已严格验证旧 V1 文件并转换为统一计划。"
                        BackupSourceFormat.V2 -> "已严格验证旧 V2 文件并初始化林场种子。"
                        BackupSourceFormat.V4 -> "已严格验证 V4 清单及全部媒体字节。"
                        else -> "已严格验证 V3 文件及林场记录。"
                    })
                    state.legacyTodoTimeZone?.let { Text("旧待办未保存时区，本次按 $it 转换；这是恢复设备时区，不是原设备历史时区。") }
                    Text("导出时间：${state.sourceDate.orEmpty()}")
                    v1BackupTableLabels.forEach { (table, label) -> Text("$label：${state.rowCounts[table] ?: 0}") }
                    Text(if(state.sourceFormat==BackupSourceFormat.V4) "将替换全部业务数据、日记、草稿、媒体、已保存回顾及设置，不合并。" else "将替换旧格式中包含的业务数据及设置，现有日记、日记草稿、媒体和回顾会保留。")
                    Text("接下来先另存并验证当前数据的 V4 保护备份，再独立最终确认。取消会保留当前数据。")
                }
            }, confirmButton = { TextButton({ answerFirst(id, true) }) { Text("同意并备份当前数据") } },
            dismissButton = { TextButton({ answerFirst(id, false) }) { Text("取消") } })
        BackupStep.CLEAR_CHOICE -> AlertDialog(onDismissRequest = { answerFirst(id, null) },
            title = { Text("清空前先保留备份") }, text = { Text("将删除计划、打卡、订阅、日记、日记草稿、媒体、历史回顾及关联记录，保留设置。建议先备份，下一步还需独立最终确认。") },
            confirmButton = { Column {
                TextButton({ answerFirst(id, true) }) { Text("先备份当前数据") }
                TextButton({ answerFirst(id, false) }) { Text("跳过备份，继续确认") }
            } }, dismissButton = { TextButton({ answerFirst(id, null) }) { Text("取消") } })
        BackupStep.FINAL_CONFIRM -> AlertDialog(onDismissRequest = { answerFinal(id, false) },
            title = { Text(if (state.action == BackupAction.RESTORE) "最终确认：替换全部数据" else "最终确认：清空业务数据") },
            text = { BackupDialogColumn {
                Text(if (state.protectionVerified) "当前数据的独立 V4 保护备份已重读验证。" else "你已选择跳过保护备份；删除后只能使用此前备份恢复。")
                Text(if (state.action == BackupAction.RESTORE) "将用 ${state.sourceDate.orEmpty()} 的备份替换全部业务数据及设置，共 ${state.rowCounts.values.sum()} 条业务及关联记录。"
                    else "将删除全部业务及关联记录，保留用户设置。")
                Text(if(state.action==BackupAction.RESTORE && state.sourceFormat!=BackupSourceFormat.V4) "旧格式恢复将保留现有日记和日记草稿；其他旧编辑会话失效。" else "确认后日记和草稿按以上范围替换或删除，旧编辑会话失效。")
                Text("取消会保留当前数据和草稿，请核对后再确认。")
            } }, confirmButton = { TextButton({ answerFinal(id, true) }) {
                Text(if (state.action == BackupAction.RESTORE) "确认替换全部数据" else "确认清空业务数据", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton({ answerFinal(id, false) }) { Text("取消，保留原数据") } })
        else -> Unit
    }
}

@Composable
private fun BackupDialogColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

private val v1BackupTableLabels = listOf("plans" to "计划", "tags" to "标签", "plan_tag_cross_ref" to "计划标签关联",
    "plan_exceptions" to "计划例外", "plan_legacy_refs" to "旧来源映射", "habits" to "习惯", "habit_records" to "打卡记录", "habit_trees" to "林场树木", "habit_waterings" to "浇水记录",
    "subscriptions" to "订阅", "subscription_payments" to "扣费记录", "subscription_reminders" to "订阅提醒配置",
    "diary_entries" to "日记", "diary_drafts" to "日记草稿", "diary_media" to "照片和视频", "diary_reviews" to "历史回顾（旧版兼容）")
