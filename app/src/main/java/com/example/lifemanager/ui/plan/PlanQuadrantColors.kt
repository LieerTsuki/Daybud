package com.example.lifemanager.ui.plan

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.lifemanager.domain.model.TodoPriority

internal data class QuadrantPalette(val container: Color, val content: Color)

/** One palette for quadrant headings and their dated task rows, including dark mode. */
@Composable
internal fun quadrantPalette(priority: TodoPriority): QuadrantPalette = with(MaterialTheme.colorScheme) {
    when (priority) {
        TodoPriority.HIGH -> QuadrantPalette(secondaryContainer, onSecondaryContainer)
        TodoPriority.MEDIUM -> QuadrantPalette(primaryContainer, onPrimaryContainer)
        TodoPriority.LOW -> QuadrantPalette(tertiaryContainer, onTertiaryContainer)
        TodoPriority.NONE -> QuadrantPalette(surfaceVariant, onSurfaceVariant)
    }
}

/** Completion only changes the month label's appearance, never its saved quadrant. */
@Composable
internal fun calendarTaskPalette(priority: TodoPriority, isCompleted: Boolean): QuadrantPalette {
    if (!isCompleted) return quadrantPalette(priority)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return QuadrantPalette(
        container = if (dark) Color(0xFF424242) else Color(0xFFE0E0E0),
        content = if (dark) Color(0xFFE0E0E0) else Color(0xFF424242),
    )
}
