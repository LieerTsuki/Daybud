package com.example.lifemanager.ui.navigation

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.PlanReminderToken
import com.example.lifemanager.notification.*
import java.time.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PlanNotificationClickTest {
    private val token=PlanReminderToken(7,DataGeneration(4),LocalDate.parse("2026-10-14"),Instant.parse("2026-10-14T04:00:00Z"))
    private fun intent()=PlanReminderIntents.metadata(Intent(RuntimeEnvironment.getApplication(),MainActivity::class.java)
        .setAction(PlanNotificationHelper.OPEN_ACTION).setData(PlanReminderIdentity.data(token).toUri()),token)
    @Test fun `matching activity click opens exact source identity and is consumed once`() {
        val source=intent();assertEquals(PlanNotificationClick.Detail(token),PlanNotificationClicks.take(source))
        assertNull(PlanNotificationClicks.take(source));assertFalse(source.hasExtra(PlanReminderIntents.EXTRA_ID))
    }
    @Test fun `wrong action URI type or mismatching metadata cannot become plan selection`() {
        val invalid=listOf(intent().setAction("wrong"),intent().setData("lifemanager://plan-reminder/v1/8/4/20740/0".toUri()),
            intent().putExtra(PlanReminderIntents.EXTRA_ID,7),intent().putExtra(ReminderGeneration.EXTRA,5L),
            intent().also { it.removeExtra(PlanReminderIntents.EXTRA_DAY) },intent().putExtra(MainActivity.EXTRA_TODO_ID,7L))
        invalid.forEach { assertNull(PlanNotificationClicks.take(it)) }
    }
    @Test fun `old todo or schedule click has no new entity id and drains old extras`() {
        listOf(MainActivity.EXTRA_TODO_ID,MainActivity.EXTRA_SCHEDULE_ID).forEach { key ->
            val source=Intent().putExtra(key,Long.MIN_VALUE).putExtra(ReminderGeneration.EXTRA,1L)
            assertEquals(PlanNotificationClick.Legacy,PlanNotificationClicks.take(source));assertNull(PlanNotificationClicks.take(source))
        }
    }
    @Test fun `unrelated subscription notification is untouched`() {
        val source=Intent().putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID,9L).putExtra(ReminderGeneration.EXTRA,4L)
        assertNull(PlanNotificationClicks.take(source));assertEquals(9L,source.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID,0))
        assertEquals(4L,source.getLongExtra(ReminderGeneration.EXTRA,-1))
    }
}
