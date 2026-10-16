package com.example.lifemanager.ui.settings

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.unit.dp

/** Smoke timings include instrumentation/idleness overhead; not frame rate or macrobenchmark claims. */
@RunWith(AndroidJUnit4::class)
class AppPagePerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun unifiedDestinationsReachUsableControls() {
        listOf("计划" to "添加计划：重要且紧急", "打卡" to "添加习惯", "订阅" to "添加订阅").forEach { (tab, action) ->
            val begin = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription("${tab}入口").performClick()
            val control = hasContentDescription(action)
            compose.waitUntil(10_000) { compose.onAllNodes(control and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            if (tab == "计划") compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(control)
            Log.i("E6PageSmoke", "$tab usable-control-ms=${SystemClock.elapsedRealtime() - begin}")
            compose.onNode(control).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        val begin = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription("设置入口").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("深色") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("深色").assertIsEnabled()
        Log.i("E6PageSmoke", "设置 usable-control-ms=${SystemClock.elapsedRealtime() - begin}")
    }
}
