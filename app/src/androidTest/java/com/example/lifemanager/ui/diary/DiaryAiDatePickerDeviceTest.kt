package com.example.lifemanager.ui.diary

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.ui.diary.ai.DiaryAiViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DiaryAiDatePickerDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun selectDate(value:String) {
        val millis=LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val description=DatePickerDefaults.dateFormatter().formatDate(millis,compose.activity.resources.configuration.locales[0],true)!!
        compose.onNode(hasText(description,substring=true) and hasClickAction()).performClick()
    }

    @Test fun actualAiScreenSelectsDatesWithoutKeyboardAndCancelDoesNotChangeRange() {
        compose.onNodeWithContentDescription("日记入口").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("AI回顾"))
        compose.onNodeWithText("AI回顾").performClick()
        val model=ViewModelProvider(compose.activity,compose.activity.diaryFactory)[DiaryAiViewModel::class.java]
        compose.runOnIdle { model.range("2026-10-12","2026-10-18") }
        compose.onNodeWithContentDescription("选择开始日期").performScrollTo().performClick()
        selectDate("2026-10-10")
        compose.onNodeWithText("取消").performClick()
        assertEquals("2026-10-12",model.uiState.value.from)
        compose.onNodeWithContentDescription("选择开始日期").performClick()
        selectDate("2026-10-10")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-10-10",model.uiState.value.from)
        compose.onNodeWithContentDescription("选择结束日期").performScrollTo().performClick()
        selectDate("2026-10-19")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-10-19",model.uiState.value.through)
        assertEquals("2026-10-10",model.uiState.value.from)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        // Date selection works before configuring any service; the generation action
        // remains governed by configuration/model readiness, not by this date test.
        compose.onNodeWithText("检查范围").performScrollTo().assertExists()
        // Only transient range state was changed; no diary or service configuration is written.
    }
}
