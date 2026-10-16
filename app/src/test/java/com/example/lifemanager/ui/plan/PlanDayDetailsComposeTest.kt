package com.example.lifemanager.ui.plan

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanDayDetailsComposeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `legal title line breaks are flattened so every segment is horizontally readable`() {
        val day=LocalDate.of(2026,10,16)
        val plan=Plan(id=1,title="第一段\n第二段\r\n第三段",timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
        val state=PlanCalendarState(selectedDate=day,displayZone=ZoneId.of("Asia/Shanghai"),isAvailable=true,isLoading=false,
            days=mapOf(day to listOf(PlanListRow(plan))))
        compose.setContent { LifeManagerTheme { PlanDayDetails(day,state,{},{},{}) } }
        compose.onNodeWithText("第一段 第二段 第三段",useUnmergedTree=true).assertExists()
    }
}
