package com.example.lifemanager.data.backup

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderToken
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Short SQL capture then independent system enumeration, under the caller's SAME frozen session. */
class V2BackupReminderCaptureImpl(private val database: RoomDatabase, private val dao: BackupSnapshotDao,
    private val notifications: BackupPlanNotificationSource) : BackupReminderCapture {
    override suspend fun snapshot(): ReminderIdentities {
        val identities = database.withTransaction {
            val todos = linkedSetOf<Long>(); val schedules = linkedSetOf<Long>(); val mapped = mutableSetOf<Long>()
            dao.read(SimpleSQLiteQuery("SELECT r.source,r.legacyId,r.planId,p.id FROM plan_legacy_refs r LEFT JOIN plans p ON p.id=r.planId ORDER BY r.source,r.legacyId")).use { c ->
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    checkBackup(c.getType(0) == Cursor.FIELD_TYPE_STRING && (1..3).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER }, "旧提醒来源类型或关系损坏")
                    checkBackup(c.getLong(2) > 0 && c.getLong(2) == c.getLong(3) && mapped.add(c.getLong(2)), "旧提醒来源映射损坏")
                    val ids = when (c.getString(0)) {
                        "TODO" -> todos; "SCHEDULE" -> schedules
                        else -> throw BackupValidationException("旧提醒来源无效")
                    }
                    checkBackup(ids.add(c.getLong(1)), "旧提醒来源重复")
                }
            }
            val subscriptions = dao.read(SimpleSQLiteQuery("SELECT id FROM subscriptions ORDER BY id")).use { c -> buildList {
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    checkBackup(c.getType(0) == Cursor.FIELD_TYPE_INTEGER, "订阅提醒主键类型无效")
                    add(c.getLong(0))
                }
            } }
            val alarms = dao.read(SimpleSQLiteQuery("SELECT planId,generation,occurrenceDate,triggerAt,delivered FROM plan_reminder_state ORDER BY planId")).use { c -> buildList {
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    checkBackup((0..4).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER } && c.getLong(4) in 0..2, "计划提醒技术库存损坏")
                    add(PlanReminderToken(c.getLong(0), DataGeneration(c.getLong(1)), LocalDate.ofEpochDay(c.getLong(2)), Instant.ofEpochMilli(c.getLong(3))))
                }
            } }
            ReminderIdentities(todos.toList(), schedules.toList(), subscriptions, alarms)
        }
        // Never hold a Room transaction across NotificationManager/Binder work.
        currentCoroutineContext().ensureActive()
        val cards = notifications.tokens().distinct()
        currentCoroutineContext().ensureActive()
        return identities.copy(planNotificationTokens = cards)
    }
}
