package com.example.lifemanager

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.example.lifemanager.ui.navigation.*
import com.example.lifemanager.ui.diary.DiaryViewModel
import com.example.lifemanager.ui.diary.DiaryViewModelFactory
import com.example.lifemanager.ui.diary.ai.DiaryAiViewModel
import com.example.lifemanager.ui.plan.*
import com.example.lifemanager.ui.habit.HabitViewModel
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import com.example.lifemanager.ui.settings.V1SettingsViewModel
import com.example.lifemanager.ui.settings.V1BackupSettingsViewModel
import com.example.lifemanager.notification.ReminderGeneration
import com.example.lifemanager.notification.PlanNotificationHelper
import com.example.lifemanager.notification.PlanReminderIntents
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var v1Factory: V1ViewModelFactory
    @Inject lateinit var diaryFactory: DiaryViewModelFactory
    private val diary: DiaryViewModel by viewModels { diaryFactory }
    private val diaryAi: DiaryAiViewModel by viewModels { diaryFactory }
    // The Activity ViewModelStore retains every real owner across destinations and rotation.
    private val navigation: PlanNavigationViewModel by viewModels { v1Factory }
    private val plans: PlanViewModel by viewModels { v1Factory }
    private val editor: PlanEditorViewModel by viewModels { v1Factory }
    private val calendar: PlanCalendarViewModel by viewModels { v1Factory }
    private val settings: V1SettingsViewModel by viewModels { v1Factory }
    private val backup: V1BackupSettingsViewModel by viewModels { v1Factory }
    private val habits: HabitViewModel by viewModels()
    private val subscriptions: SubscriptionViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveNotificationIntent(intent)
        setContent {
            V1NavGraph(navigation, plans, editor, calendar, settings, habits, subscriptions, backup, diary, diaryAi)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Navigation owns this consumed event; keep the launch Intent stable for recreation.
        receiveNotificationIntent(intent)
    }

    private fun receiveNotificationIntent(intent: Intent) {
        val planEnvelope = intent.hasExtra(EXTRA_TODO_ID) || intent.hasExtra(EXTRA_SCHEDULE_ID) ||
            intent.hasExtra(PlanReminderIntents.EXTRA_ID) || intent.action == PlanNotificationHelper.OPEN_ACTION ||
            intent.dataString?.startsWith("lifemanager://plan-reminder/") == true
        val planClick = PlanNotificationClicks.take(intent)
        @Suppress("DEPRECATION")
        val subscriptionId = intent.extras?.get(EXTRA_SUBSCRIPTION_ID) as? Long
        val original = ReminderGeneration.read(intent)
        if (planClick != null) navigation.receive(planClick)
        else if (!planEnvelope && subscriptionId != null && subscriptionId > 0 && original != null)
            navigation.receive(PlanNotificationClick.Subscription(subscriptionId, original))
        // The event belongs to the retained navigation owner, never a replayed Activity Intent.
        intent.removeExtra(EXTRA_SUBSCRIPTION_ID)
        intent.removeExtra(ReminderGeneration.EXTRA)
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    }
}
