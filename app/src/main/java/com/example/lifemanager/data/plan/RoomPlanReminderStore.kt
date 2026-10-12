package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomPlanReminderStore(private val dao: PlanReminderStateDao) : PlanReminderStore {
    override suspend fun get(planId: Long): PlanReminderState? = withContext(Dispatchers.IO) {
        require(planId > 0)
        dao.read(SimpleSQLiteQuery("$SELECT WHERE planId=?", arrayOf(planId))).use {
            if (!it.moveToFirst()) null else decode(it).also { _ -> check(!it.moveToNext()) }
        }
    }
    override suspend fun all(): List<PlanReminderState> = withContext(Dispatchers.IO) {
        dao.read(SimpleSQLiteQuery("$SELECT ORDER BY planId")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor)) }
        }
    }
    override suspend fun remember(token: PlanReminderToken) = dao.remember(PlanReminderStateEntity(
        token.planId, token.generation.value, token.occurrenceDate.toEpochDay(), token.triggerAt.toEpochMilli(), 0))
    override suspend fun markDelivered(token: PlanReminderToken): Boolean = dao.markDelivered(
        token.planId, token.generation.value, token.occurrenceDate.toEpochDay(), token.triggerAt.toEpochMilli()) == 1
    override suspend fun markAwaiting(token: PlanReminderToken): Boolean = dao.markAwaiting(
        token.planId, token.generation.value, token.occurrenceDate.toEpochDay(), token.triggerAt.toEpochMilli()) == 1
    override suspend fun markArrived(token: PlanReminderToken): Boolean = dao.markArrived(
        token.planId, token.generation.value, token.occurrenceDate.toEpochDay(), token.triggerAt.toEpochMilli()) == 1
    override suspend fun forget(planId: Long) { require(planId > 0); dao.forget(planId) }

    private fun decode(cursor: Cursor): PlanReminderState {
        check((0..4).all { cursor.getType(it) == Cursor.FIELD_TYPE_INTEGER }) { "Invalid reminder inventory types" }
        val delivered = cursor.getLong(4)
        check(delivered in 0..2) { "Invalid reminder decision" }
        return PlanReminderState(PlanReminderToken(cursor.getLong(0), DataGeneration(cursor.getLong(1)),
            LocalDate.ofEpochDay(cursor.getLong(2)), Instant.ofEpochMilli(cursor.getLong(3))), delivered == 1L, delivered == 2L)
    }
    private companion object {
        const val SELECT = "SELECT planId,generation,occurrenceDate,triggerAt,delivered FROM plan_reminder_state"
    }
}
