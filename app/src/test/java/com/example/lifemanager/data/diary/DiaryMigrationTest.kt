package com.example.lifemanager.data.diary

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
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
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DiaryMigrationTest {
    @Test fun everyOriginalTableColumnAndAutoincrementHighWaterSurvivesUpgrade() {
        val context=RuntimeEnvironment.getApplication()
        val name="diary-raw-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val entities=JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/7.json").readText()).getJSONObject("database").getJSONArray("entities")
        val names=(0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        lateinit var before:Map<String,List<List<String?>>>
        var sequences:List<List<String?>> = emptyList()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            for(index in 0 until entities.length()) {
                val entity=entities.getJSONObject(index)
                val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
                val fields=entity.getJSONArray("fields")
                val columns=(0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                val values=(0 until fields.length()).map { i ->
                    val field=fields.getJSONObject(i)
                    val column=field.getString("columnName")
                    when {
                        column=="parentId" -> "NULL"
                        column=="generation" -> "37"
                        field.getString("affinity")=="INTEGER" -> if(column=="id" || column.endsWith("Id")) "1" else (-1000-index*100-i).toString()
                        else -> "'  历史-${table}-${column}🌱  '"
                    }
                }
                old.execSQL("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${values.joinToString()})")
            }
            old.execSQL("UPDATE sqlite_sequence SET seq=999999")
            before=names.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid",null).use(::rawRows) }
            sequences=old.rawQuery("SELECT * FROM sqlite_sequence ORDER BY name",null).use(::rawRows)
            old.version=7
        }
        val db=LifeManagerDatabaseFactory.open(context,name)
        try {
            val sql=db.openHelper.writableDatabase
            assertEquals(8,sql.version)
            assertEquals(15,before.size)
            names.forEach { table -> sql.query("SELECT * FROM `$table` ORDER BY rowid").use { assertEquals(before.getValue(table),rawRows(it),"Raw values changed in $table") } }
            sql.query("SELECT * FROM sqlite_sequence ORDER BY name").use { assertEquals(sequences,rawRows(it)) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun rawRows(cursor:android.database.Cursor):List<List<String?>> = buildList {
        while(cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> if(cursor.isNull(index)) null else "${cursor.getType(index)}:${cursor.getString(index)}" })
    }

    @Test fun versionSevenAddsSevenTablesWithoutRewritingHistoricalValuesOrSequence() {
        val context=RuntimeEnvironment.getApplication()
        val name="diary-migration-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            val entities=JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/7.json").readText()).getJSONObject("database").getJSONArray("entities")
            for(index in 0 until entities.length()) {
                val entity=entities.getJSONObject(index)
                val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO app_settings VALUES(1,'DARK','DMY','USD',0,1,0)")
            old.execSQL("INSERT INTO app_maintenance VALUES(1,37)")
            old.execSQL("INSERT INTO tags VALUES(11,'  原始标签  ',123,42)")
            old.execSQL("UPDATE sqlite_sequence SET seq=999 WHERE name='tags'")
            old.version=7
        }
        val db=LifeManagerDatabaseFactory.open(context,name)
        try {
            val sql=db.openHelper.writableDatabase
            assertEquals(8,sql.version)
            sql.query("SELECT name,color,createdAt FROM tags WHERE id=11").use { assertTrue(it.moveToFirst()); assertEquals("  原始标签  ",it.getString(0)); assertEquals(123L,it.getLong(1)); assertEquals(42L,it.getLong(2)) }
            sql.query("SELECT seq FROM sqlite_sequence WHERE name='tags'").use { assertTrue(it.moveToFirst()); assertEquals(999L,it.getLong(0)) }
            sql.query("SELECT generation FROM app_maintenance").use { it.moveToFirst(); assertEquals(37L,it.getLong(0)) }
            sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'diary_%'").use { assertEquals(7,it.count) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
