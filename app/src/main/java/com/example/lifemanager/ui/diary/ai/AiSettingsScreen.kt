package com.example.lifemanager.ui.diary.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate

@Composable
fun AiSettingsScreen(viewModel: DiaryAiViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var address by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var model by remember(state.model) { mutableStateOf(state.model) }
    // Intentionally remember, never rememberSaveable: API keys must not enter a Bundle.
    var key by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AiPeriodKind?>(null) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importing
        importing = null
        if (uri != null && id != null) viewModel.importLocal(id) {
            context.contentResolver.openInputStream(uri) ?: error("无法读取模型文件")
        }
    }
    LaunchedEffect(viewModel) { viewModel.refreshSettings() }
    DisposableEffect(viewModel) { onDispose { key = "" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }; Text("AI服务", style = MaterialTheme.typography.headlineSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.mode == AiExecutionMode.LOCAL, onClick = { if (state.mode != AiExecutionMode.LOCAL) { key = ""; viewModel.selectLocal() } }, enabled = !state.busy, label = { Text("手机离线") })
            FilterChip(selected = state.mode == AiExecutionMode.CLOUD, onClick = { if (state.mode != AiExecutionMode.CLOUD) viewModel.selectCloud() }, enabled = !state.busy, label = { Text("云端服务") })
        }
        if (state.mode == AiExecutionMode.LOCAL) {
            Text("无需API Key。先下载或导入固定版本模型，校验成功后可离开电脑、断网生成。切换到手机离线会暂停云端自动回顾，失败时不会自动改用云端。")
            Text("离线功能试用：使用CPU，支持64位Android进程；会检查可用内存。速度与效果受设备影响，目前尚未完成多数品牌真机适配验证。", style = MaterialTheme.typography.bodySmall)
            Text("离线采用原文摘选：AI从每篇日记选择片段，心情直接展示已填标签，不生成自由推断。分类可能不准、重复或遗漏，摘选不代表完整总结。", style = MaterialTheme.typography.bodySmall)
            state.localDeviceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.localModels.forEach { spec ->
                val ready = spec.id in state.readyModelIds
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${spec.expectedBytes / 1_000_000} MB · ${spec.quantization} · ${spec.license}", style = MaterialTheme.typography.bodySmall)
                        Text(if (ready) "已校验就绪" else "尚未准备")
                        if (spec.id in state.localPreparationBlocked) Text("当前架构或可用存储空间不满足模型准备要求。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (spec.id == state.model) Text("当前选择", color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.selectLocal(spec.id) }, enabled = !state.busy && spec.id != state.model) { Text("选择") }
                            if (!ready) {
                                OutlinedButton(onClick = { viewModel.downloadLocal(spec.id) }, enabled = !state.busy && spec.availableForDownload && spec.id !in state.localPreparationBlocked) { Text("下载模型") }
                                TextButton(onClick = { importing = spec.id; picker.launch(arrayOf("*/*")) }, enabled = !state.busy && spec.id !in state.localPreparationBlocked) { Text("导入文件") }
                            } else TextButton(onClick = { viewModel.deleteLocal(spec.id) }, enabled = !state.busy) { Text("删除模型") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !state.busy && state.configured) { Text("检查已选离线模型（不发送日记）") }
            Text("模型不随日记备份导出；换机后需重新准备。小模型可能遗漏细节，请核对来源。长材料超出预算时会提示缩小范围。", style = MaterialTheme.typography.bodySmall)
        } else {
        Text("支持HTTPS Chat Completions兼容接口。填写接口基础地址（如 https://服务地址/v1）或完整 /chat/completions 地址、模型和自己的API Key。")
        OutlinedTextField(address, { address = it }, label = { Text("HTTPS服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
        OutlinedTextField(key, { key = it }, label = { Text(if (state.configured) "新API Key（保存时重新填写）" else "API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
        Text("Key通过Android Keystore加密并保存在不参与备份的位置，不会随手动备份迁移；换机需重新填写。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { viewModel.saveConfiguration(address, model, key) { key = "" } }, enabled = !state.busy && key.isNotBlank()) { Text("检查并保存配置") }
        OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !state.busy && state.configured) { Text("测试已保存配置的连接（不发送日记）") }
        Text("自动回顾", style = MaterialTheme.typography.titleLarge)
        Text("默认关闭。开启后，后续周期会自动发送文字到已配置服务，可能产生费用。设备当地09:00后由系统择机执行，不保证精确时间。离线后只补最近两个完整周期，每次最多一个；结果未知的请求不会自动重发。", style = MaterialTheme.typography.bodySmall)
        AiPeriodKind.entries.forEach { kind ->
            val consent = state.consents.firstOrNull { it.kind == kind }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(if (kind == AiPeriodKind.WEEK) "自动周总结" else "自动月总结")
                    if (consent?.enabled == true) Text("授权起点：${consent.enabledOn}所在${if (kind == AiPeriodKind.WEEK) "周" else "月"}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = consent?.enabled == true, enabled = state.configured && !state.busy, onCheckedChange = { enabled -> if (enabled) confirm = kind else viewModel.automatic(kind, false) })
            }
        }
        if (state.jobs.any { it.state == AiJobState.SENT_UNKNOWN }) Text("有自动回顾请求已发送但结果未知，已停止重发。可检查服务后手动生成。", color = MaterialTheme.colorScheme.error)
        if (state.jobs.any { it.state == AiJobState.PAUSED }) Text("有自动回顾未完成，请检查配置并按需手动生成。")
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = viewModel::cancel) { Text("取消") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp))
    }
    confirm?.let { kind -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("开启自动${if (kind == AiPeriodKind.WEEK) "周" else "月"}总结？") },
        text = { Text("从${LocalDate.now()}所在${if (kind == AiPeriodKind.WEEK) "周" else "月"}开始，完整周期结束后将自动发送该范围的正式日记文字和显式心情到${state.serviceOrigin}。此前历史周期不会自动补做。更换配置会暂停，需重新开启。") },
        confirmButton = { TextButton(onClick = { confirm = null; viewModel.automatic(kind, true) }) { Text("同意并开启") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
