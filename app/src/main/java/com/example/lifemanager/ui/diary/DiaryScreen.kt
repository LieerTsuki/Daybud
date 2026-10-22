package com.example.lifemanager.ui.diary

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.lifemanager.domain.diary.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** The Activity retains this owner. Only presentation state lives in the composition. */
@Composable
fun DiaryScreen(viewModel:DiaryViewModel,canNavigate:Boolean=true) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rendered=state
    val stamp=rendered.stamp
    val enabled=canNavigate && !rendered.maintaining && !rendered.busy && !rendered.loading
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(viewModel,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) { viewModel.refreshToday();delay(60_000) }
        }
    }
    DisposableEffect(viewModel,owner) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP)viewModel.flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);viewModel.flush() }
    }
    BackHandler(rendered.draft!=null || rendered.detail!=null) { viewModel.leave(stamp) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(DiaryRules.MAX_ATTACHMENTS)) { uris->viewModel.pickerResult(uris.map { it.toString() }) }
    var viewing by remember { mutableStateOf<DiaryMedia?>(null) }
    LaunchedEffect(stamp.session,stamp.generation) { viewing=null }
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if(rendered.maintaining)Text("正在维护数据，暂不能修改",Modifier.padding(horizontal=16.dp,vertical=8.dp))
            rendered.error?.let { error->
                OutlinedCard(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.error)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error,color=MaterialTheme.colorScheme.error)
                        Row {
                            if(rendered.saveStatus==DiarySaveStatus.FAILED)TextButton(onClick={ viewModel.retrySave(stamp) },enabled=enabled) { Text("重试保存") }
                            if(rendered.readFailed)TextButton(onClick=viewModel::retryRead) { Text("重新读取") }
                            TextButton(onClick={ viewModel.dismissError(stamp) }) { Text("知道了") }
                        }
                    }
                }
            }
            when {
                rendered.draft!=null -> DiaryEditor(rendered,enabled,
                    onBack={ viewModel.leave(stamp) },onTitle={ viewModel.changeTitle(it,stamp) },onBody={ viewModel.changeBody(it,stamp) },
                    onDate={ viewModel.changeDate(it,stamp) },onMood={ viewModel.changeMood(it,stamp) },onComplete={ viewModel.complete(stamp) },
                    onDiscard={ viewModel.askDiscard(stamp) },onPick={ if(viewModel.beginPicker(stamp)!=null)picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onRemove={ viewModel.removeMedia(it,stamp) },file=viewModel::mediaFile,onView={ viewing=it })
                rendered.detail!=null -> DiaryDetail(rendered.detail,enabled,{ viewModel.leave(stamp) },{ viewModel.editEntry(stamp) },{ viewModel.askDelete(stamp) },viewModel::mediaFile) { viewing=it }
                else -> DiaryHome(rendered,enabled,{ viewModel.newEntry(stamp) },{ viewModel.openEntry(it,stamp) },
                    { viewModel.toggleCalendar(it,stamp) },{ viewModel.changeMonth(it,stamp) },{ viewModel.selectDate(it,stamp) },
                    { viewModel.resumeDraft(it,stamp) },{ viewModel.anotherMemory(stamp) },viewModel::mediaFile)
            }
        }
    }
    if(rendered.confirmDelete)AlertDialog(onDismissRequest={ viewModel.cancelConfirmation(stamp) },title={ Text("删除这篇日记？") },
        text={ Text("本条日记及其附件将删除。手机图库原文件不受影响。") },
        confirmButton={ TextButton(onClick={ viewModel.confirmDelete(stamp) },enabled=enabled) { Text("删除") } },
        dismissButton={ TextButton(onClick={ viewModel.cancelConfirmation(stamp) }) { Text("取消") } })
    if(rendered.confirmDiscard)AlertDialog(onDismissRequest={ viewModel.cancelConfirmation(stamp) },title={ Text("丢弃这份草稿？") },
        text={ Text("草稿中的修改和新增附件将丢弃，已保存的日记保持原样。") },
        confirmButton={ TextButton(onClick={ viewModel.confirmDiscard(stamp) },enabled=enabled) { Text("丢弃草稿") } },
        dismissButton={ TextButton(onClick={ viewModel.cancelConfirmation(stamp) }) { Text("继续写") } })
    viewing?.let { media->DiaryMediaViewer(media,viewModel.mediaFile(media)) { viewing=null } }
}

