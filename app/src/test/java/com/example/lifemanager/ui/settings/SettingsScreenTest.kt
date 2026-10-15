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

    @Test fun currencyEditorPassesTheEnteredCodeToTheSaveAction() {
        var requested: String? = null
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isLoading = false, isAvailable = true),
                {}, {}, {}, onCurrencyChanged = { requested = it })
        } }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").performScrollTo().performTextReplacement("EUR")
        compose.onNodeWithText("保存默认币种").performScrollTo().performClick()
        kotlin.test.assertEquals("EUR", requested)
    }

    @Test fun reminderSelectionKeepsOtherSelectedDays() {
        val state = mutableStateOf(presentation(settings = values(defaultReminderDays = setOf(3)),
            isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            content(state.value, {}, {}, {}, onReminderDayChanged = { day, selected ->
                val current = state.value.settings!!
                state.value = state.value.copy(settings = current.copy(defaultReminderDays =
                    if (selected) current.defaultReminderDays + day else current.defaultReminderDays - day))
            })
        } }
        compose.onNodeWithText("提前 1 天").performScrollTo().performClick()
        kotlin.test.assertEquals(setOf(1, 3), state.value.settings!!.defaultReminderDays)
        compose.onNodeWithText("提前 3 天").performClick()
        kotlin.test.assertEquals(setOf(1), state.value.settings!!.defaultReminderDays)
    }

    @Test fun maintenanceDisablesPreferenceWritesAndRetry() {
        compose.setContent { LifeManagerTheme {
            content(presentation(settings = values(), isLoading = false,
                isAvailable = true, isMaintaining = true, errorMessage = "读取设置失败，请重试"), {}, {}, {})
        } }
        compose.onNodeWithText("深色").assertIsNotEnabled()
        compose.onNodeWithText("重新读取设置").assertIsNotEnabled()
        compose.onNodeWithText("DD-MM-YYYY").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("保存默认币种").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("提前 7 天").performScrollTo().assertIsNotEnabled()
    }

    @Test fun currencyDraftSurvivesCancelledFreezeButResetsOnCommittedGenerationWithSameCurrency() {
        val state = mutableStateOf(presentation(settings = values(), isLoading = false,
            isAvailable = true, generation = DataGeneration(0)))
        compose.setContent { LifeManagerTheme { content(state.value, {}, {}, {}) } }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").performScrollTo().performTextReplacement("EUR")
        compose.runOnIdle { state.value = state.value.copy(isMaintaining = true) }
        compose.runOnIdle { state.value = state.value.copy(isMaintaining = false) }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").assertTextContains("EUR")
        compose.runOnIdle { state.value = state.value.copy(generation = DataGeneration(1)) }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").assertTextContains("CNY")
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
        onDateFormatChanged: (DateFormat) -> Unit = {}, onCurrencyChanged: (String) -> Unit = {},
        onReminderDayChanged: (Int, Boolean) -> Unit = { _, _ -> },
        onReminderEnabledChanged: (String, Boolean) -> Unit = { _, _ -> }) {
        PreferenceContent(state, onThemeChanged, onRetry, onBack, onDateFormatChanged, onCurrencyChanged,
            onReminderDayChanged, onReminderEnabledChanged, modulesDescription = "统一计划、打卡、订阅与设置。",
            backLabel = "返回计划")
    }
}
