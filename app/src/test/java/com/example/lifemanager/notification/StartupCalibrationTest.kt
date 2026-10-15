package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.TestWorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.LifeManagerApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real Application/WorkManager wiring; never writes business rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StartupCalibrationTest {
    private lateinit var context: LifeManagerApp
    private lateinit var manager: WorkManagerImpl
    @Before fun setup() {
        context = LifeManagerApp()
        // Robolectric does not run the merged AndroidX Startup provider before onCreate.
        // Attach the real application, then initialize WorkManager as that provider does.
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = context
        }
        ReflectionHelpers.setField(context, "mBase", base)
        val configuration = Configuration.Builder().build()
        manager = TestWorkManagerImpl(context, configuration, WorkManagerTaskExecutor(configuration.taskExecutor))
        WorkManagerImpl.setDelegate(manager)
    }

    @After fun cleanup() {
        if (::manager.isInitialized) manager.close()
        WorkManagerImpl.setDelegate(null)
    }

    @Test fun startupEnqueuesFreshCalibrationWithoutWaitingForTheDailyPeriod() {
        context.onCreate()
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("unified_reminder_reconciliation_immediate").get()
        assertEquals(1, work.size, "Startup must enqueue a fresh current-data calibration")
        val spec = assertNotNull(manager.workDatabase.workSpecDao().getWorkSpec(work.single().id.toString()))
        assertEquals(UnifiedReminderReconciliationWorker::class.java.name, spec.workerClassName)
        assertEquals(mapOf("reset_system" to true), spec.input.keyValueMap)
        assertEquals(1, manager.getWorkInfosForUniqueWork("unified_reminder_reconciliation_periodic").get().size)
        assertTrue(manager.getWorkInfosForUniqueWork("todo_reminder_reconciliation").get().isEmpty())
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertNotNull(notifications.getNotificationChannel(PlanNotificationHelper.CHANNEL))
        assertNotNull(notifications.getNotificationChannel(SubscriptionNotificationHelper.CHANNEL_ID))
    }

    @Test fun defaultBindingsShareAdmissionSchedulerStoreAndNotificationSingletons(): Unit = runBlocking {
        context.onCreate()
        val safety = EntryPointAccessors.fromApplication(context, ReminderSafetyEntryPoint::class.java)
        assertSame(context.runner, safety.reminderBroadcastRunner())
        assertSame(safety.maintenanceCoordinator(), ReflectionHelpers.getField<Any>(context.runner, "coordinator"))
        assertSame(safety.settingsRepository(), ReflectionHelpers.getField<Any>(safety.reminderSchedulingState(), "settings"))
        val scheduler: PlanReminderScheduler = ReflectionHelpers.getField(context.delivery, "scheduler")
        assertSame(scheduler, ReflectionHelpers.getField<Any>(context.unifiedReminderReconciliation, "scheduler"))
        assertSame(context.planNotifications, ReflectionHelpers.getField<Any>(context.delivery, "notifier"))
        val store: com.example.lifemanager.domain.plan.PlanReminderStore = ReflectionHelpers.getField(context.delivery, "store")
        assertSame(store, ReflectionHelpers.getField<Any>(scheduler, "store"))
        assertSame(store, ReflectionHelpers.getField<Any>(context.unifiedReminderReconciliation, "store"))
        // A read through the actual default Hilt helper must use the v6 settings schema.
        val generation = safety.maintenanceCoordinator().capture()
        safety.maintenanceCoordinator().run(generation) {
            assertEquals(generation, safety.reminderSchedulingState().read().first)
        }
    }

    @Test fun systemEventsRetainFreshCalibrationPassesWithoutBroadcastLifetimeWork(): Unit = runBlocking {
        val coordinator = EntryPointAccessors.fromApplication(context, ReminderSafetyEntryPoint::class.java).maintenanceCoordinator()
        coordinator.withSession {
            // The shadow dispatch establishes a real PendingResult, not a null test-only goAsync.
            val receiver = BootReceiver()
            val shadow = org.robolectric.Shadows.shadowOf(receiver)
            shadow.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED), java.util.concurrent.atomic.AtomicBoolean(false))
            shadow.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED), java.util.concurrent.atomic.AtomicBoolean(false))
            assertEquals(false, shadow.wentAsync(), "Boot delegates to durable work; it must not hold a broadcast across database reads")
            val work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("unified_reminder_reconciliation_immediate").get()
            assertEquals(2, work.size, "System events retain serialized fresh passes rather than dropping a request or replaying payloads")
        }
    }
}
