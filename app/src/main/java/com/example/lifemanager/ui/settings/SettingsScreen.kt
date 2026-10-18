package com.example.lifemanager.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.maintenance.DataGeneration

// Retired preferences remain in the adapter for historical storage/backup compatibility.
internal data class PreferenceValues(val theme: ThemeMode, val dateFormat: DateFormat, val defaultCurrency: String,
    val defaultReminderDays: Set<Int>, val reminders: List<PreferenceReminder>)
internal data class PreferenceReminder(val module: String, val label: String, val checked: Boolean)
internal data class PreferenceState(val settings: PreferenceValues?, val generation: DataGeneration?,
    val isAvailable: Boolean, val isLoading: Boolean, val isSaving: Boolean, val isMaintaining: Boolean, val errorMessage: String?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreferenceContent(state: PreferenceState, onThemeChanged: (ThemeMode) -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    onDateFormatChanged: (DateFormat) -> Unit,
    onReminderEnabledChanged: (String, Boolean) -> Unit, operationBusy: Boolean = false, backupSection: @Composable () -> Unit = {},
    modulesDescription: String = "", backLabel: String = "返回计划") {
    val editable = state.isAvailable && !state.isLoading && !state.isSaving && !state.isMaintaining && !operationBusy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Settings, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (state.isLoading || state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onRetry, enabled = !state.isSaving && !state.isMaintaining && !operationBusy) { Text("重新读取设置") }
        }
        if (state.isMaintaining) Text("数据维护中，请稍候", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.settings?.let { saved ->
            SettingsGroup("主题") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, label) ->
                        val selected = saved.theme == mode
                        Surface(modifier = Modifier.heightIn(min = 48.dp).selectable(selected, enabled = editable,
                            role = Role.RadioButton, onClick = { onThemeChanged(mode) }),
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            SettingsGroup("提醒") {
                saved.reminders.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(item.checked, { onReminderEnabledChanged(item.module, it) }, enabled = editable,
                            modifier = Modifier.semantics { contentDescription = item.label })
                    }
                }
            }
        }
        backupSection()
        SettingsPlatformSection(enabled = editable)
        TextButton(onBack, enabled = !operationBusy && !state.isMaintaining, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(backLabel)
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
internal fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) tint else tint.copy(alpha = .38f))
            Icon(Icons.Outlined.ChevronRight, null, tint = if (enabled) tint else tint.copy(alpha = .38f))
        }
    }
}
