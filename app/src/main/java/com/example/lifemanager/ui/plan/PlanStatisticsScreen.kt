package com.example.lifemanager.ui.plan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.lifemanager.ui.settings.displayDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable fun PlanStatisticsScreen(state:PlanStatisticsState,onRetry:()->Unit,extraContent:LazyListScope.()->Unit={}) {
    Surface(color=MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { OverviewHeader("成长统计","看全库事实，不为筛选改变口径") }
            extraContent()
            if(state.isMaintaining) item { Text("正在维护数据，统计暂不可操作") }
            state.readError?.let { error -> item { Column {
                Text(error,color=MaterialTheme.colorScheme.error)
                TextButton(onRetry,enabled=!state.isMaintaining) { Text("重新读取统计") }
            } } }
            if(state.isLoading) item { CircularProgressIndicator(Modifier.semantics { contentDescription="正在读取统计" }) }
            val stats=state.statistics
            if(state.isAvailable && !state.isLoading && stats!=null) {
                item { Text("统计日期：${displayDate(state.today)} · 展示时区：${state.displayZone.id}") }
                item {
                    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("全库完成率：${(stats.completionRate*100).roundToInt()}%（${stats.completedCount} / ${stats.totalCount}）",style=MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(progress={stats.completionRate.toFloat()},modifier=Modifier.fillMaxWidth().semantics { contentDescription="全库计划完成进度" })
                            if(stats.totalCount==0) Text("暂无计划")
                            Text("完成计划数 / 全部计划数。重复系列只计一条；搜索、分类与列表状态筛选不改变分母。")
                        }
                    }
                }
                item { StatisticCard("待完成：${stats.pendingCount} 项","全库当前未完成的计划，包含无日期待办。") }
                item { StatisticCard("今日完成：${stats.todayCompletedCount} 项","按完成时间在展示时区的本地日期统计，包含今天完成的长期目标。") }
                item { StatisticCard("今天日程：${state.todayScheduledCount} 项","真实发生窗口与今天相交，按计划去重，含已完成单次计划；无日期待办和系列完成标记不计入。仅日期/全天在计划时区比较日范围，因此跨时区时不等于日历日期格数量。") }
                item { StatisticCard("逾期：${stats.overdueCount} 项","仅未完成的非重复日程；日期截止/全天须结束日过去，时间区间须结束时刻过去。重复系列不因旧锚点而永久逾期。") }
                item { Text("系列完成不代表每次都执行过；本页不提供虚构的逐次执行率。可见时每分钟及恢复前台重新计算。",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
@Composable private fun StatisticCard(title:String,description:String) {
    Card(shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium);Text(description,style=MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable fun PlanStatisticsScreen(model:PlanStatisticsViewModel,extraContent:LazyListScope.()->Unit={}) {
    val state=model.uiState.collectAsStateWithLifecycle().value
    RefreshPlanOverview(model,model::refresh)
    PlanStatisticsScreen(state,model::retryRead,extraContent)
}

/** Trigger fresh admitted reads on appearance/resume and while visible; never runs in the background. */
@Composable internal fun RefreshPlanOverview(model:Any,refresh:(ZoneId)->Unit) {
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(model,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) { refresh(ZoneId.systemDefault());delay(60_000) }
        }
    }
}
