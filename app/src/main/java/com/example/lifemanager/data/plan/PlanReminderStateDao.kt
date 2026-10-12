package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface PlanReminderStateDao {
    @RawQuery fun read(query: SupportSQLiteQuery): Cursor
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun remember(value: PlanReminderStateEntity)
    @Query("UPDATE plan_reminder_state SET delivered=1 WHERE planId=:id AND generation=:generation AND occurrenceDate=:day AND triggerAt=:trigger AND delivered=0")
    suspend fun markDelivered(id: Long, generation: Long, day: Long, trigger: Long): Int
    @Query("DELETE FROM plan_reminder_state WHERE planId=:id") suspend fun forget(id: Long)
}
