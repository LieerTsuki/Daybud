package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.data.plan.*
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import kotlin.test.assertNotNull

/** Test-owned complete target database. Only its unique synthetic file is ever deleted. */
internal class UnifiedReminderTestFixture(onFile: Boolean = false, private val allowMain: Boolean = true) {
    val context: Context = RuntimeEnvironment.getApplication()
    private val name = if (onFile) "u${System.nanoTime()}.db" else null
    var db = open()
    val clock = UnifiedTestClock()
    var onGenerationRead: (() -> Unit)? = null
    val generations = object : DataGenerationRepository {
        override fun observe() = flow { emit(current()) }
        override suspend fun current() = db.withTransaction {
            db.metadata().read(SimpleSQLiteQuery("SELECT id,generation FROM app_maintenance")).use {
                check(it.moveToFirst() && it.getType(0) == 1 && it.getLong(0) == 1L && it.getType(1) == 1)
                DataGeneration(it.getLong(1)).also { _ -> check(!it.moveToNext()) }
            }
        }.also { onGenerationRead?.invoke() }
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit) = db.withTransaction {
            check(current() == expected); mutation()
            val next = DataGeneration(Math.addExact(expected.value, 1))
            db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=?", arrayOf(next.value))
            next
        }
    }
    val coordinator = MaintenanceCoordinator(generations)
    val sources get() = RoomPlanReminderSources(db.plans())
    val repo get() = PlanRepositoryImpl(db, db.plans(), clock)
    val subscriptions get() = SubscriptionRepositoryImpl(db, db.subscriptions())
    val store get() = RoomPlanReminderStore(db.reminders())
    val notifier = PlanNotificationHelper(context)
    val scheduler get() = PlanReminderScheduler(context, repo, generations, sources, store, notifier, clock)
    val cases get() = PlanUseCases(repo, coordinator, scheduler)
    val delivery get() = PlanReminderDelivery(repo, generations, sources, store, notifier, scheduler, clock)
    fun reconciler(source: PlanReminderSources = sources, subscription: SubscriptionCalibration = subscriptionCalibration) =
        UnifiedReminderReconciler(coordinator, repo, source, store, LegacyPlanReminderCleaner(context), scheduler, notifier, subscription)
    val subscriptionCalibration get() = UnifiedSubscriptionCalibration(context, subscriptions, generations, sources, clock) { ZoneOffset.UTC }
    val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    val notifications get() = context.getSystemService(NotificationManager::class.java)
    fun plan(title: String = "当前计划") = Plan(title = title, timeZone = "UTC", timeMode = PlanTimeMode.DEADLINE,
        dueAt = Instant.parse("2026-01-01T10:00:00Z"), createdAt = clock.instant(), updatedAt = clock.instant())
    suspend fun save(plan: Plan = plan()) = coordinator.run(DataGeneration(0)) {
        PlanOperationCoordinator.run { repo.save(plan, emptyList(), DataGeneration(0)) }
    }
    suspend fun reconcile(id: Long) = coordinator.run(DataGeneration(0)) {
        PlanOperationCoordinator.run { scheduler.reconcile(id, DataGeneration(0)) }
    }
    suspend fun token(id: Long) = assertNotNull(store.get(id)).token
    fun reopen() { check(name != null); db.close(); db = open() }
    fun close() { db.close(); if (name != null) context.deleteDatabase(name) }
    private fun open(): PlanReminderTestDatabase {
        if (name != null) {
            val parent = context.getDatabasePath(name).parentFile!!
            check(parent.isDirectory || parent.mkdirs())
        }
        val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, PlanReminderTestDatabase::class.java)
            else Room.databaseBuilder(context, PlanReminderTestDatabase::class.java, name)
        builder.addCallback(PlanV6Schema.INITIALIZE)
        if (allowMain) builder.allowMainThreadQueries()
        return builder.build()
    }
}

internal class UnifiedTestClock : Clock() {
    var at = Instant.parse("2026-01-01T09:00:00Z")
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
}

@Implements(AlarmManager::class)
class UnifiedAlarmService : ShadowAlarmManager() {
    @Implementation override fun setExactAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        val id = PlanReminderIdentity.parse(shadowOf(operation).savedIntent.dataString)?.planId
        if (id != null && id in failPlanSet) error("synthetic plan alarm install failure")
        super.setExactAndAllowWhileIdle(type, at, operation)
    }
    @Implementation override fun cancel(operation: PendingIntent) {
        val component = shadowOf(operation).savedIntent.component?.className
        val id = PlanReminderIdentity.parse(shadowOf(operation).savedIntent.dataString)?.planId
        if (id != null && id in failPlanCancel) error("synthetic plan alarm cancellation failure")
        if (component != null && component in setOf(TodoReminderReceiver::class.java.name, ScheduleReminderReceiver::class.java.name)) {
            if (cancelled) throw kotlinx.coroutines.CancellationException("synthetic cancel")
            if (failLegacyCancel) error("synthetic legacy cancellation failure")
            if (failNoDataOnly && shadowOf(operation).savedIntent.data == null) error("synthetic no-data cancellation failure")
        }
        super.cancel(operation)
    }
    companion object {
        var failLegacyCancel = false; var failNoDataOnly = false; var cancelled = false
        var failPlanSet: Set<Long> = emptySet(); var failPlanCancel: Set<Long> = emptySet()
    }
}

@Implements(NotificationManager::class)
class UnifiedNotificationService : ShadowNotificationManager() {
    @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
        if (rejectPlan && notification.channelId == PlanNotificationHelper.CHANNEL) {
            setNotificationsEnabled(false)
            return
        }
        super.notify(tag, id, notification)
    }
    companion object { var rejectPlan = false }
}
