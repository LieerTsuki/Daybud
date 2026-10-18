package com.example.lifemanager.ui.settings

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Real system OpenDocument/CreateDocument cancellation only. Never creates/deletes app business rows. */
@RunWith(AndroidJUnit4::class)
class BackupPickerActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancelledSystemSourcePickerKeepsSettingsAndReturnsToUsableActions() {
        openSettings()
        backupAction("恢复备份").performClick()
        cancelSystemPicker()
        compose.onNodeWithText("已取消，原数据和草稿已保留。").performScrollTo().assertIsDisplayed()
        backupAction("恢复备份")
    }

    @Test fun exportShowsPrivacyConsentThenLaunchesRealSystemDestinationPicker() {
        openSettings()
        backupAction("备份数据").performClick()
        compose.onNodeWithText("导出明文备份").assertIsDisplayed()
        compose.onNodeWithText("选择保存位置").performClick()
        cancelSystemPicker()
        compose.onNodeWithText("已取消，原数据和草稿已保留。").performScrollTo().assertIsDisplayed()
        backupAction("备份数据")
    }

    private fun openSettings() {
        compose.waitUntil(5_000) { settingsReady() }
        compose.onNodeWithContentDescription("设置入口").performClick()
        compose.waitUntil(5_000) {
            settingsReady() && compose.onAllNodes(hasText("深色") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("深色").assertIsDisplayed()
    }

    private fun settingsReady(): Boolean =
        ViewModelProvider(compose.activity)[V1SettingsViewModel::class.java].uiState.value.let {
            it.isAvailable && !it.isLoading && !it.isSaving && !it.isMaintaining
        }

    private fun backupAction(label: String): SemanticsNodeInteraction {
        // Offscreen button text remains in the unmerged tree; scroll before resolving its clickable parent.
        compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
        return compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
    }

    private fun cancelSystemPicker() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + 10_000_000_000L
        var pickerVisible = false
        while (!pickerVisible && System.nanoTime() < deadline) {
            compose.waitForIdle() // Drain IO-triggered recomposition and launcher LaunchedEffect.
            pickerVisible = automation.rootInActiveWindow?.packageName?.toString()?.let { it.endsWith("documentsui") } == true
            if (!pickerVisible) Thread.sleep(50)
        }
        assertTrue(pickerVisible, "Actual Android system document picker must have appeared")
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(10_000) {
            ViewModelProvider(compose.activity)[V1BackupSettingsViewModel::class.java].uiState.value.let {
                !it.busy && it.outcome == V1BackupOutcome.UNCHANGED && it.message == "已取消，原数据和草稿已保留。"
            }
        }
    }
}
