package com.example.lifemanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.lifemanager.ui.diary.DiaryScreen
import com.example.lifemanager.ui.diary.DiaryViewModel
import com.example.lifemanager.ui.diary.DiarySaveStatus
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.ui.plan.*
import com.example.lifemanager.ui.settings.V1SettingsViewModel
import com.example.lifemanager.ui.settings.V1SettingsScreen
import com.example.lifemanager.ui.settings.V1BackupSettingsViewModel
import com.example.lifemanager.ui.settings.LocalDateFormat
import com.example.lifemanager.ui.habit.HabitViewModel
import com.example.lifemanager.ui.habit.HabitScreen
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import com.example.lifemanager.ui.subscription.SubscriptionScreen
import com.example.lifemanager.ui.theme.LifeManagerTheme
import com.example.lifemanager.domain.model.DateFormat
import kotlinx.coroutines.launch

/** Every destination uses the actual retained Activity owner. */
@Composable
fun V1NavGraph(navigation:PlanNavigationViewModel,list:PlanViewModel,editor:PlanEditorViewModel,
    calendar:PlanCalendarViewModel,settings:V1SettingsViewModel,
    habits:HabitViewModel,subscriptions:SubscriptionViewModel,backup:V1BackupSettingsViewModel,
    diary:DiaryViewModel?=null) {
    val state=navigation.uiState.collectAsStateWithLifecycle().value
    val edit=editor.uiState.collectAsStateWithLifecycle().value
    val prefs=settings.uiState.collectAsStateWithLifecycle().value
    val operation=backup.uiState.collectAsStateWithLifecycle().value
    val sub=subscriptions.uiState.collectAsStateWithLifecycle().value
    val diaryState=diary?.uiState?.collectAsStateWithLifecycle()?.value
    val planNavigationEditor=remember(editor) { PlanNavigationEditorAdapter(editor) }
    val subscriptionNavigationEditor=remember(subscriptions) { SubscriptionNavigationEditorAdapter(subscriptions) }
    val stamp=edit.stamp // Immutable rendering stamp for every captured tab callback.
    val scope=rememberCoroutineScope()
    val pages=rememberSaveableStateHolder()
    val enabled=state.generation!=null && !state.isMaintaining && !prefs.isSaving && !prefs.isMaintaining && !edit.isBusy && !operation.busy &&
        !sub.blocksNavigation && diaryState?.busy!=true
    fun liveNavigationAllowed()=diary?.uiState?.value?.busy!=true && !settings.uiState.value.isSaving && !settings.uiState.value.isMaintaining &&
        !editor.uiState.value.isBusy && !editor.uiState.value.isMaintaining && !backup.uiState.value.busy &&
        !subscriptionNavigationEditor.snapshot.blocksNavigation
    LaunchedEffect(prefs.settings?.planReminders,state.destination) {
        // Same-source preference changes and returning to Plan refresh hints without clearing drafts.
        if(state.destination==V1Destination.PLAN && prefs.settings!=null)editor.retryRead()
    }
    LaunchedEffect(state.pending?.ticket,edit.stamp,edit.isLoading,edit.isAvailable,edit.isBusy,prefs.isSaving,state.waitingForDraft,operation.busy,
        sub.generation,sub.isAvailable,sub.isLoading,sub.isBusy,sub.isMaintaining,sub.isExporting,sub.isExportPending,sub.editor,sub.paymentEditor,diaryState?.busy) {
        if(liveNavigationAllowed()) {
            if(state.pending!=null && state.pending.destination!=V1Destination.DIARY)diary?.flush()
            navigation.deliver(planNavigationEditor,subscriptionNavigationEditor)
        }
    }
    BackHandler(enabled=state.destination!=V1Destination.PLAN || state.isMaintaining) {
        if(liveNavigationAllowed())navigation.requestDestination(V1Destination.PLAN,stamp)
    }
    LifeManagerTheme(themeMode=prefs.settings?.theme) {
        CompositionLocalProvider(LocalDateFormat provides DateFormat.YMD) {
            Scaffold(bottomBar={ Surface(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(8.dp),
                shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp) {
                NavigationBar(windowInsets=WindowInsets(0,0,0,0)) {
                    V1Destination.entries.forEach { destination ->
                        val label=when(destination) { V1Destination.PLAN->"计划";V1Destination.HABIT->"打卡";V1Destination.DIARY->"日记";V1Destination.SUBSCRIPTION->"订阅";V1Destination.SETTINGS->"设置" }
                        val icon=when(destination) { V1Destination.PLAN->Icons.Outlined.CheckCircle;V1Destination.HABIT->Icons.Outlined.Repeat;V1Destination.DIARY->Icons.Outlined.Book;V1Destination.SUBSCRIPTION->Icons.Outlined.Payments;V1Destination.SETTINGS->Icons.Outlined.Settings }
                        NavigationBarItem(state.destination==destination,
                            { if(liveNavigationAllowed()) { if(destination!=V1Destination.DIARY)diary?.flush();navigation.requestDestination(destination,stamp) } },
                            { Icon(icon,null) },label={ Text(label,maxLines=1,overflow=TextOverflow.Ellipsis) },enabled=enabled,
                            modifier=Modifier.semantics { contentDescription="${label}入口" })
                    }
                }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    state.message?.let { Text(it,Modifier.padding(12.dp)) }
                    state.error?.let {
                        Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)
                        TextButton(navigation::retryRead,enabled=!state.isMaintaining) { Text("重新读取导航") }
                    }
                    if(state.waitingForDraft)Text("有待处理通知，请先保存或关闭当前草稿",Modifier.padding(12.dp))
                    if(state.destination==V1Destination.SETTINGS && diaryState?.draft!=null && diaryState.saveStatus!=DiarySaveStatus.SAVED) {
                        Text("日记草稿尚未保存，请返回日记重试后再备份或恢复。",Modifier.padding(horizontal=12.dp))
                        TextButton({ if(liveNavigationAllowed())navigation.requestDestination(V1Destination.DIARY,stamp) }) { Text("返回日记草稿") }
                    }
                    Box(Modifier.weight(1f)) { pages.SaveableStateProvider(state.destination.name) {
                        when(state.destination) {
                            V1Destination.PLAN->PlanWorkspace(list,editor,calendar)
                            V1Destination.HABIT->HabitScreen(habits)
                            V1Destination.DIARY->if(diary!=null) DiaryScreen(diary,enabled)
                            V1Destination.SUBSCRIPTION->SubscriptionScreen(subscriptions)
                            V1Destination.SETTINGS->V1SettingsScreen(settings,backup,
                                onBack={ if(liveNavigationAllowed())navigation.requestDestination(V1Destination.PLAN,stamp) },
                                canStartBackup={ diary?.uiState?.value?.let { !it.busy && it.saveStatus==DiarySaveStatus.SAVED }!=false && !editor.uiState.value.isBusy && !editor.uiState.value.isMaintaining &&
                                    subscriptions.uiState.value.let { !it.isBusy && !it.isMaintaining && !it.isExporting && !it.isExportPending && !it.editor.isOpen && !it.paymentEditor.isOpen } &&
                                    navigation.uiState.value.let { it.destination==V1Destination.SETTINGS && it.pending==null && it.confirmation==null } })
                        }
                        // Registered after destination children; their BackHandlers cannot win a freeze.
                        BackHandler(enabled=state.isMaintaining || edit.isMaintaining || prefs.isMaintaining || operation.busy || sub.isBusy || sub.isExporting || sub.isExportPending || diaryState?.busy==true) {}
                    } }
                }
            }
            state.confirmation?.let { consent ->
                AlertDialog(onDismissRequest={ navigation.cancelLeave(consent) },title={ Text("离开前保留草稿？") },
                    text={ Text("继续编辑可保留输入，保存或关闭草稿后再处理通知。放弃草稿不会写入数据库。") },
                    confirmButton={ TextButton({ scope.launch { if(liveNavigationAllowed())navigation.confirmLeave(consent,planNavigationEditor,subscriptionNavigationEditor) } },enabled=enabled) { Text("放弃草稿并继续") } },
                    dismissButton={ TextButton({ navigation.cancelLeave(consent) }) { Text("继续编辑") } })
            }
        }
    }
}
