package com.example.lifemanager.ui.plan

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
