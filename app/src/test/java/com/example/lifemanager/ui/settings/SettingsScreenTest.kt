package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.theme.LifeManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeSelectionShowsSelectedStateAndChangesThroughItsCallback() {
        val state = mutableStateOf(presentation(settings = values(), isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            content(state.value, onThemeChanged = { theme ->
                state.value = state.value.copy(settings = state.value.settings!!.copy(theme = theme))
            }, onRetry = {}, onBack = {})
        } }
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("浅色").assertIsNotSelected()
    }

    @Test fun savingDisablesThemeControls() {
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isLoading = false, isAvailable = true, isSaving = true), {}, {}, {})
        } }
        compose.onNodeWithText("深色").assertIsNotEnabled()
    }

    @Test fun unreadSettingsShowRetryInsteadOfAnEditableDefault() {
        compose.setContent { LifeManagerTheme {
            content(presentation(isLoading = false, errorMessage = "读取设置失败，请重试"), {}, {}, {})
        } }
        compose.onNodeWithText("重新读取设置").assertExists()
        compose.onNodeWithText("深色").assertDoesNotExist()
    }

    @Test fun failedSaveOffersReloadRatherThanPromisingToResubmitTheSelection() {
        var reloads = 0
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isLoading = false,
                isAvailable = true, errorMessage = "保存设置失败，请重新读取后再次选择"),
                {}, { reloads++ }, {})
        } }
        compose.onNodeWithText("重新读取设置").performClick()
        kotlin.test.assertEquals(1, reloads)
        compose.onNodeWithText("跟随系统").assertIsSelected()
    }

    @Test fun datePreferenceSelectionIsAccessibleAndUsesItsSaveCallback() {
        val state = mutableStateOf(presentation(settings = values(), isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            content(state.value, {}, {}, {}, onDateFormatChanged = { format ->
                state.value = state.value.copy(settings = state.value.settings!!.copy(dateFormat = format))
            })
        } }
        compose.onNodeWithText("DD-MM-YYYY").performScrollTo().performClick()
        compose.onNodeWithText("DD-MM-YYYY").assertIsSelected()
    }

    @Test fun subscriptionPolicyCannotBeChangedThroughObsoleteCurrencyAndAdvanceReminderControls() {
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(defaultReminderDays = setOf(1, 3, 7)),
                isLoading = false, isAvailable = true), {}, {}, {})
        } }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").assertDoesNotExist()
        compose.onNodeWithText("保存默认币种").assertDoesNotExist()
        listOf(1, 3, 7).forEach { day ->
            compose.onNodeWithText("提前 $day 天").assertDoesNotExist()
        }
        compose.onNodeWithText("订阅以人民币计费，扣费当天 09:00 提醒").performScrollTo().assertExists()
    }

    @Test fun subscriptionReminderSwitchStillControlsOnlySubscriptionReminders() {
        val state = mutableStateOf(presentation(settings = values(), isAvailable = true))
        compose.setContent { LifeManagerTheme {
            content(state.value, {}, {}, {}, onReminderEnabledChanged = { module, enabled ->
                val saved = state.value.settings!!
                state.value = state.value.copy(settings = saved.copy(reminders = saved.reminders.map {
                    if (it.module == module) it.copy(checked = enabled) else it
                }))
            })
        } }
        compose.onNodeWithContentDescription("订阅提醒").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithContentDescription("计划提醒").assertIsOn()
    }

    @Test fun maintenanceDisablesPreferenceWritesAndRetry() {
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isLoading = false,
                isAvailable = true, isMaintaining = true, errorMessage = "读取设置失败，请重试"), {}, {}, {})
        } }
        compose.onNodeWithText("深色").assertIsNotEnabled()
        compose.onNodeWithText("重新读取设置").assertIsNotEnabled()
        compose.onNodeWithText("DD-MM-YYYY").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("订阅提醒").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unifiedReminderSwitchUsesItsModuleIdentityWithoutChangingOtherSwitches() {
        var changed: Pair<String, Boolean>? = null
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isAvailable = true), {}, {}, {},
                onReminderEnabledChanged = { module, enabled -> changed = module to enabled })
        } }
        compose.onNodeWithContentDescription("计划提醒").performScrollTo().performClick()
        kotlin.test.assertEquals("plan" to false, changed)
    }

    private fun values(theme: ThemeMode = ThemeMode.SYSTEM, dateFormat: DateFormat = DateFormat.YMD,
        defaultReminderDays: Set<Int> = emptySet()) =
        PreferenceValues(theme, dateFormat, "CNY", defaultReminderDays,
            listOf(PreferenceReminder("plan", "计划提醒", true), PreferenceReminder("subscription", "订阅提醒", true)))

    private fun presentation(settings: PreferenceValues? = null, generation: DataGeneration? = DataGeneration(0),
        isLoading: Boolean = false, isAvailable: Boolean = false, isSaving: Boolean = false,
        isMaintaining: Boolean = false, errorMessage: String? = null) =
        PreferenceState(settings, generation, isAvailable, isLoading, isSaving, isMaintaining, errorMessage)

    @androidx.compose.runtime.Composable
    private fun content(state: PreferenceState, onThemeChanged: (ThemeMode) -> Unit = {},
        onRetry: () -> Unit = {}, onBack: () -> Unit = {},
        onDateFormatChanged: (DateFormat) -> Unit = {},
        onReminderEnabledChanged: (String, Boolean) -> Unit = { _, _ -> }) {
        PreferenceContent(state, onThemeChanged, onRetry, onBack, onDateFormatChanged,
            onReminderEnabledChanged, modulesDescription = "统一计划、打卡、订阅与设置。",
            backLabel = "返回计划")
    }
}
