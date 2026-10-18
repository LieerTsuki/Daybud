package com.example.lifemanager.ui.subscription

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
internal fun SubscriptionDateInput(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.medium,
        trailingIcon = { IconButton({ showPicker = true }, enabled = enabled) { Icon(Icons.Outlined.CalendarMonth, "选择$label") } })
    if (showPicker && enabled) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1900..2100 } ?: LocalDate.now()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showPicker = false }, confirmButton = {
            TextButton({
                picker.selectedDateMillis?.let { millis ->
                    onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    showPicker = false
                }
            }, enabled = picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ showPicker = false }) { Text("取消") } }) {
            DatePicker(picker, title = { Text("选择$label", Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = true)
        }
    }
}
