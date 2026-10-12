package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.domain.plan.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomPlanReminderSources(private val dao: PlanDao) : PlanReminderSources, PlanReminderPreferences {
    override suspend fun configuration(): PlanReminderConfiguration = withContext(Dispatchers.IO) {
        dao.read(SimpleSQLiteQuery("SELECT id,planReminders,subscriptionReminders FROM app_settings")).use { c ->
            check(c.moveToFirst() && (0..2).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER }) { "Invalid reminder settings" }
            check(c.getLong(0) == 1L && c.getLong(1) in 0..1 && c.getLong(2) in 0..1) { "Invalid reminder settings" }
            PlanReminderConfiguration(c.getLong(1) == 1L, c.getLong(2) == 1L).also { check(!c.moveToNext()) }
        }
    }
    override suspend fun legacyIds(): LegacyPlanReminderIds = withContext(Dispatchers.IO) {
        val todos = linkedSetOf<Long>(); val schedules = linkedSetOf<Long>()
        dao.read(SimpleSQLiteQuery("SELECT r.source,r.legacyId,r.planId,p.id FROM plan_legacy_refs r LEFT JOIN plans p ON p.id=r.planId ORDER BY r.source,r.legacyId")).use { c ->
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING && (1..3).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER }) { "Invalid legacy reminder source" }
                check(c.getLong(2) > 0 && c.getLong(2) == c.getLong(3)) { "Invalid legacy reminder mapping" }
                val ids = when (PlanLegacySource.valueOf(c.getString(0))) {
                    PlanLegacySource.TODO -> todos
                    PlanLegacySource.SCHEDULE -> schedules
                }
                check(ids.add(c.getLong(1))) { "Duplicate legacy reminder identity" }
            }
        }
        LegacyPlanReminderIds(todos, schedules)
    }
    override suspend fun enabled() = configuration().planReminders
}
