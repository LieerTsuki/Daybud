package com.example.lifemanager.ui.plan

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Text entry and a real date/time picker share the same guarded edit callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanDateInput(label: String, description: String, value: String, enabled: Boolean,
    withTime: Boolean = false, onChange: (String) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var showTime by remember { mutableStateOf(false) }
    val display = if (withTime) planInputDateTime(value) else value
    val local = runCatching { LocalDateTime.parse(display.replace(' ', 'T')) }.getOrNull()
    val entryDay = local?.toLocalDate() ?: runCatching { LocalDate.parse(display) }.getOrNull()
    OutlinedTextField(value = display, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(label) }, placeholder = { Text(if (withTime) "年-月-日 时:分" else "年-月-日") },
        shape = MaterialTheme.shapes.medium,
        trailingIcon = { IconButton({ showDate = true }, enabled = enabled) { Icon(Icons.Outlined.CalendarMonth, "选择$label") } },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
    if (showDate && enabled) {
        val initial = (entryDay ?: LocalDate.now(planDisplayZone)).takeIf { it.year in 1900..2100 }
            ?: LocalDate.now(planDisplayZone)
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false },
            confirmButton = { TextButton({
                val day = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                if (day != null) {
                    showDate = false
                    if (withTime) { selectedDay = day; showTime = true } else onChange(day.toString())
                }
            }, enabled = picker.selectedDateMillis != null) { Text("确定") } },
            dismissButton = { TextButton({ showDate = false }) { Text("取消") } }) {
            DatePicker(picker, title = { Text("选择$label", Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = true)
        }
    }
    if (showTime && enabled) {
        val time = local?.toLocalTime() ?: LocalTime.of(9, 0)
        val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { showTime = false }, title = { Text("选择时间") },
            text = { TimeInput(picker) }, confirmButton = { TextButton({
                val day = selectedDay ?: entryDay ?: LocalDate.now(planDisplayZone)
                onChange(day.atTime(picker.hour, picker.minute).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                showTime = false
            }) { Text("确定") } }, dismissButton = { TextButton({ showTime = false }) { Text("取消") } })
    }
}
