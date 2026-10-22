package com.example.lifemanager.ui.diary.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@Composable
fun AiSettingsScreen(viewModel: DiaryAiViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }; Text("AI服务", style = MaterialTheme.typography.headlineSmall)
        }
        Text("手机离线 AI", style = MaterialTheme.typography.titleLarge)
        Text("先下载或导入模型，校验就绪后可断网生成。日记和回顾只在手机上处理；下载模型时需要联网。")
        Text("短总结和具体建议仍需结合来源核对。当前为测试功能，模型效果和手机速度尚待真机验收，暂不保证多数手机的生成速度。", style = MaterialTheme.typography.bodySmall)
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
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("已用时 ${state.elapsedSeconds} 秒", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::cancel) { Text("取消") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp))
    }
}
