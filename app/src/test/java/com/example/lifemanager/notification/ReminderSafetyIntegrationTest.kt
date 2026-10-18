package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import com.example.lifemanager.data.plan.RoomV1SettingsRepository
import com.example.lifemanager.data.plan.RoomPlanReminderSources
import com.example.lifemanager.domain.model.V1AppSettings
import com.example.lifemanager.domain.repository.V1SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first

// Subscription receipt/admission cases are retained on the activated v6 schema.
// Legacy todo/schedule cases map to PlanReminderIntegrationTest, UnifiedPlanCalibrationTest,
// UnifiedLegacyCleanupTest, UnifiedWorkerRoutingTest and E6ReminderActivationTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderSafetyIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LifeManagerDatabase
    private lateinit var generations: DataGenerationRepositoryImpl
    private lateinit var coordinator: MaintenanceCoordinator
    private lateinit var settings: RoomV1SettingsRepository
    private lateinit var subscriptions: SubscriptionRepositoryImpl
    private lateinit var subscriptionScheduler: SubscriptionReminderScheduler
    private var calibrations = 0
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=37 WHERE id=1")
        generations = DataGenerationRepositoryImpl(database)
        coordinator = MaintenanceCoordinator(generations)
        settings = RoomV1SettingsRepository(database)
        subscriptions = SubscriptionRepositoryImpl(database)
        val state = ReminderSchedulingState(generations, settings)
        subscriptionScheduler = SubscriptionReminderScheduler(context) { state }
    }
    @After fun cleanup() { database.close() }

    private suspend fun reconcileSubscriptions() {
        val expected = coordinator.capture()
        coordinator.run(expected) {
            UnifiedSubscriptionCalibration(context, subscriptions, generations,
                RoomPlanReminderSources(database.planDao()), subscriptionScheduler).reconcile(expected, false)
        }
    }

    @Test fun v1SwitchChangedDuringSubscriptionReadIsCheckedAgainBeforePosting() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            val current = dueSubscription(Instant.now())
            val observed = object : com.example.lifemanager.domain.repository.SubscriptionRepository by subscriptions {
                override suspend fun getSubscription(id: Long): Subscription? {
                    val row = subscriptions.getSubscription(id)
                    // Already under the receiver's original admission. No nested coordinator.
                    settings.update(DataGeneration(37)) { it.copy(subscriptionReminders = false) }
                    return row
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val receiver = SubscriptionReminderReceiver({ observed }, { subscriptionScheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
            deliver(receiver, currentPayload(current))
            assertEquals(0, notifications.size())
            assertTrue(alarms.scheduledAlarms.isEmpty())
            assertEquals(false, settings.observe().first().subscriptionReminders)
            assertTrue(assertNotNull(subscriptions.getSubscription(current.id)).isActive)
            assertEquals(setOf(1), subscriptions.getReminderDays(current.id))
            assertEquals(false, SubscriptionReminderScheduler.wasDelivered(context, current.id, 0,
                current.nextBillingDate, DataGeneration(37)))
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test fun unreadableV1FlagFailsClosedAndRequestsFreshCalibrationWithoutPosting() = runTest {
        val current = dueSubscription(Instant.now())
        database.openHelper.writableDatabase.execSQL("UPDATE app_settings SET subscriptionReminders=3")
        val dispatcher = StandardTestDispatcher(testScheduler)
        deliver(SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler },
            { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), currentPayload(current))
        assertEquals(0, notifications.size())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, calibrations)
        assertEquals(false, SubscriptionReminderScheduler.wasDelivered(context, current.id, 0,
            current.nextBillingDate, DataGeneration(37)))
        assertEquals(DataGeneration(37), generations.current())
    }

    private fun currentPayload(subscription: Subscription) = Intent()
        .setData(Uri.parse(ReminderKey.subscriptionData(subscription.id, 0, subscription.nextBillingDate)))
        .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, subscription.id)
        .putExtra(SubscriptionReminderReceiver.EXTRA_DAYS_BEFORE, 0)
        .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, subscription.nextBillingDate.toString())
        .putExtra(ReminderGeneration.EXTRA, 37L)

    @Test fun consumedSubscriptionAlarmDuringCancelledFreezeIsRearmedNotMistakenForPendingIntent() = runTest {
        var clock = Instant.parse("2026-10-06T08:00:00Z")
        val zone = java.time.ZoneId.of("UTC")
        subscriptionScheduler.nowProvider = { clock }
        subscriptionScheduler.zoneProvider = { zone }
        val value = Subscription(appName = "synthetic blocked alarm", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 10, 6), startDate = LocalDate.of(2026, 9, 6), createdAt = clock, updatedAt = clock)
        val current = value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val old = alarms.scheduledAlarms.single().operation!!
        val payload = Intent(shadowOf(old).savedIntent)
        clock = Instant.parse("2026-10-06T09:05:00Z")
        context.getSystemService(AlarmManager::class.java).cancel(old) // OS consumed the alarm, PI still exists.
        coordinator.withSession {
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
        } // Equivalent to declining the final confirmation: no generation/data change.
        reconcileSubscriptions()
        assertEquals(1, alarms.scheduledAlarms.size)
        val rearmed = alarms.scheduledAlarms.single()
        assertEquals(clock.toEpochMilli() + 1_000, rearmed.triggerAtTime)
        assertEquals(DataGeneration(37), ReminderGeneration.read(shadowOf(rearmed.operation!!).savedIntent))
        assertEquals(current.nextBillingDate.toString(), shadowOf(rearmed.operation!!).savedIntent.getStringExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE))
        assertEquals(1, calibrations)
        assertEquals(0, notifications.size())
    }

    @Test @Config(shadows = [RetryAlarmService::class])
    fun failedSubscriptionRearmRetainsTodayForNextRetry() = runTest {
        var clock = Instant.parse("2026-10-06T08:00:00Z")
        subscriptionScheduler.nowProvider = { clock }
        subscriptionScheduler.zoneProvider = { java.time.ZoneId.of("UTC") }
        val value = Subscription(appName = "synthetic retry", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 10, 6), startDate = LocalDate.of(2026, 9, 6), createdAt = clock, updatedAt = clock)
        val current = value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val old = alarms.scheduledAlarms.single().operation!!
        context.getSystemService(AlarmManager::class.java).cancel(old)
        subscriptionScheduler.acknowledgeArrival(current.id, 0, current.nextBillingDate, DataGeneration(37))
        clock = Instant.parse("2026-10-06T09:05:00Z")
        RetryAlarmService.failNext = true
        assertFailsWith<IllegalStateException> { subscriptionScheduler.scheduleCurrent(current, setOf(1)) }
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val retry = alarms.scheduledAlarms.single()
        assertEquals(clock.toEpochMilli() + 1_000, retry.triggerAtTime)
        assertEquals(current.nextBillingDate.toString(), shadowOf(retry.operation!!).savedIntent.getStringExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE))
    }

    @Test fun admittedSubscriptionReadFailureRequestsRecoveryOfConsumedTodayAlarm() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val alarm = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(alarm).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            clock = Instant.now()
            var failRead = true
            val repository = object : com.example.lifemanager.domain.repository.SubscriptionRepository by subscriptions {
                override suspend fun getSubscription(id: Long): Subscription? {
                    if (failRead) error("synthetic temporary subscription read failure")
                    return subscriptions.getSubscription(id)
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ repository }, { subscriptionScheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
            assertEquals(0, notifications.size())
            assertEquals(1, calibrations)
            failRead = false
            reconcileSubscriptions()
            assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test fun cancellationDuringSubscriptionRearmKeepsConsumedOccurrenceUntilDelivery() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            var cancelRead = false
            val guardedSettings = object : V1SettingsRepository by settings {
                override fun observe(): Flow<V1AppSettings> = flow {
                    if (cancelRead) throw kotlinx.coroutines.CancellationException("synthetic rearm timeout")
                    emit(settings.observe().first())
                }
            }
            val state = ReminderSchedulingState(generations, guardedSettings)
            val scheduler = SubscriptionReminderScheduler(context) { state }
            scheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            scheduler.scheduleCurrent(current, setOf(1))
            val alarm = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(alarm).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            clock = Instant.now()
            cancelRead = true
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ subscriptions }, { scheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
            assertEquals(0, notifications.size(), "Cancellation before posting must suppress delivery")
            cancelRead = false
            scheduler.scheduleCurrent(current, setOf(1))
            assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
            assertEquals(1, calibrations)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun replacementArrivalQueuedWhilePostingDoesNotDeliverOccurrenceTwice() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val old = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(old).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(old)
            clock = Instant.now()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            var reads = 0
            val observed = object : V1SettingsRepository by settings {
                override fun observe(): Flow<V1AppSettings> = flow {
                    if (++reads == 2) { entered.complete(Unit); resume.await() }
                    emit(settings.observe().first())
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { observed })
            val first = beginDelivery(receiver(), payload)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!entered.isCompleted && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
            assertTrue(entered.isCompleted, "First delivery must pause after rearming and before posting")
            val replacement = alarms.scheduledAlarms.single().operation!!
            context.getSystemService(AlarmManager::class.java).cancel(replacement)
            val second = beginDelivery(receiver(), Intent(shadowOf(replacement).savedIntent))
            runCurrent()
            assertEquals(0, CountingSubscriptionNotifications.posts)
            resume.complete(Unit)
            awaitDelivery(first); awaitDelivery(second)
            assertEquals(1, CountingSubscriptionNotifications.posts, "Already-running replacement cannot post the same occurrence again")
            assertEquals(1, alarms.scheduledAlarms.size)
            assertTrue(alarms.scheduledAlarms.single().triggerAtTime > clock.plusSeconds(86400).toEpochMilli())
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun deliveryMarkerNeverSuppressesRestoredSameIdDateOrAcceptsAnOldGenerationCallback() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val old = Intent(shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent)
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
            clock = Instant.now()
            deliver(receiver(), old)
            assertEquals(1, CountingSubscriptionNotifications.posts)
            coordinator.withSession { session -> coordinator.withMaintenance(session) { generations.commit(session.generation) {} } }
            clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val restored = Intent(shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent)
            assertEquals(DataGeneration(38), ReminderGeneration.read(restored))
            clock = Instant.now()
            deliver(receiver(), restored)
            assertEquals(2, CountingSubscriptionNotifications.posts, "Old marker must not suppress new generation")
            deliver(receiver(), old)
            deliver(receiver(), restored)
            assertEquals(2, CountingSubscriptionNotifications.posts, "Late old callback must not replace the new delivered marker")
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun cancellationAfterRecordingDeliveryStillRebuildsFutureWithoutAnotherPost() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            var cancelFuture = false
            val guarded = object : V1SettingsRepository by settings {
                override fun observe(): Flow<V1AppSettings> = flow {
                    if (cancelFuture && CountingSubscriptionNotifications.posts > 0) throw kotlinx.coroutines.CancellationException("synthetic post-marker interruption")
                    emit(settings.observe().first())
                }
            }
            val state = ReminderSchedulingState(generations, guarded)
            val scheduler = SubscriptionReminderScheduler(context) { state }
            scheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            scheduler.scheduleCurrent(current, setOf(1))
            val old = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(old).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(old)
            clock = Instant.now(); cancelFuture = true
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { scheduler },
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
            deliver(receiver(), payload)
            assertEquals(1, CountingSubscriptionNotifications.posts)
            assertEquals(1, calibrations)
            cancelFuture = false
            scheduler.scheduleCurrent(current, setOf(1))
            assertTrue(alarms.scheduledAlarms.single().triggerAtTime > clock.plusSeconds(86400).toEpochMilli())
            deliver(receiver(), payload)
            assertEquals(1, CountingSubscriptionNotifications.posts)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    private suspend fun dueSubscription(clock: Instant): Subscription {
        val value = Subscription(appName = "synthetic consumed subscription", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now(), startDate = LocalDate.now().minusMonths(1), createdAt = clock, updatedAt = clock)
        return value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
    }


    private fun TestScope.deliver(receiver: BroadcastReceiver, payload: Intent) {
        awaitDelivery(beginDelivery(receiver, payload))
    }

    private fun TestScope.beginDelivery(receiver: BroadcastReceiver, payload: Intent): ShadowBroadcastPendingResult {
        val intent = Intent(payload).setAction("com.example.lifemanager.TEST_SOURCE")
        context.registerReceiver(receiver, IntentFilter(intent.action).apply { addDataScheme("lifemanager") })
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            return shadowOf(assertNotNull(shadowOf(receiver).originalPendingResult))
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun TestScope.awaitDelivery(pending: ShadowBroadcastPendingResult) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!pending.future.isDone && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue(pending.future.isDone, "Receiver must finish before closing its isolated Room database")
    }
}

/** Fail at the real scheduler's OS boundary, then use the normal AlarmManager shadow again. */
@Implements(AlarmManager::class)
class RetryAlarmService : ShadowAlarmManager() {
    @Implementation override fun setExactAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        if (failNext) { failNext = false; error("synthetic temporary alarm failure") }
        super.setExactAndAllowWhileIdle(type, at, operation)
    }
    companion object { var failNext = false }
}

@Implements(NotificationManager::class)
class CountingSubscriptionNotifications : ShadowNotificationManager() {
    @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
        if (tag?.startsWith("subscription/") == true) posts++
        super.notify(tag, id, notification)
    }
    companion object { var posts = 0 }
}
