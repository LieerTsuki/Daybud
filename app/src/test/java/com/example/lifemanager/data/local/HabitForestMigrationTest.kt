package com.example.lifemanager.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HabitForestMigrationTest {
    @Test fun versionSixUpgradeKeepsRawHabitRecordsAndOnlyAddsUnwateredSeeds() {
        val context = RuntimeEnvironment.getApplication()
        val name = "forest-upgrade-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/6.json").readText())
                .getJSONObject("database").getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entry = entities.getJSONObject(index)
                val table = entry.getString("tableName")
                old.execSQL(entry.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entry.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO app_settings VALUES(1,'SYSTEM','YMD','CNY',1,1,0)")
            old.execSQL("INSERT INTO app_maintenance VALUES(1,8)")
            old.execSQL("INSERT INTO habits VALUES(-1,'  保留名字  ','Check',-16738680,'DAILY',1,NULL,20000,' 原备注 ',4,5)")
            old.execSQL("INSERT INTO habits VALUES(2,'枫树任务','tree:MAPLE',123,'WEEKLY',2,NULL,20000,NULL,6,7)")
            old.execSQL("INSERT INTO habit_records VALUES(7,-1,20000,19)")
            old.execSQL("INSERT INTO habit_records VALUES(8,2,20001,20)")
            old.version = 6
        }
        val upgraded = LifeManagerDatabaseFactory.open(context, name)
        try {
            val sql = upgraded.openHelper.writableDatabase
            assertEquals(7, sql.version)
            sql.query("SELECT name,iconKey,color,note,createdAt,updatedAt FROM habits WHERE id=-1").use {
                assertTrue(it.moveToFirst()); assertEquals("  保留名字  ", it.getString(0)); assertEquals("Check", it.getString(1))
                assertEquals(-16738680, it.getInt(2)); assertEquals(" 原备注 ", it.getString(3)); assertEquals(4, it.getLong(4)); assertEquals(5, it.getLong(5))
            }
            sql.query("SELECT id,habitId,date,createdAt FROM habit_records ORDER BY id").use {
                assertTrue(it.moveToFirst()); assertEquals(listOf(7L,-1L,20000L,19L), (0..3).map(it::getLong))
                assertTrue(it.moveToNext()); assertEquals(listOf(8L,2L,20001L,20L), (0..3).map(it::getLong)); assertFalse(it.moveToNext())
            }
            sql.query("SELECT habitId,species,plantedAt FROM habit_trees ORDER BY habitId").use {
                assertTrue(it.moveToFirst()); assertEquals(-1, it.getLong(0)); assertEquals("BIRCH", it.getString(1)); assertEquals(4, it.getLong(2))
                assertTrue(it.moveToNext()); assertEquals(2, it.getLong(0)); assertEquals("MAPLE", it.getString(1)); assertEquals(6, it.getLong(2)); assertFalse(it.moveToNext())
            }
            sql.query("SELECT COUNT(*) FROM habit_waterings").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT generation FROM app_maintenance").use { it.moveToFirst(); assertEquals(8, it.getLong(0)) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
