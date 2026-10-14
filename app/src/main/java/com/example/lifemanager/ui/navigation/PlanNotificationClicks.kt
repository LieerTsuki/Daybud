package com.example.lifemanager.ui.navigation

import android.content.Intent
import com.example.lifemanager.MainActivity
import com.example.lifemanager.notification.*

/** Activity adapter for the prepared V1 owner, not installed into MainActivity yet. */
object PlanNotificationClicks {
    fun take(intent:Intent):PlanNotificationClick? {
        val legacy=intent.hasExtra(MainActivity.EXTRA_TODO_ID) || intent.hasExtra(MainActivity.EXTRA_SCHEDULE_ID)
        val unified=intent.action==PlanNotificationHelper.OPEN_ACTION || intent.hasExtra(PlanReminderIntents.EXTRA_ID) ||
            intent.dataString?.startsWith("lifemanager://plan-reminder/")==true
        if(!legacy && !unified)return null // In particular, do not drain subscription's generation.
        val result=if(unified) {
            val token=PlanReminderIdentity.parse(intent.dataString)
            if(!legacy && !intent.hasExtra(MainActivity.EXTRA_SUBSCRIPTION_ID) && intent.action==PlanNotificationHelper.OPEN_ACTION &&
                intent.component?.className==MainActivity::class.java.name && intent.component?.packageName=="com.example.lifemanager" &&
                (intent.`package`==null || intent.`package`=="com.example.lifemanager") && token!=null && PlanReminderIntents.matchesMetadata(intent.extras,token))
                PlanNotificationClick.Detail(token)else null
        } else PlanNotificationClick.Legacy // Deliberately never read or map the old numeric ID.
        listOf(MainActivity.EXTRA_TODO_ID,MainActivity.EXTRA_SCHEDULE_ID,PlanReminderIntents.EXTRA_ID,
            PlanReminderIntents.EXTRA_DAY,PlanReminderIntents.EXTRA_TRIGGER).forEach(intent::removeExtra)
        if(!intent.hasExtra(MainActivity.EXTRA_SUBSCRIPTION_ID))intent.removeExtra(ReminderGeneration.EXTRA)
        if(intent.action==PlanNotificationHelper.OPEN_ACTION)intent.action=null
        if(intent.dataString?.startsWith("lifemanager://plan-reminder/")==true)intent.data=null
        return result
    }
}
