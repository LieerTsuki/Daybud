package com.example.lifemanager.ui.plan

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.plan.PlanCalendarMode
import com.example.lifemanager.domain.plan.PlanOccurrenceTime
import com.example.lifemanager.domain.plan.PlanListRow
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.common.PlannerIllustration
import com.example.lifemanager.ui.settings.displayDate
import java.time.LocalDate
import java.time.YearMonth

@Composable fun PlanCalendarScreen(state:PlanCalendarState,onDate:(LocalDate)->Unit,onMode:(PlanCalendarMode)->Unit,
    onCompleted:(Boolean)->Unit,onMove:(Int)->Unit,onToday:()->Unit,onRetry:()->Unit,onOpen:((Long)->Unit)?,extraContent:LazyListScope.()->Unit={}) {
    val enabled=state.isAvailable && !state.isLoading && !state.isMaintaining
    Surface(color=MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { OverviewHeader("日历手账","给有日期的计划留一个位置") }
            extraContent()
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton({onMove(-1)},enabled=enabled) { Icon(Icons.Outlined.ChevronLeft,"上个日历窗口") }
                    Text(when(state.mode) {
                        PlanCalendarMode.MONTH -> "${state.selectedDate.year}年${state.selectedDate.monthValue}月"
                        PlanCalendarMode.WEEK -> "${displayDate(state.days.keys.firstOrNull() ?: state.selectedDate)} 起的一周"
                        PlanCalendarMode.DAY -> displayDate(state.selectedDate)
                    },Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    IconButton({onMove(1)},enabled=enabled) { Icon(Icons.Outlined.ChevronRight,"下个日历窗口") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PlanCalendarMode.entries.forEach { mode -> FilterChip(state.mode==mode,{onMode(mode)},enabled=enabled,
                        label={Text(when(mode) { PlanCalendarMode.MONTH -> "月视图";PlanCalendarMode.WEEK -> "周视图";PlanCalendarMode.DAY -> "日视图" })}) }
                }
                TextButton(onToday,enabled=enabled) { Text("回到今天") }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(state.includeCompleted,onCompleted,enabled=enabled,modifier=Modifier.semantics { contentDescription="显示已完成计划" })
                    Text("显示已完成",Modifier.weight(1f))
                }
                Text("点计划可查看详情、完成或编辑。重复完成作用于整个系列。",style=MaterialTheme.typography.bodySmall)
            }
            if(state.isMaintaining) item { Text("正在维护数据，暂不能切换或打开计划") }
            state.message?.let { message -> item { Text(message) } }
            state.readError?.let { error -> item { Column {
                Text(error,color=MaterialTheme.colorScheme.error)
                TextButton(onRetry,enabled=!state.isMaintaining) { Text("重新读取日历") }
            } } }
            if(state.isLoading) item { CircularProgressIndicator(Modifier.semantics { contentDescription="正在读取日历" }) }
            if(state.isAvailable && !state.isLoading) {
                if(state.mode==PlanCalendarMode.MONTH) {
                    val month=YearMonth.from(state.selectedDate)
                    item { Row(Modifier.fillMaxWidth()) { listOf("一","二","三","四","五","六","日").forEach { Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall) } } }
                    val cells=List<LocalDate?>(month.atDay(1).dayOfWeek.value-1) { null }+(1..month.lengthOfMonth()).map(month::atDay)
                    cells.chunked(7).forEach { week -> item {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            (week+List(7-week.size) { null }).forEach { date ->
                                if(date==null) Spacer(Modifier.weight(1f)) else {
                                    val rows=state.days[date].orEmpty()
                                    val count=rows.size
                                    Surface(shape=RoundedCornerShape(12.dp),color=if(date==state.selectedDate) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                        modifier=Modifier.weight(1f).fillMaxHeight().heightIn(min=112.dp)
                                            .testTag("plan-calendar-date-$date").clickable(enabled=enabled,onClickLabel="选择日期") { onDate(date) }
                                            .semantics { contentDescription="$date，$count 条日历记录";selected=date==state.selectedDate }) {
                                        Column(Modifier.padding(horizontal=3.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,
                                            verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                            Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.labelSmall)
                                            rows.take(2).forEach { row ->
                                                Text(row.plan.title.ifBlank { "（历史空标题）" },Modifier.fillMaxWidth(),
                                                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface,
                                                    maxLines=2,overflow=TextOverflow.Ellipsis)
                                            }
                                            if(count>2) Text("还有 ${count-2} 项",Modifier.fillMaxWidth(),
                                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    } }
                }
                val groups=if(state.mode==PlanCalendarMode.MONTH) mapOf(state.selectedDate to state.days[state.selectedDate].orEmpty()) else state.days
                groups.forEach { (date,rows) ->
                    item(key="date-$date") { Text(displayDate(date),style=MaterialTheme.typography.titleMedium) }
                    if(rows.isEmpty()) item(key="empty-$date") { Text("此日期暂无日程") }
                    items(rows,key={"$date-${it.plan.id}-${it.occurrence!!.occurrenceDate}"}) { row ->
                        CalendarEntry(row,state,enabled,onOpen)
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarEntry(row:PlanListRow,state:PlanCalendarState,enabled:Boolean,onOpen:((Long)->Unit)?) {
    Card(shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().testTag("plan-calendar-entry-${row.plan.id}")
            .then(if(onOpen!=null) Modifier.clickable(enabled=enabled,onClickLabel="打开计划详情") { onOpen(row.plan.id) } else Modifier)
            .padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(row.plan.title.ifBlank { "（历史空标题）" },style=MaterialTheme.typography.titleMedium)
            Text(timeLabel(row,state.displayZone),style=MaterialTheme.typography.bodyMedium)
            if(row.occurrence!!.time is PlanOccurrenceTime.Completion) Text("整个系列已完成，仅显示这一条完成标记")
            else if(row.plan.isCompleted) Text("已完成")
            if(row.plan.isRepeating && !row.plan.isCompleted) Text("重复系列 · 完成将结束整个系列",style=MaterialTheme.typography.bodySmall)
            if(row.plan.tagNames.isNotEmpty()) Text("标签：${row.plan.tagNames.joinToString(" · ")}",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun PlanCalendarScreen(model:PlanCalendarViewModel,onOpen:(Long,DataGeneration?)->Unit,canNavigate:Boolean=true,extraContent:LazyListScope.()->Unit={}) {
    val state=model.uiState.collectAsStateWithLifecycle().value
    val rendered=state.generation
    RefreshPlanOverview(model,model::refresh)
    PlanCalendarScreen(state,{model.selectDate(it,rendered)},{model.selectMode(it,rendered)},{model.showCompleted(it,rendered)},
        {model.movePeriod(it,rendered)},{model.today(rendered)},model::retryRead,
        if(canNavigate) { id -> if(model.canNavigate(rendered)) onOpen(id,rendered) } else null,extraContent)
}

@Composable internal fun OverviewHeader(title:String,subtitle:String) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.headlineMedium);Text(subtitle,style=MaterialTheme.typography.bodyMedium) }
        PlannerIllustration(Modifier.size(64.dp))
    }
}
