package com.example.lifemanager.ui.diary.ai

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiReviewDateInput(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    // A busy transition dismisses tentative selection rather than reopening it later.
    var showPicker by remember(enabled) { mutableStateOf(false) }
    OutlinedTextField(value, {}, readOnly = true, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { showPicker = true }, enabled = enabled) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = "选择$label")
            }
        })
    if (showPicker && enabled) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(1900, initial.year)..maxOf(2100, initial.year))
        DatePickerDialog(onDismissRequest = { showPicker = false }, confirmButton = {
            TextButton(onClick = {
                if (enabled) picker.selectedDateMillis?.let { millis ->
                    onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    showPicker = false
                }
            }, enabled = enabled && picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }) {
            DatePicker(picker, title = { Text("选择$label", Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = false)
        }
    }
}
