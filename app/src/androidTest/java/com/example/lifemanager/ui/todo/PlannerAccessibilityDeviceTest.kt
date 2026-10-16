package com.example.lifemanager.ui.todo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.ui.plan.PlanViewModel
import com.example.lifemanager.ui.plan.PlanScreen
import com.example.lifemanager.ui.theme.LifeManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owned read-only emulator only; uses real ViewModel, no business inserts or preference changes. */
@RunWith(AndroidJUnit4::class)
class PlannerAccessibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lightNarrowUnifiedPageAtDoubleFontKeepsControlsReadable() = checkNarrowPage(false)
    @Test fun darkNarrowUnifiedPageAtDoubleFontKeepsControlsReadable() = checkNarrowPage(true)

    private fun checkNarrowPage(dark: Boolean) {
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[PlanViewModel::class.java]
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    LifeManagerTheme(darkTheme = dark) {
                        Box(Modifier.width(320.dp).height(500.dp)) {
                            PlanScreen(viewModel = model, onNewInQuadrant = { _, _ -> })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("添加计划：重要且紧急") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertTextFits("计划手账")
        listOf("重要且紧急", "重要不紧急", "紧急不重要", "不重要不紧急").forEach { quadrant ->
            // Lazy quadrant rows below the compact viewport are composed on demand.
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(quadrant))
            assertTextFits(quadrant)
            val add = hasContentDescription("添加计划：$quadrant")
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(add)
            compose.onNode(add).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    private fun assertTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse(layout.didOverflowHeight, "vertical clipping at 200% font / 320dp: $label")
            // In this Compose version paragraph width may include unused trailing space:
            // observed paragraph=428px, text box=292px, actual line=0..292px (not clipped).
            // Check the actual lines rather than treating that extra whitespace as lost glyphs.
            repeat(layout.lineCount) { line ->
                assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f,
                    "horizontal clipping at 200% font / 320dp: $label; line=${layout.getLineLeft(line)}..${layout.getLineRight(line)}, box=${layout.size.width}")
            }
        }
    }
}
