package com.example.lifemanager.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.usecase.SubscriptionRules
import com.example.lifemanager.ui.settings.displayDate
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun SubscriptionWorkspace(state: SubscriptionUiState, model: SubscriptionViewModel) {
    val generation = state.generation
    val enabled = state.isAvailable && !state.isMaintaining && !state.isBusy
    val owner = LocalLifecycleOwner.current
    var today by remember { mutableStateOf(LocalDate.now()) }
    var selectedMonth by rememberSaveable(generation?.value) { mutableStateOf(YearMonth.from(today).toString()) }
    var showCancelled by rememberSaveable(generation?.value) { mutableStateOf(false) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { today = LocalDate.now(); delay(60_000) }
        }
    }
    val month = YearMonth.parse(selectedMonth)
    val listTab = state.selectedTab == SubscriptionTab.LIST
    val statementMonth = if (listTab) YearMonth.from(today) else month
    val bill = remember(state.subscriptions, state.payments, statementMonth) {
        runCatching { subscriptionBillMonth(state.subscriptions, state.payments, statementMonth) }.getOrNull()
    }
    Scaffold(floatingActionButton = {
        if (listTab) FloatingActionButton(onClick = { if (enabled) model.openEditor(generation = generation) },
            modifier = Modifier.semantics { if (!enabled) disabled() },
            containerColor = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.Add, "添加订阅") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("订阅手账", style = MaterialTheme.typography.headlineMedium)
                        Text("把固定支出，记得清清楚楚", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Icon(Icons.Outlined.CreditCard, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    SubscriptionTab.entries.forEach { tab ->
                        val selected = state.selectedTab == tab
                        Tab(selected, { model.selectTab(tab) }, modifier = Modifier.weight(1f).padding(4.dp)
                            .clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                            text = { Text(if (tab == SubscriptionTab.LIST) "订阅" else "账单") })
                    }
                }
            }
            state.errorMessage?.let { message -> item(key = "error") {
                Text(message, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton({ model.retry(generation) }, enabled = !state.isMaintaining && !state.isBusy) { Text("重试读取") }
                    TextButton({ model.clearError(generation) }) { Text("关闭提示") }
                }
            } }
            if (state.isLoading) item(key = "loading") { CircularProgressIndicator() }
            if (state.isMaintaining) item(key = "maintenance") { Text("数据维护中，请稍后；未提交的草稿会保留。") }
            if (!listTab) item(key = "month") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ selectedMonth = month.minusMonths(1).toString() }, enabled = month.year > 1900,
                        modifier = Modifier.semantics { contentDescription = "上个月账单" }) { Text("‹ 上月") }
                    Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                    TextButton({ selectedMonth = month.plusMonths(1).toString() }, enabled = month.year < 2100,
                        modifier = Modifier.semantics { contentDescription = "下个月账单" }) { Text("下月 ›") }
                }
            }
            if (state.isAvailable && state.hasSubscriptionData && state.hasPaymentData) {
                if (bill != null) item(key = "summary") {
                    SubscriptionMonthSummary(bill, today, listTab, state.subscriptions.count { it.isActive && it.currency == "CNY" })
                } else item(key = "overflow") { Text("账单金额超出支持范围，请编辑订阅后重试。", color = MaterialTheme.colorScheme.error) }
            }
            if (listTab) {
                val active = state.subscriptions.filter { it.isActive }
                val cancelled = state.subscriptions.filterNot { it.isActive }
                item(key = "list-title") { Text("使用中 · ${active.size}", style = MaterialTheme.typography.titleMedium) }
                if (!state.isLoading && state.isAvailable && active.isEmpty()) item(key = "empty") {
                    BillEmptyCard("还没有使用中的订阅", "点击右下角的 ＋，记下第一笔固定支出")
                }
                items(active, key = { "subscription-${it.id}" }) { subscription ->
                    SubscriptionCard(subscription, today, enabled) { model.openDetail(subscription.id, generation) }
                }
                if (cancelled.isNotEmpty()) item(key = "cancelled-toggle") {
                    TextButton({ showCancelled = !showCancelled }) { Text("已取消 · ${cancelled.size} ${if (showCancelled) "⌃" else "⌄"}") }
                }
                if (showCancelled) items(cancelled, key = { "subscription-${it.id}" }) { subscription ->
                    SubscriptionCard(subscription, today, enabled) { model.openDetail(subscription.id, generation) }
                }
            } else if (bill != null && state.isAvailable) {
                item(key = "bill-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("预计账单 · ${bill.entries.size} 笔", style = MaterialTheme.typography.titleMedium)
                        Text("按当前订阅推算，实际扣费以记录为准", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (bill.entries.isEmpty()) item(key = "bill-empty") { BillEmptyCard("这个月没有预计账单", "换个月份看看，或添加一项新订阅") }
                bill.entries.groupBy { it.date }.forEach { (date, entries) ->
                    item(key = "bill-date-$date") { BillDateHeading(date, today) }
                    items(entries, key = { "bill-${it.subscription.id}-${it.date}" }) { entry ->
                        BillEntryCard(entry, enabled) { model.openDetail(entry.subscription.id, generation) }
                    }
                }
                item(key = "actual-title") { Text("实际扣费记录 · ${bill.payments.size} 笔", style = MaterialTheme.typography.titleMedium) }
                if (bill.payments.isEmpty()) item(key = "actual-empty") {
                    Text("还没有实际扣费记录，可在订阅详情中记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(bill.payments, key = { "payment-${it.id}" }) { payment ->
                    Card(onClick = { model.openDetail(payment.subscriptionId, generation) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.subscriptions.firstOrNull { it.id == payment.subscriptionId }?.appName ?: "历史订阅", style = MaterialTheme.typography.titleMedium)
                            Text("${displayDate(payment.paidAt)} · 已记录 ${moneyText(payment.amountMinor)}")
                        }
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(if (listTab) 80.dp else 16.dp)) }
        }
    }
}

@Composable
private fun SubscriptionCard(subscription: Subscription, today: LocalDate, enabled: Boolean, onOpen: () -> Unit) {
    val due = SubscriptionRules.effectiveNextBillingDate(subscription, today)
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.appName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(cycleLabel(subscription.billingCycle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(moneyText(subscription.amountMinor, subscription.currency), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (due != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下次 ${displayDate(due)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                BillDueBadge(due, today)
            } else Text(if (subscription.isActive) "无可用的未来扣费日期" else "已取消 · 保留扣费记录", style = MaterialTheme.typography.bodySmall)
        }
    }
}