@Composable
private fun DiaryHome(state:DiaryUiState,enabled:Boolean,onNew:()->Unit,onOpen:(Long)->Unit,onCalendar:(Boolean)->Unit,
    onMonth:(Long)->Unit,onDate:(LocalDate)->Unit,onDraft:(String)->Unit,onMemory:()->Unit,file:(DiaryMedia)->File?) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("日记手账",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
            }
            Text("把平凡日子，写成自己的故事",style=MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                DiaryStat("连续记录","${state.statistics.streakDays}天",Modifier.weight(1f))
                DiaryStat("本月记录","${state.statistics.monthEntries}篇 · ${state.statistics.monthDays}天",Modifier.weight(1f))
            }
        }
        state.memory?.let { memory->item("memory") {
            OutlinedCard(border=BorderStroke(1.dp,MaterialTheme.colorScheme.secondary),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("那年今日",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        if(state.memories.size>1)TextButton(onClick=onMemory,enabled=enabled) { Text("换一篇") }
                    }
                    Text(memory.title,Modifier.clickable(enabled=enabled) { onOpen(memory.id) },style=MaterialTheme.typography.titleMedium)
                    Text(memory.date.toString(),style=MaterialTheme.typography.labelMedium)
                    Text(memory.body,maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick={ onOpen(memory.id) },enabled=enabled) { Text("回看这一天") }
                }
            }
        } }
        if(state.memories.isEmpty())item("memory-empty") {
            Text("那年今日 · 还没有往年的记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(state.drafts.isNotEmpty())item("drafts") {
            OutlinedCard {
                Column(Modifier.padding(12.dp)) {
                    Text("草稿箱 · ${state.drafts.size}",style=MaterialTheme.typography.titleMedium)
                    state.drafts.sortedByDescending { it.updatedAt }.forEach { draft->
                        TextButton(onClick={ onDraft(draft.id) },enabled=enabled) {
                            Text("${draft.date}  ${draft.title.ifBlank { draft.body.take(22).ifBlank { "未完成的日记" } }}",maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(selected=!state.calendar,onClick={ onCalendar(false) },label={ Text("列表") },enabled=enabled)
                FilterChip(selected=state.calendar,onClick={ onCalendar(true) },label={ Text("日历") },enabled=enabled)
                Spacer(Modifier.weight(1f))
                Button(onClick=onNew,enabled=enabled) { Text("写日记") }
            }
        }
        if(state.calendar)item("calendar") { DiaryCalendar(state,enabled,onMonth,onDate) }
        if(state.loading)item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(!state.loading && state.visibleEntries.isEmpty())item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(state.calendar && state.selectedDate!=null)"这一天还没有日记"else "今天，有什么想记下的？",style=MaterialTheme.typography.titleMedium)
                    Text("写几句话，或留下一张照片。")
                }
            }
        }
        items(state.visibleEntries,key={ it.id }) { entry->DiaryEntryCard(entry,enabled,{ onOpen(entry.id) },file) }
    }
}

