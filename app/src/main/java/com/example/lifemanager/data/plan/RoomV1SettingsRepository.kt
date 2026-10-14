package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.room.*
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.*
import com.example.lifemanager.domain.maintenance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class RoomV1SettingsRepository(private val database:RoomDatabase):V1SettingsRepository {
    override fun observe():Flow<V1AppSettings> = callbackFlow {
        val observer=object:InvalidationTracker.Observer("app_settings") {
            override fun onInvalidated(tables:Set<String>) { trySend(Unit) }
        }
        database.invalidationTracker.addObserver(observer);trySend(Unit)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }.conflate().map { database.withTransaction { read() } }

    override suspend fun update(expected:DataGeneration,transform:(V1AppSettings)->V1AppSettings):V1AppSettings=database.withTransaction {
        cancellableSettingsWriteBody {
            checkGeneration(expected)
            val next=transform(read());require(V1SettingsRules.validate(next)==null) { V1SettingsRules.validate(next).orEmpty() }
            val mask=next.defaultReminderDays.sumOf { when(it) { 1->1;3->2;7->4;else->error("Invalid reminder day") } }
            database.openHelper.writableDatabase.execSQL("UPDATE app_settings SET theme=?,dateFormat=?,defaultCurrency=?,planReminders=?,subscriptionReminders=?,defaultReminderMask=? WHERE id=1",
                arrayOf<Any>(next.theme.name,next.dateFormat.name,next.defaultCurrency,if(next.planReminders)1 else 0,if(next.subscriptionReminders)1 else 0,mask))
            val saved=read();check(saved==next) { "Settings update changed unexpectedly" }
            currentCoroutineContext().ensureActive();checkGeneration(expected);saved
        }
    }
    private fun read():V1AppSettings=database.openHelper.readableDatabase.query(
        "SELECT id,theme,dateFormat,defaultCurrency,planReminders,subscriptionReminders,defaultReminderMask FROM app_settings").use { c ->
        check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_INTEGER && c.getLong(0)==1L) { "Settings singleton missing or corrupt" }
        check((1..3).all { c.getType(it)==Cursor.FIELD_TYPE_STRING } && (4..6).all { c.getType(it)==Cursor.FIELD_TYPE_INTEGER }) { "Invalid settings storage types" }
        check(c.getLong(4) in 0..1 && c.getLong(5) in 0..1 && c.getLong(6) in 0..7) { "Invalid settings flags or mask" }
        val mask=c.getInt(6)
        val value=V1AppSettings(ThemeMode.valueOf(c.getString(1)),DateFormat.valueOf(c.getString(2)),c.getString(3),c.getLong(4)==1L,c.getLong(5)==1L,
            listOf(1 to 1,3 to 2,7 to 4).filter { mask and it.second!=0 }.map { it.first }.toSet())
        check(!c.moveToNext() && V1SettingsRules.validate(value)==null) { "Invalid saved settings" };value
    }
    private fun checkGeneration(expected:DataGeneration) {
        database.openHelper.readableDatabase.query("SELECT id,generation FROM app_maintenance").use { c ->
            check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_INTEGER && c.getLong(0)==1L && c.getType(1)==Cursor.FIELD_TYPE_INTEGER) { "Local generation missing or corrupt" }
            val actual=c.getLong(1);check(actual>=0 && !c.moveToNext()) { "Invalid local generation" }
            if(actual!=expected.value)throw StaleGenerationException()
        }
    }
}
