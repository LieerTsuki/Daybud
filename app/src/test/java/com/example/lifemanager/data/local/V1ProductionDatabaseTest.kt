package com.example.lifemanager.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.example.lifemanager.data.backup.BackupFixtures
import com.example.lifemanager.domain.backup.BackupValue
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class V1ProductionDatabaseTest {
    @Test fun `actual factory creates only unified business tables and persisted defaults`()=fixture { db ->
        val sql=db.openHelper.writableDatabase
        assertEquals(8,sql.version)
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while(c.moveToNext())add(c.getString(0)) } }
        assertTrue(tables.containsAll(setOf("plans","plan_tag_cross_ref","plan_exceptions","plan_legacy_refs","plan_reminder_state","habits","subscriptions","app_settings","app_maintenance")))
        assertFalse("todos" in tables);assertFalse("schedules" in tables)
        sql.query("SELECT theme,planReminders,subscriptionReminders FROM app_settings WHERE id=1").use { c ->
            assertTrue(c.moveToFirst());assertEquals("SYSTEM",c.getString(0));assertEquals(1,c.getInt(1));assertEquals(1,c.getInt(2));assertFalse(c.moveToNext())
        }
        sql.query("SELECT generation FROM app_maintenance WHERE id=1").use { c -> assertTrue(c.moveToFirst());assertEquals(0,c.getLong(0)) }
    }
    @Test fun `actual factory upgrades complete v5 data without old business tables`()=fixture(5) { db ->
        val sql=db.openHelper.writableDatabase;assertEquals(8,sql.version)
        sql.query("SELECT count(*) FROM plans").use { c -> c.moveToFirst();assertEquals(2,c.getInt(0)) }
        sql.query("SELECT count(*) FROM plan_legacy_refs").use { c -> c.moveToFirst();assertEquals(2,c.getInt(0)) }
        sql.query("SELECT title FROM plans WHERE id=1").use { c -> c.moveToFirst();assertEquals("  旧待办\n\"🏠\"  ",c.getString(0)) }
        sql.query("SELECT amountMinor FROM subscriptions").use { c -> c.moveToFirst();assertEquals(Long.MAX_VALUE,c.getLong(0)) }
        sql.query("SELECT generation FROM app_maintenance").use { c -> c.moveToFirst();assertEquals(10,c.getLong(0)) }
    }
    @Test fun `actual factory registers complete v4 through v8 chain`()=fixture(4) { db ->
        val sql=db.openHelper.writableDatabase;assertEquals(8,sql.version)
        sql.query("SELECT count(*) FROM plans").use { c -> c.moveToFirst();assertEquals(2,c.getInt(0)) }
        sql.query("SELECT count(*) FROM habit_records").use { c -> c.moveToFirst();assertEquals(1,c.getInt(0)) }
        sql.query("SELECT generation FROM app_maintenance").use { c -> c.moveToFirst();assertEquals(1,c.getLong(0)) }
    }
    @Test fun `actual factory registers reconstructed v1 through v8 chain`()=earlierChain(1)
    @Test fun `actual factory registers v2 through v8 chain`()=earlierChain(2)
    @Test fun `actual factory registers v3 through v8 chain`()=earlierChain(3)

    private fun earlierChain(version:Int)=fixture(version) { db ->
        val sql=db.openHelper.writableDatabase
        assertEquals(8,sql.version)
        sql.query("SELECT count(*) FROM plans").use { c -> c.moveToFirst();assertEquals(if(version==1)1 else 2,c.getInt(0)) }
        sql.query("SELECT generation FROM app_maintenance").use { c -> c.moveToFirst();assertEquals(1,c.getLong(0)) }
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        sql.query("SELECT count(*) FROM subscriptions").use { c -> c.moveToFirst();assertEquals(0,c.getInt(0)) }
    }
    private fun fixture(version:Int?=null,block:suspend (LifeManagerDatabase)->Unit):Unit=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val name="e6-production-synthetic-${System.nanoTime()}.db"
        if(version!=null) {
            val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file,null).use { sql ->
                // There is no original v1 asset: reconstruct its unchanged three tables from v2.
                val entities=JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/${maxOf(2,version)}.json").readText()).getJSONObject("database").getJSONArray("entities")
                val created=mutableSetOf<String>()
                for(i in 0 until entities.length()) {
                    val entry=entities.getJSONObject(i);val table=entry.getString("tableName")
                    if(version==1 && table !in setOf("todos","tags","todo_tag_cross_ref"))continue
                    created.add(table)
                    sql.execSQL(entry.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entry.optJSONArray("indices")
                    if(indices!=null)for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val payload=BackupFixtures.fullPayload()
                for((table,rows) in payload.tables.filterKeys { it in created })for(row in rows)sql.execSQL(
                    "INSERT INTO `$table` (${row.fields.keys.joinToString(",") { "`$it`" }}) VALUES (${row.fields.keys.joinToString(",") { "?" }})",
                    row.fields.values.map<BackupValue,Any?> { when(it) { BackupValue.Null->null;is BackupValue.Text->it.value;is BackupValue.Integer->it.value;is BackupValue.Flag->if(it.value)1L else 0L } }.toTypedArray())
                if(version==5) {
                    sql.execSQL("INSERT INTO app_settings VALUES(1,'SYSTEM','YMD','CNY',1,1,1,0)")
                    sql.execSQL("INSERT INTO app_maintenance VALUES(1,9)")
                }
                sql.version=version
            }
        }
        val db=LifeManagerDatabaseFactory.open(context,name)
        try { block(db) } finally { db.close();context.deleteDatabase(name) }
    }
}
