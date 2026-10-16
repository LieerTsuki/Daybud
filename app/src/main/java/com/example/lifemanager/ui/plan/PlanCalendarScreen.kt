package com.example.lifemanager.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lifemanager.domain.plan.PlanCalendarMode
import com.example.lifemanager.domain.plan.PlanListRow
import java.time.LocalDate
import java.time.YearMonth

/** The inline month contributes items to the board's existing vertical scroll container. */
fun LazyListScope.planMonthCalendar(
    state: PlanCalendarState,
    onDate: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onMove: (Int) -> Unit,
    onToday: () -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = state.isAvailable && !state.isLoading && !state.isMaintaining
    val month = YearMonth.from(state.selectedDate)
    item(key = "plan-month-controls") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onMove(-1) }, enabled = enabled) {
                Icon(Icons.Outlined.ChevronLeft, "上个月")
            }
            Text("${month.year}年${month.monthValue}月", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton({ onMove(1) }, enabled = enabled) {
                Icon(Icons.Outlined.ChevronRight, "下个月")
            }
            TextButton(onToday, enabled = enabled) {
                Text("回到今天", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    if (state.isMaintaining) item(key = "plan-month-maintenance") {
        Text("正在维护数据，暂不能切换或打开计划", style = MaterialTheme.typography.bodySmall)
    }
    state.message?.let { message -> item(key = "plan-month-message") { Text(message) } }
    state.readError?.let { error -> item(key = "plan-month-error") {
        Column {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onRetry, enabled = !state.isMaintaining) { Text("重新读取日历") }
        }
    } }
    if (state.isLoading) item(key = "plan-month-loading") {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "正在读取日历" })
    }
    if (state.isAvailable) {
        item(key = "plan-month-weekdays") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { name ->
                    Text(name, Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val cells = List<LocalDate?>(month.atDay(1).dayOfWeek.value % 7) { null } +
            (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(7).forEachIndexed { index, week ->
            item(key = "plan-month-$month-week-$index") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // The whole month shares a width-based size, independent of each task's title.
                    // Reserve six 3dp gaps and 4dp internal padding, aiming for four CJK characters.
                    val fontScale = LocalDensity.current.fontScale
                    val fourCharacterSize = (((maxWidth.value - 18f) / 7f - 4f) / (4f * fontScale))
                    val taskFontSize = (kotlin.math.floor(fourCharacterSize * 2f) / 2f).coerceIn(8f, 10f).sp
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (week + List(7 - week.size) { null }).forEach { date ->
                            if (date == null) Spacer(Modifier.weight(1f)) else {
                                MonthDateCell(date, state.days[date].orEmpty(), date == state.selectedDate,
                                    date == LocalDate.now(state.displayZone), enabled, onDate, onOpenDay,
                                    taskFontSize, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
        item(key = "plan-month-hint") {
            Text("双击日期查看当天全部计划", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthDateCell(date: LocalDate, rows: List<PlanListRow>, isSelected: Boolean, isToday: Boolean,
    enabled: Boolean, onDate: (LocalDate) -> Unit, onOpenDay: (LocalDate) -> Unit, taskFontSize: TextUnit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (isSelected) 1.5.dp else 0.5.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.heightIn(min = 56.dp).testTag("plan-calendar-date-$date")
            .combinedClickable(enabled = enabled, onClickLabel = "选择日期", onClick = { onDate(date) },
                onDoubleClick = { onOpenDay(date) }, onLongClickLabel = "查看当天全部计划", onLongClick = { onOpenDay(date) })
            .semantics {
                contentDescription = "$date，${rows.size} 条日历记录"
                selected = isSelected
                customActions = listOf(CustomAccessibilityAction("查看当天全部计划") {
                    if (enabled) { onOpenDay(date); true } else false
                })
            }) {
        Column(Modifier.padding(horizontal = 1.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Surface(shape = CircleShape,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp), contentAlignment = Alignment.Center) {
                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            rows.take(4).forEach { row -> MonthTaskTitle(date, row, taskFontSize) }
            if (rows.size > 4) Text("还有 ${rows.size - 4} 项", Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MonthTaskTitle(date: LocalDate, row: PlanListRow, taskFontSize: TextUnit) {
    val originalTitle = row.plan.title.ifBlank { "（历史空标题）" }
    val displayTitle = originalTitle.replace(Regex("[\\r\\n]+"), " ")
    val palette = quadrantPalette(row.plan.priority)
    val style = MaterialTheme.typography.labelSmall
    Surface(color = palette.container, contentColor = palette.content, shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 2.dp)) {
            Text(displayTitle, Modifier.fillMaxWidth()
                .testTag("plan-calendar-title-$date-${row.plan.id}").semantics {
                stateDescription = priorityLabel(row.plan.priority)
                if (displayTitle != originalTitle) contentDescription = originalTitle
            }, style = style.copy(fontSize = taskFontSize, lineHeight = (taskFontSize.value + 3).sp, letterSpacing = 0.sp),
                color = palette.content, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Standalone component host retained for focused month tests; production embeds [planMonthCalendar]. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PlanCalendarScreen(state: PlanCalendarState, onDate: (LocalDate) -> Unit, onMode: (PlanCalendarMode) -> Unit,
    onCompleted: (Boolean) -> Unit, onMove: (Int) -> Unit, onToday: () -> Unit, onRetry: () -> Unit,
    onOpen: ((Long) -> Unit)?, extraContent: LazyListScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            extraContent()
            planMonthCalendar(state, onDate, onDate, onMove, onToday, onRetry)
        }
    }
}
