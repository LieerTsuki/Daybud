package com.example.lifemanager.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.*
import com.example.lifemanager.domain.model.V1ReminderModule

@Composable
fun V1SettingsScreen(model:V1SettingsViewModel,backup:V1BackupSettingsViewModel,onBack:()->Unit,canStartBackup:()->Boolean={true}) {
    val state=model.uiState.collectAsStateWithLifecycle().value
    val operation=backup.uiState.collectAsStateWithLifecycle().value
    val generation=state.generation
    fun preferencesAllowed():Boolean {
        val live=model.uiState.value
        return !backup.uiState.value.busy && live.isAvailable && !live.isLoading && !live.isSaving && !live.isMaintaining
    }
    val view=PreferenceState(state.settings?.let { PreferenceValues(it.theme,it.dateFormat,it.defaultCurrency,it.defaultReminderDays,
        listOf(PreferenceReminder("plan","计划提醒",it.planReminders),PreferenceReminder("subscription","订阅提醒",it.subscriptionReminders))) },
        generation,state.isAvailable,state.isLoading,state.isSaving,state.isMaintaining,state.errorMessage)
    androidx.activity.compose.BackHandler(enabled=operation.busy) {}
    PreferenceContent(view,{ if(preferencesAllowed())model.setTheme(it,generation) },{ if(!backup.uiState.value.busy)model.retry() },{ if(!backup.uiState.value.busy)onBack() },
        { if(preferencesAllowed())model.setDateFormat(it,generation) },{ if(preferencesAllowed())model.setCurrency(it,generation) },{ day,selected->if(preferencesAllowed())model.setReminderDay(day,selected,generation) },
        { module,value->if(preferencesAllowed())model.setReminderEnabled(when(module) { "plan"->V1ReminderModule.PLAN;"subscription"->V1ReminderModule.SUBSCRIPTION;else->error("Unknown preference module") },value,generation) },
        operationBusy=operation.busy,
        backupSection={
            V1BackupSettingsSection(backup,preferencesAllowed() && canStartBackup()) { action ->
                if(preferencesAllowed() && canStartBackup())backup.start(action,generation)
            }
            if(state.remindersPending) {
            Text("偏好已保存，提醒尚未确认；此操作不会重新保存偏好")
            TextButton({ if(preferencesAllowed())model.retryReminders(generation) },enabled=preferencesAllowed()) { Text("仅重试提醒") }
        } },modulesDescription="统一计划、打卡、订阅与设置。打卡提醒、暂停和归档尚未实现。",backLabel="返回计划")
}