@Composable
private fun DiaryStat(label:String,value:String,modifier:Modifier) {
    OutlinedCard(modifier,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelMedium);Text(value,style=MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun DiaryCalendar(state:DiaryUiState,enabled:Boolean,onMonth:(Long)->Unit,onDate:(LocalDate)->Unit) {
    OutlinedCard {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={ onMonth(-1) },enabled=enabled) { Text("上月") }
                Text("${state.month.year}-${state.month.monthValue.toString().padStart(2,'0')}",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={ onMonth(1) },enabled=enabled) { Text("下月") }
            }
            Row { listOf("日","一","二","三","四","五","六").forEach { Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
            val start=state.month.atDay(1).dayOfWeek.value%7
            val count=state.month.lengthOfMonth()
            val counts=state.entries.groupingBy { it.date }.eachCount()
            repeat((start+count+6)/7) { week->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column->
                        val day=week*7+column-start+1
                        if(day !in 1..count)Spacer(Modifier.weight(1f).height(54.dp))
                        else {
                            val date=state.month.atDay(day)
                            Surface(Modifier.weight(1f).heightIn(min=54.dp).padding(1.dp).clickable(enabled=enabled) { onDate(date) },
                                shape=RoundedCornerShape(10.dp),color=if(date==state.selectedDate)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border=if(date==state.today)BorderStroke(1.dp,MaterialTheme.colorScheme.primary)else null) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                    Text(day.toString())
                                    Text(counts[date]?.let { if(it>1)"${it}篇"else "•" } ?: "",style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            state.selectedDate?.let { Text("$it · ${counts[it] ?: 0}篇",Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun DiaryEntryCard(entry:DiaryEntry,enabled:Boolean,onOpen:()->Unit,file:(DiaryMedia)->File?) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onOpen),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(entry.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(entry.date.toString()+ (entry.mood?.let { " · ${it.label}" } ?: ""),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.body.isNotBlank())Text(entry.body,maxLines=3,overflow=TextOverflow.Ellipsis)
                else Text("${entry.media.size}个附件",style=MaterialTheme.typography.bodySmall)
            }
            entry.media.firstOrNull()?.let { DiaryMediaPreview(it,file(it),Modifier.size(80.dp)) }
        }
    }
}

@Composable
private fun DiaryEditor(state:DiaryUiState,enabled:Boolean,onBack:()->Unit,onTitle:(String)->Unit,onBody:(String)->Unit,
    onDate:(LocalDate)->Unit,onMood:(DiaryMood?)->Unit,onComplete:()->Unit,onDiscard:()->Unit,onPick:()->Unit,
    onRemove:(String)->Unit,file:(DiaryMedia)->File?,onView:(DiaryMedia)->Unit) {
    val draft=state.draft ?: return
    val context=LocalContext.current
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onBack,enabled=!state.busy) { Text("返回") }
                Text(if(draft.entryId==null)"写日记"else "编辑日记",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                Button(onClick=onComplete,enabled=enabled) { Text("完成") }
            }
            Text(when(state.saveStatus) { DiarySaveStatus.SAVING->"保存中…";DiarySaveStatus.SAVED->"草稿已保存";DiarySaveStatus.FAILED->"保存失败，输入已保留";DiarySaveStatus.UNSAVED->"等待保存…" },
                color=if(state.saveStatus==DiarySaveStatus.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
        }
        item { OutlinedTextField(value=draft.title,onValueChange=onTitle,label={ Text("标题（可不填）") },singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth()) }
        item { OutlinedButton(onClick={
            DatePickerDialog(context,{ _,year,month,day->onDate(LocalDate.of(year,month+1,day)) },draft.date.year,draft.date.monthValue-1,draft.date.dayOfMonth).apply {
                datePicker.maxDate=state.today.atTime(23,59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        },enabled=enabled) { Text("日记日期  ${draft.date}") } }
        item {
            Text("今天的心情（可不选）",style=MaterialTheme.typography.labelLarge)
            Column {
                DiaryMood.entries.chunked(3).forEach { moods->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    moods.forEach { mood->FilterChip(selected=draft.mood==mood,onClick={ onMood(if(draft.mood==mood)null else mood) },label={ Text(mood.label) },enabled=enabled) }
                } }
            }
        }
        item { OutlinedTextField(value=draft.body,onValueChange=onBody,label={ Text("记下今天的片段") },enabled=enabled,minLines=8,modifier=Modifier.fillMaxWidth()) }
        item {
            Text("照片与视频 · ${draft.media.size}/20",style=MaterialTheme.typography.titleMedium)
            Text("图片≤50MiB，视频≤500MiB，每篇合计≤1GiB",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick=onPick,enabled=enabled && draft.media.size<20) { Text("添加照片 / 视频") }
            state.importProgress?.let { Text(it);LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        items(draft.media,key={ it.id }) { media->
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                DiaryMediaPreview(media,file(media),Modifier.size(96.dp).clickable(enabled=enabled) { onView(media) })
                Text(if(media.mimeType.startsWith("video/"))"视频"else "照片",Modifier.weight(1f))
                TextButton(onClick={ onRemove(media.id) },enabled=enabled) { Text("移除") }
            }
        }
        item { TextButton(onClick=onDiscard,enabled=enabled) { Text("丢弃草稿",color=MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun DiaryDetail(entry:DiaryEntry,enabled:Boolean,onBack:()->Unit,onEdit:()->Unit,onDelete:()->Unit,file:(DiaryMedia)->File?,onView:(DiaryMedia)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onBack,enabled=enabled) { Text("返回") };Spacer(Modifier.weight(1f))
                TextButton(onClick=onEdit,enabled=enabled) { Text("编辑") };TextButton(onClick=onDelete,enabled=enabled) { Text("删除",color=MaterialTheme.colorScheme.error) }
            }
            Text(entry.title,style=MaterialTheme.typography.headlineMedium)
            Text(entry.date.toString()+(entry.mood?.let { " · ${it.label}" } ?: ""),style=MaterialTheme.typography.labelLarge)
        }
        if(entry.body.isNotBlank())item { Text(entry.body,style=MaterialTheme.typography.bodyLarge) }
        items(entry.media,key={ it.id }) { media->DiaryMediaPreview(media,file(media),Modifier.fillMaxWidth().height(240.dp).clickable { onView(media) }) }
    }
}

@Composable
private fun DiaryMediaPreview(media:DiaryMedia,file:File?,modifier:Modifier=Modifier) {
    val bitmap by produceState<Bitmap?>(null,media.id,file) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                if(file?.isFile!=true)null
                else if(media.mimeType.startsWith("image/"))decodeDiaryImage(file,512)
                else if(Build.VERSION.SDK_INT>=27) {
                    val retriever=MediaMetadataRetriever()
                    try { retriever.setDataSource(file.absolutePath);retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,512,512) } finally { retriever.release() }
                } else null
            }.getOrNull()
        }
    }
    Surface(modifier,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment=Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(),contentDescription=if(media.mimeType.startsWith("video/"))"视频缩略图"else "日记照片",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
            if(file?.isFile!=true)Text("附件不可用",Modifier.padding(8.dp),style=MaterialTheme.typography.labelSmall)
            else if(media.mimeType.startsWith("video/"))Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f)) { Text("▶ 播放",Modifier.padding(8.dp)) }
            else if(bitmap==null)Text("图片无法显示",Modifier.padding(8.dp),style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiaryMediaViewer(media:DiaryMedia,file:File?,onClose:()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column {
                TextButton(onClick=onClose,modifier=Modifier.align(Alignment.End)) { Text("关闭") }
                when {
                    file?.isFile!=true -> Text("附件不可用，日记文字仍保留",Modifier.padding(24.dp))
                    media.mimeType.startsWith("video/") -> DiaryVideo(file,Modifier.fillMaxWidth().weight(1f))
                    else -> {
                        val bitmap by produceState<Bitmap?>(null,file) { value=withContext(Dispatchers.IO) { runCatching { decodeDiaryImage(file,2048) }.getOrNull() } }
                        Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
                            bitmap?.let { Image(it.asImageBitmap(),contentDescription="日记照片大图",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit) } ?: Text("图片无法显示")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryVideo(file:File,modifier:Modifier) {
    val owner=LocalLifecycleOwner.current
    val context=LocalContext.current
    var error by remember(file) { mutableStateOf(false) }
    val player=remember(file,context) { VideoView(context).apply {
        setMediaController(MediaController(context));setVideoPath(file.absolutePath)
        setOnErrorListener { _,_,_->error=true;true }
    } }
    DisposableEffect(player,owner) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP || event==Lifecycle.Event.ON_PAUSE)player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);player.stopPlayback();player.setMediaController(null) }
    }
    if(error)Text("设备无法播放这段视频",Modifier.padding(24.dp))
    else AndroidView(factory={ player },modifier=modifier)
}
