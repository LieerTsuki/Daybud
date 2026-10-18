package com.example.lifemanager.ui.settings

import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

/** Isolated device only: changes preferences and restores them using the current recreated owner. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun themeSelectionAndStoredLegacyDatePreferenceSurviveActivityRecreation() {
        compose.waitUntil(5000) { model().uiState.value.let { it.isAvailable && !it.isLoading && !it.isSaving && !it.isMaintaining } }
        val original = model().uiState.value.settings!!
        try {
            compose.runOnIdle { model().setDateFormat(DateFormat.DMY, model().uiState.value.generation) }
            compose.waitUntil(5000) { model().uiState.value.settings?.dateFormat == DateFormat.DMY && !model().uiState.value.isSaving }
            compose.onNodeWithContentDescription("设置入口").performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasText("深色") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("深色").assertIsDisplayed()
            screenshot("settings-polish-home.png")
            captureDialog("通知与权限", "关闭权限", "settings-polish-permissions.png")
            captureDialog("关于日子芽", "关闭关于", "settings-polish-about.png")
            compose.onNodeWithText("深色").performScrollTo().performClick()
            compose.waitUntil(5000) { model().uiState.value.settings?.theme == ThemeMode.DARK && !model().uiState.value.isSaving }
            assertDateFormatControlsAbsent()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("深色").performScrollTo().assertIsSelected()
            assertDateFormatControlsAbsent()
            compose.waitUntil(5000) { model().uiState.value.isAvailable }
            assertEquals(ThemeMode.DARK, model().uiState.value.settings!!.theme)
            assertEquals(DateFormat.DMY, model().uiState.value.settings!!.dateFormat)
        } finally {
            compose.runOnIdle { model().setTheme(original.theme, model().uiState.value.generation) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
            compose.runOnIdle { model().setDateFormat(original.dateFormat, model().uiState.value.generation) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
        }
    }

    private fun assertDateFormatControlsAbsent() {
        compose.onNodeWithText("日期格式").assertDoesNotExist()
        compose.onNodeWithText("MM-DD-YYYY").assertDoesNotExist()
        compose.onNodeWithText("DD-MM-YYYY").assertDoesNotExist()
    }

    private fun captureDialog(title: String, closeLabel: String, imageName: String) {
        compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo()
        compose.onNode(hasText(title) and hasClickAction()).assertIsDisplayed().assertIsEnabled().performClick()
        val dialogTitle = hasText(title) and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(dialogTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(dialogTitle).assertIsDisplayed()
        screenshot(imageName)
        compose.onNodeWithText(closeLabel).assertIsDisplayed().performClick()
        compose.onNode(dialogTitle).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun model() = ViewModelProvider(compose.activity)[V1SettingsViewModel::class.java]
}
