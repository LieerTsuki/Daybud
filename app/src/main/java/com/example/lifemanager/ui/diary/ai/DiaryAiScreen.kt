package com.example.lifemanager.ui.diary.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.diary.ai.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DiaryAiScreen(viewModel: DiaryAiViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenDiary: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Pair<String, Long>?>(null) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("AI回顾", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onOpenSettings) { Text("AI服务") }
        }
        Text("只发送所选日期内正式日记的文字和显式心情，不发送照片、视频或草稿。使用你配置的服务和额度。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.preset(AiPeriodKind.WEEK) }, enabled = !state.busy) { Text("上周") }
            OutlinedButton(onClick = { viewModel.preset(AiPeriodKind.MONTH) }, enabled = !state.busy) { Text("上月") }
        }
        OutlinedTextField(state.from, { viewModel.range(it, state.through) }, label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.through, { viewModel.range(state.from, it) }, label = { Text("结束日期 YYYY-MM-DD") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.prepare() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("检查范围") }
        state.preview?.let { preview ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.snapshot.from} 至 ${preview.snapshot.through}")
                Text("共${preview.snapshot.sources.size}篇；纳入${preview.plan.includedCount}篇，未纳入${preview.plan.excludedCount}篇（无可总结文字）")
                Text("服务：${preview.serviceOrigin}", style = MaterialTheme.typography.bodySmall)
                if (preview.plan.limitedEvidence && preview.plan.includedCount > 0) Text("记录少于3篇，材料有限，回顾不能代表完整经历。", color = MaterialTheme.colorScheme.error)
                if (preview.plan.includedCount > 0) Button(onClick = { viewModel.generate(preview) }, enabled = !state.busy) { Text("确认范围并生成") }
            } }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancel) { Text("取消；已发送的请求可能仍产生费用") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.result?.let { review ->
            Text("本次回顾", style = MaterialTheme.typography.titleLarge)
            ReviewCard(review, onOpenDiary)
            Button(onClick = { viewModel.save(review) }, enabled = !state.busy) { Text("保存回顾") }
        }
        Text("已保存回顾", style = MaterialTheme.typography.titleLarge)
        if (state.reviews.isEmpty()) Text("还没有保存的回顾")
        state.reviews.forEach { review ->
            ReviewCard(review, onOpenDiary)
            state.generation?.let { generation -> TextButton(onClick = { deleting = review.id to generation }, enabled = !state.busy) { Text("删除这份回顾") } }
        }
        Text("模型生成内容仍需结合来源核对。删除任一来源日记，会同时删除引用它的整份已保存回顾；可用剩余日记重新生成。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    deleting?.let { (id, generation) -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除回顾？") }, text = { Text("删除这份已保存回顾，日记仍会保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(id, generation) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ReviewCard(review: AiDiaryReview, onOpenDiary: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${review.from} 至 ${review.through}", style = MaterialTheme.typography.titleMedium)
        Text("生成于 ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(review.createdAt)}", style = MaterialTheme.typography.bodySmall)
        if (review.needsUpdate) Text("来源已变化，需要更新", color = MaterialTheme.colorScheme.error)
        if (review.provider.isNotBlank()) Text("${review.provider} · ${review.model}", style = MaterialTheme.typography.bodySmall)
        review.sections.sortedBy { it.kind.ordinal }.forEach { section ->
            var expanded by remember(review.id, section.kind) { mutableStateOf(true) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(section.kind.title + if (expanded) "  收起" else "  展开") }
            if (expanded) {
                Text(section.text)
                section.sourceIds.sorted().forEach { id -> TextButton(onClick = { onOpenDiary(id) }, contentPadding = PaddingValues(0.dp)) { Text("查看来源日记 #$id") } }
            }
        }
    } }
}
