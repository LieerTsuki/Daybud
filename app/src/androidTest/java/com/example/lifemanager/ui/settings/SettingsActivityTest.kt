package com.example.lifemanager.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Isolated device only: changes preferences and restores them using the current recreated owner. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun preferencesRemainSelectedAfterActivityRecreation() {
        compose.waitUntil(5000) { model().uiState.value.isAvailable }
        val original = model().uiState.value.settings!!
        try {
            compose.onNodeWithContentDescription("设置入口").performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasText("深色") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("深色").performScrollTo().performClick()
            compose.waitUntil(5000) { model().uiState.value.settings?.theme == ThemeMode.DARK && !model().uiState.value.isSaving }
            compose.onNodeWithText("DD-MM-YYYY").performScrollTo().performClick()
            compose.waitUntil(5000) { model().uiState.value.settings?.dateFormat == DateFormat.DMY && !model().uiState.value.isSaving }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("深色").performScrollTo().assertIsSelected()
            compose.onNodeWithText("DD-MM-YYYY").performScrollTo().assertIsSelected()
            compose.waitUntil(5000) { model().uiState.value.isAvailable }
            assertEquals(ThemeMode.DARK, model().uiState.value.settings!!.theme)
        } finally {
            compose.runOnIdle { model().setTheme(original.theme, model().uiState.value.generation) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
            compose.runOnIdle { model().setDateFormat(original.dateFormat, model().uiState.value.generation) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
        }
    }

    private fun model() = ViewModelProvider(compose.activity)[V1SettingsViewModel::class.java]
}
