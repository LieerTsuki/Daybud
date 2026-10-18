package com.example.lifemanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.maintenance.DataGeneration

internal data class PreferenceValues(val theme:ThemeMode,val dateFormat:DateFormat,val defaultCurrency:String,
    val defaultReminderDays:Set<Int>,val reminders:List<PreferenceReminder>)
internal data class PreferenceReminder(val module:String,val label:String,val checked:Boolean)
internal data class PreferenceState(val settings:PreferenceValues?,val generation:DataGeneration?,
    val isAvailable:Boolean,val isLoading:Boolean,val isSaving:Boolean,val isMaintaining:Boolean,val errorMessage:String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreferenceContent(state:PreferenceState,onThemeChanged:(ThemeMode)->Unit,onRetry:()->Unit,onBack:()->Unit,
    onDateFormatChanged:(DateFormat)->Unit,
    onReminderEnabledChanged:(String,Boolean)->Unit,operationBusy:Boolean=false,backupSection:@Composable ()->Unit={},
    modulesDescription:String="当前已启用统一计划、打卡、订阅费用管理和设置。打卡提醒、暂停和归档尚未实现。",backLabel:String="返回计划") {

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isLoading || state.isSaving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, enabled = !state.isSaving && !state.isMaintaining) { Text("重新读取设置") }
            }
            state.settings?.let { saved ->
                Text("主题", style = MaterialTheme.typography.titleLarge)
                val editable = state.isAvailable && !state.isLoading && !state.isSaving && !state.isMaintaining && !operationBusy
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, label) ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = saved.theme == mode, enabled = editable, role = Role.RadioButton,
                        onClick = { onThemeChanged(mode) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = saved.theme == mode, onClick = null, enabled = editable)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("日期格式", style = MaterialTheme.typography.titleLarge)
                listOf(DateFormat.YMD to "YYYY-MM-DD", DateFormat.MDY to "MM-DD-YYYY", DateFormat.DMY to "DD-MM-YYYY").forEach { (format, label) ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = saved.dateFormat == format, enabled = editable, role = Role.RadioButton,
                        onClick = { onDateFormatChanged(format) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = saved.dateFormat == format, onClick = null, enabled = editable)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("仅改变日期展示，不影响已保存的日期。", style = MaterialTheme.typography.bodySmall)
                Text("订阅以人民币计费，扣费当天 09:00 提醒", style = MaterialTheme.typography.bodyMedium)
                Text("模块提醒", style = MaterialTheme.typography.titleLarge)
                saved.reminders.forEach { (module, label, checked) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = checked, enabled = editable,
                            onCheckedChange = { onReminderEnabledChanged(module, it) },
                            modifier = Modifier.semantics { contentDescription = label })
                    }
                }
                Text("关闭会撤销该模块的提醒与通知；重新开启将从本地记录重建。打卡提醒尚未实现。", style = MaterialTheme.typography.bodySmall)
            }
            backupSection()
            Text("已完成模块", style = MaterialTheme.typography.titleLarge)
            Text(modulesDescription)
            SettingsPlatformSection()
            Button(onClick = onBack, enabled = !operationBusy && !state.isMaintaining) { Text(backLabel) }
        }
    }
}
