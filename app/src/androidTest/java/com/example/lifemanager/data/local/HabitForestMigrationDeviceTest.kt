package com.example.lifemanager.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.domain.model.TreeSpecies
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every test owns a separate database file; the application's database is never removed. */
@RunWith(AndroidJUnit4::class)
class HabitForestMigrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeManagerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun productionFactoryUpgradesV6WithoutRewritingHabitsAndOldRecordsBecomeManualWater() =
        runBlocking(Dispatchers.IO) {
            val name = "forest-migration-${UUID.randomUUID()}.db"
            val today = LocalDate.now()
            val old = helper.createDatabase(name, 6)
            val beforeHabits: List<List<String?>>
            val beforeRecords: List<List<String?>>
            try {
                old.execSQL("INSERT INTO app_maintenance(id,generation) VALUES(1,17)")
                old.execSQL("""INSERT INTO habits(id,name,iconKey,color,frequencyType,frequencyValue,
                    customDaysOfWeek,startDate,note,createdAt,updatedAt)
                    VALUES(7,'  原有习惯🌱  ','legacy-star',-16711936,'DAILY',1,NULL,?,' 原始备注 ',1234,5678)""",
                    arrayOf(today.minusDays(30).toEpochDay()))
                old.execSQL("""INSERT INTO habits(id,name,iconKey,color,frequencyType,frequencyValue,
                    customDaysOfWeek,startDate,note,createdAt,updatedAt)
                    VALUES(8,'已有树种','tree:MAPLE',-1,'DAILY',1,NULL,?,NULL,2345,6789)""",
                    arrayOf(today.minusDays(30).toEpochDay()))
                listOf(today.minusDays(2), today.minusDays(1), today.plusDays(1)).forEachIndexed { index, date ->
                    old.execSQL("INSERT INTO habit_records(id,habitId,date,createdAt) VALUES(?,7,?,?)",
                        arrayOf(100L + index, date.toEpochDay(), 9000L + index))
                }
                beforeHabits = rows(old, "SELECT * FROM habits ORDER BY id")
                beforeRecords = rows(old, "SELECT * FROM habit_records ORDER BY id")
            } finally { old.close() }

            val database = LifeManagerDatabaseFactory.open(context, name)
            try {
                val sqlite = database.openHelper.writableDatabase // Room validates the actual v7 schema.
                assertEquals(8, sqlite.version)
                assertEquals(beforeHabits, rows(sqlite, "SELECT * FROM habits ORDER BY id"))
                assertEquals(beforeRecords, rows(sqlite, "SELECT * FROM habit_records ORDER BY id"))
                assertEquals(listOf(listOf("17")), rows(sqlite, "SELECT generation FROM app_maintenance"))
                assertTrue(rows(sqlite, "PRAGMA foreign_key_check").isEmpty())
                assertEquals(listOf(listOf("0")), rows(sqlite, "SELECT COUNT(*) FROM habit_waterings"))

                val repository = HabitRepositoryImpl(database)
                val forest = repository.observeForest().first()
                assertEquals(2, forest.trees.size)
                val legacy = forest.trees.single { it.habitId == 7L }
                val explicit = forest.trees.single { it.habitId == 8L }
                assertEquals(TreeSpecies.GINKGO, legacy.species, "Legacy species is a stable ID mapping")
                assertEquals(TreeSpecies.MAPLE, explicit.species)
                assertTrue(forest.trees.all { it.wateringCount == 0 && !it.isMature })
                assertEquals(2, forest.availableDrops[7L], "Future records cannot be spent early")
                assertEquals(0, forest.availableDrops[8L])
                assertTrue(runCatching { repository.waterTree(explicit.id) }.isFailure,
                    "A task cannot consume another task's old records")
                assertTrue(runCatching { repository.plantTree(7) }.isFailure,
                    "An immature migrated tree blocks replanting")

                repository.waterTree(legacy.id)
                assertEquals(listOf(listOf("100", legacy.id.toString())),
                    rows(sqlite, "SELECT recordId,treeId FROM habit_waterings"))
                repository.waterTree(legacy.id)
                assertEquals(2, repository.observeForest().first().trees.single { it.id == legacy.id }.wateringCount)
                assertTrue(runCatching { repository.waterTree(legacy.id) }.isFailure,
                    "The future record is still unavailable after both eligible drops are spent")
                assertFalse(repository.toggleRecord(7, today.minusDays(2)))
                assertEquals(1, repository.observeForest().first().trees.single { it.id == legacy.id }.wateringCount)
                assertEquals(listOf(listOf("101", legacy.id.toString())),
                    rows(sqlite, "SELECT recordId,treeId FROM habit_waterings"))
                assertFalse(repository.toggleRecord(7, today.minusDays(1)))
                assertEquals(0, repository.observeForest().first().trees.single { it.id == legacy.id }.wateringCount)
                assertEquals(0, repository.observeForest().first().availableDrops[7L])
                assertTrue(rows(sqlite, "PRAGMA foreign_key_check").isEmpty())
                // Undo removes exactly its watering association; the old future record remains intact.
                assertEquals(listOf(listOf("102")), rows(sqlite, "SELECT id FROM habit_records"))
            } finally {
                try { database.close() } finally { context.deleteDatabase(name) }
            }
        }

    private fun rows(database: SupportSQLiteDatabase, sql: String): List<List<String?>> =
        database.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
}
