package com.example.lifemanager.ui.settings

import com.example.lifemanager.domain.model.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalDateFormat = compositionLocalOf { DateFormat.YMD }

private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)

// Keep the legacy preference argument compatible without applying it to current presentation.
@Suppress("UNUSED_PARAMETER")
fun formatDisplayDate(date: LocalDate, format: DateFormat): String =
    date.format(dateFormatter)

@Suppress("UNUSED_PARAMETER")
fun formatDisplayDateTime(instant: Instant, format: DateFormat, zone: ZoneId): String =
    instant.atZone(zone).format(dateTimeFormatter)

@Composable fun displayDate(date: LocalDate): String = formatDisplayDate(date, LocalDateFormat.current)
@Composable fun displayDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    displayDate(instant.atZone(zone).toLocalDate())
@Composable fun displayDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    formatDisplayDateTime(instant, LocalDateFormat.current, zone)
