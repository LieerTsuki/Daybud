package com.example.lifemanager.ui.navigation

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.notification.ReminderGeneration
import com.example.lifemanager.notification.ReminderSafetyEntryPoint
import com.example.lifemanager.ui.settings.V1SettingsViewModel
import com.example.lifemanager.ui.settings.V1BackupSettingsViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator only: unsaved drafts and navigation; no database resets. */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun repeatedTabCyclesReturnToUnifiedPlanRoot() {
        repeat(3) { listOf("计划","打卡","订阅","设置").forEach(::selectTab) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitSelectedTab("计划")
        compose.onNodeWithContentDescription("添加计划：重要且紧急").assertExists()
    }
    @Test fun habitSubpageSurvivesSwitchesAndRecreation() {
        selectTab("打卡");compose.onNodeWithText("统计").performClick()
        repeat(3) { selectTab("订阅");selectTab("打卡") }
        compose.onNodeWithText("统计").assertIsSelected()
        compose.activityRule.scenario.recreate()
        awaitSelectedTab("打卡");compose.onNodeWithText("统计").assertIsSelected()
    }
    @Test fun habitDraftSurvivesLegacyNotificationDepartureAndTabReturn() {
        selectTab("打卡")
        compose.onNodeWithContentDescription("添加习惯").performClick()
        compose.onNodeWithText("名称").performTextInput("未保存的导航习惯草稿")
        sendNotification(MainActivity.EXTRA_TODO_ID);awaitSelectedTab("计划")
        selectTab("打卡");compose.onNodeWithText("未保存的导航习惯草稿").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("未保存的导航习惯草稿").assertExists()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun subscriptionDraftRequiresDecisionBeforeLegacyNotificationAndSurvivesRotation() {
        selectTab("订阅")
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("订阅名称").performTextInput("未保存的导航订阅草稿")
        sendNotification(MainActivity.EXTRA_SCHEDULE_ID)
        compose.waitUntil(5000) { compose.onAllNodesWithText("继续编辑").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续编辑").performClick()
        awaitSelectedTab("订阅")
        compose.onNodeWithText("未保存的导航订阅草稿").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("未保存的导航订阅草稿").assertExists()
        compose.onNodeWithText("取消").performClick();awaitSelectedTab("计划")
    }
    @Test fun maintenanceRejectsNotificationAndLocksEveryDestination():Unit=runBlocking {
        selectTab("设置")
        coordinator().withSession {
            awaitMaintenanceLock()
            listOf("计划","打卡","订阅","设置").forEach { compose.onNodeWithContentDescription(it+"入口").assertIsNotEnabled() }
            sendNotification(MainActivity.EXTRA_TODO_ID);awaitSelectedTab("设置")
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("打卡入口") and isEnabled()).fetchSemanticsNodes().size==1 }
        selectTab("打卡")
    }
    @Test fun settingsReturnAndSystemBackAreLockedDuringMaintenance():Unit=runBlocking {
        selectTab("设置")
        coordinator().withSession {
            awaitMaintenanceLock()
            compose.onNodeWithText("返回计划").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            awaitSelectedTab("设置")
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("计划入口") and isEnabled()).fetchSemanticsNodes().size==1 }
        compose.onNodeWithText("返回计划").performScrollTo().performClick();awaitSelectedTab("计划")
    }
    @Test fun legacyTodoNotificationWaitsForBackupConsent()=notificationWaitsForBackup(MainActivity.EXTRA_TODO_ID,"计划")
    @Test fun legacyScheduleNotificationWaitsForBackupConsent()=notificationWaitsForBackup(MainActivity.EXTRA_SCHEDULE_ID,"计划")
    @Test fun subscriptionNotificationWaitsForBackupConsent()=notificationWaitsForBackup(MainActivity.EXTRA_SUBSCRIPTION_ID,"订阅")

    private fun notificationWaitsForBackup(extra:String,target:String) {
        selectTab("设置")
        compose.onNodeWithText("导出完整 V2 备份").performScrollTo().performClick()
        compose.onNodeWithText("导出明文备份").assertIsDisplayed()
        val owners=ViewModelProvider(compose.activity,compose.activity.v1Factory)
        val backup=owners[V1BackupSettingsViewModel::class.java]
        try {
            sendNotification(extra)
            compose.waitUntil(5000) { owners[PlanNavigationViewModel::class.java].uiState.value.pending!=null }
            compose.onNodeWithText("导出明文备份").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick();awaitSelectedTab(target)
            compose.waitUntil(5000) { !backup.uiState.value.busy }
        } finally { compose.runOnIdle { backup.uiState.value.operationId?.let(backup::cancel) } }
    }
    private fun coordinator()=EntryPointAccessors.fromApplication(
        compose.activity.applicationContext,ReminderSafetyEntryPoint::class.java).maintenanceCoordinator()
    private fun awaitMaintenanceLock() {
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("计划入口") and isNotEnabled()).fetchSemanticsNodes().size==1 }
    }
    private fun selectTab(label:String) {
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription(label+"入口") and isEnabled()).fetchSemanticsNodes().size==1 }
        compose.onNodeWithContentDescription(label+"入口").performClick();awaitSelectedTab(label)
    }
    private fun awaitSelectedTab(label:String) {
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription(label+"入口") and isSelected()).fetchSemanticsNodes().size==1 }
    }
    private fun sendNotification(extra:String) {
        compose.runOnIdle {
            val activity=compose.activity
            val settings=ViewModelProvider(activity,activity.v1Factory)[V1SettingsViewModel::class.java]
            activity.startActivity(Intent(activity,MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReminderGeneration.EXTRA,requireNotNull(settings.uiState.value.generation).value)
                .putExtra(extra,Long.MAX_VALUE))
        }
        compose.waitForIdle()
    }
}
