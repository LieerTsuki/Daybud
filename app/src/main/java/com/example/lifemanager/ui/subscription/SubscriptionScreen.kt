package com.example.lifemanager.ui.subscription

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SubscriptionScreen(viewModel: SubscriptionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column {
        ReminderPermissionBanner()
        SubscriptionContent(state, viewModel)
    }
}

@Composable
fun SubscriptionContent(state: SubscriptionUiState, viewModel: SubscriptionViewModel,
    @Suppress("UNUSED_PARAMETER") onExport: () -> Unit = {}) {
    val renderedGeneration = state.generation
    val canMutate = state.isAvailable && !state.isMaintaining && !state.isBusy
    SubscriptionWorkspace(state, viewModel)
    if (state.detailId != null && !state.editor.isOpen && !state.paymentEditor.isOpen) SubscriptionDetail(state, viewModel)
    val pendingNotificationLabel = state.pendingNotificationId?.let { id ->
        state.subscriptions.firstOrNull { it.id == id }?.appName ?: "订阅"
    }
    if (state.editor.isOpen) key(state.editor.generation, state.editor.sessionId) {
        SubscriptionEditor(state.editor, viewModel, pendingNotificationLabel, canMutate)
    }
    if (state.paymentEditor.isOpen) key(state.paymentEditor.generation, state.paymentEditor.subscriptionId, state.paymentEditor.editingId) {
        PaymentEditor(state.paymentEditor, viewModel, pendingNotificationLabel, canMutate)
    }
}

@Composable
private fun ReminderPermissionBanner() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    var notifications by remember { mutableStateOf(notificationsAllowed()) }
    var exact by remember { mutableStateOf(exactAllowed()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            notifications = notificationsAllowed(); exact = exactAllowed()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!notifications || !exact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(if (!notifications) "通知未允许，订阅提醒无法显示。" else "精确提醒未允许，提醒可能延迟。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val action = if (!notifications) android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    else if (Build.VERSION.SDK_INT >= 31) android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val intent = Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                if (action != android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }) { Text("打开系统权限设置") }
        }
    }
}
