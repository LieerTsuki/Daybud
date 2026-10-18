package com.example.lifemanager.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.lifemanager.ui.settings.displayDate
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@Composable
internal fun SubscriptionMonthSummary(bill: SubscriptionBillMonth, today: LocalDate, listTab: Boolean, activeCount: Int) {
    val current = bill.month == YearMonth.from(today)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (current) "本月预计" else "当月预计", style = MaterialTheme.typography.labelLarge)
            Text(moneyText(bill.forecastMinor), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { contentDescription = "当月预计费用 ${moneyText(bill.forecastMinor)}" })
            if (listTab) Text("${bill.month.monthValue}月 · $activeCount 项人民币订阅", style = MaterialTheme.typography.bodyMedium)
            else {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Text("已记录实际扣费  ${moneyText(bill.actualMinor)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun BillEmptyCard(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BillDueBadge(date: LocalDate, today: LocalDate) {
    val days = ChronoUnit.DAYS.between(today, date)
    val near = days in 0..3
    Surface(shape = RoundedCornerShape(8.dp),
        color = if (near) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (near) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(when { days == 0L -> "今天扣费"; days == 1L -> "明天扣费"; days < 0L -> "已过日期"; else -> "$days 天后" },
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun BillDateHeading(date: LocalDate, today: LocalDate) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
        Text("${displayDate(date)} · $week", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (date >= today) BillDueBadge(date, today)
    }
}

@Composable
internal fun BillEntryCard(entry: SubscriptionBillEntry, enabled: Boolean, onOpen: () -> Unit) {
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.subscription.appName, style = MaterialTheme.typography.titleMedium)
                Text(cycleLabel(entry.subscription.billingCycle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(moneyText(entry.subscription.amountMinor), style = MaterialTheme.typography.titleMedium)
        }
    }
}
