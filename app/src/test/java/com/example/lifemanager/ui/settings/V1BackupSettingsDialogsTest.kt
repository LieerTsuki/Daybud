package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.backup.BackupSourceFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V1BackupSettingsDialogsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `converted preview shows unified counts and source timezone without final consent`() {
        var first:Pair<String,Boolean?>?=null;var finals=0
        compose.setContent { MaterialTheme {
            V1BackupSettingsDialogs(V1BackupUiState("source-operation",BackupAction.RESTORE,BackupStep.RESTORE_PREVIEW,
                rowCounts=mapOf("plans" to 42,"plan_legacy_refs" to 7),sourceFormat=BackupSourceFormat.V1,legacyTodoTimeZone="Asia/Shanghai"),
                { id,value->first=id to value },{ _,_->finals++ })
        } }
        compose.onNodeWithText("计划：42").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("旧来源映射：7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("旧待办未保存时区",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("同意并备份当前数据").performClick()
        assertEquals("source-operation" to true,first);assertEquals(0,finals)
    }
    @Test fun `final consent remains independent accessible and bound to operation in dark narrow large font`() {
        var answer:Pair<String,Boolean>?=null
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
            MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.size(320.dp,640.dp)) {
                V1BackupSettingsDialogs(V1BackupUiState("final-operation",BackupAction.RESTORE,BackupStep.FINAL_CONFIRM,
                    protectionVerified=true),{ _,_->error("First consent reused") },{ id,value->answer=id to value })
            } }
        } }
        compose.onNodeWithText("确认替换全部数据").assertIsDisplayed().performClick()
        assertEquals("final-operation" to true,answer)
        compose.onNodeWithText("取消，保留原数据").assertIsDisplayed().performClick()
        assertEquals("final-operation" to false,answer)
    }
    @Test fun `unknown commit disables all mutation starts but permits explicit inspection`() {
        var starts=0;var inspections=0
        compose.setContent { MaterialTheme { Column {
            V1BackupSettingsControls(V1BackupUiState(step=BackupStep.ERROR,outcome=V1BackupOutcome.UNKNOWN),true,
                { starts++ },{}, { inspections++ })
        } } }
        compose.onNodeWithText("备份数据").assertIsNotEnabled()
        compose.onNodeWithText("恢复备份").assertIsNotEnabled()
        compose.onNodeWithText("清空数据").assertIsNotEnabled()
        compose.onNodeWithText("重新读取当前数据，不重试操作").performClick()
        assertEquals(1,inspections);assertEquals(0,starts)
    }
    @Test fun `committing cannot expose cancellation but pending file operation can`() {
        compose.setContent { MaterialTheme { Column {
            V1BackupSettingsControls(V1BackupUiState(step=BackupStep.COMMITTING),true,{}, {}, {})
        } } }
        compose.onNodeWithText("取消本次操作").assertDoesNotExist()
        compose.onNodeWithText("清空数据").assertIsNotEnabled()
    }
}
