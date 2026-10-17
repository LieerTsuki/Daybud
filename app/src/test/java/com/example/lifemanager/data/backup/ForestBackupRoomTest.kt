package com.example.lifemanager.data.backup

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ForestBackupRoomTest {
    private val f = V2BackupRoomFixtures
    private fun forest() = f.full().copy(tables = f.full().tables + mapOf(
        "habit_trees" to listOf(V2BackupFixtures.row("id" to 800L, "habitId" to 5L, "species" to "CHERRY", "plantedAt" to 7L)),
        "habit_waterings" to listOf(V2BackupFixtures.row("recordId" to 9L, "treeId" to 800L))))
    @Test fun `production Room schema preserves forest snapshot restore and clear`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("INSERT INTO habits VALUES (5,'synthetic','tree:MAPLE',0,'DAILY',1,NULL,20000,NULL,7,8)")
            sqlite.execSQL("INSERT INTO habit_records VALUES (9,5,20000,7)")
            sqlite.execSQL("INSERT INTO habit_trees VALUES (31,5,'MAPLE',7)")
            sqlite.execSQL("INSERT INTO habit_waterings VALUES (9,31)")
            val snapshots = V2BackupRepositoryImpl(db, db.backupSnapshotDao())
            val mutations = V2BackupMutationRepositoryImpl(db, db.backupSnapshotDao())
            val source = snapshots.snapshot()
            val codec = VersionedBackupJsonCodec()
            val restored = codec.decode(codec.encode(V2BackupFixtures.document(source)), java.time.ZoneId.of("UTC"))
            assertEquals(DataGeneration(1), mutations.replace(DataGeneration(0), restored.document.payload))
            assertEquals(source, snapshots.snapshot())
            assertEquals(1, source.tables.getValue("habit_waterings").size)
            assertEquals(DataGeneration(2), mutations.clearBusinessData(DataGeneration(1)))
            assertTrue(snapshots.snapshot().tables.values.all { it.isEmpty() })
            sqlite.query("SELECT seq FROM sqlite_sequence WHERE name='habit_trees'").use {
                assertTrue(it.moveToFirst()); assertEquals(31L, it.getLong(0))
            }
        } finally { db.close() }
    }
    @Test fun `restore snapshot clear include forest links and retain tree id high water`() = f.withDatabase { db ->
        val source = forest()
        f.replace(db, source)
        f.sameRows(source, f.snapshot(db))
        assertEquals(1, f.snapshot(db).tables.getValue("habit_waterings").size)
        f.clear(db, 6)
        assertTrue(f.snapshot(db).tables.values.all { it.isEmpty() })
        assertEquals(800L, f.scalar(db, "SELECT seq FROM sqlite_sequence WHERE name='habit_trees'"))
        f.replace(db, source.table("habit_trees", listOf(source.tables.getValue("habit_trees").single().with("id", BackupValue.Integer(4))))
            .table("habit_waterings", listOf(V2BackupFixtures.row("recordId" to 9L, "treeId" to 4L))), 7)
        db.openHelper.writableDatabase.execSQL("INSERT INTO habit_trees(habitId,species,plantedAt) VALUES (5,'PINE',8)")
        assertEquals(801L, f.scalar(db, "SELECT MAX(id) FROM habit_trees"))
    }
    @Test fun `failure restoring forest rolls back previous data generation and sequence`() = f.withDatabase { db ->
        f.seed(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_forest BEFORE INSERT ON habit_waterings BEGIN SELECT RAISE(ABORT,'synthetic forest failure'); END")
        assertFailsWith<Exception> { f.replace(db, forest()) }
        f.sameRows(f.full(), f.snapshot(db))
        assertEquals(5L, f.generation(db))
        assertEquals(0L, f.scalar(db, "SELECT count(*) FROM sqlite_sequence WHERE name='habit_trees'"))
    }
    @Test fun `forest row and text preflights count new tables and malformed stored links reject`() = f.withDatabase { db ->
        f.seed(db, forest())
        val count = forest().tables.values.sumOf { it.size }
        assertFailsWith<BackupValidationException> { f.snapshot(db, BackupLimits(maxRows = count - 1)) }
        assertEquals(count, f.snapshot(db, BackupLimits(maxRows = count)).tables.values.sumOf { it.size })
        db.openHelper.writableDatabase.execSQL("UPDATE habit_trees SET species='FOREIGN'")
        assertFailsWith<BackupValidationException> { f.snapshot(db) }
    }
    @Test fun `maximum incoming tree id and exhausted local tree sequence refuse mutation`() = f.withDatabase { db ->
        f.seed(db, forest())
        val maximum = forest().table("habit_trees", listOf(forest().tables.getValue("habit_trees").single().with("id", BackupValue.Integer(Long.MAX_VALUE))))
            .table("habit_waterings", listOf(V2BackupFixtures.row("recordId" to 9L, "treeId" to Long.MAX_VALUE)))
        // Rescue export remains possible; restoring an exhausted identity is forbidden.
        VersionedBackupJsonCodec().encode(V2BackupFixtures.document(maximum))
        assertFailsWith<BackupValidationException> { f.replace(db, maximum) }
        f.sameRows(forest(), f.snapshot(db))
        db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=9223372036854775807 WHERE name='habit_trees'")
        assertFailsWith<BackupValidationException> { f.replace(db, forest()) }
        assertFailsWith<BackupValidationException> { f.clear(db) }
        assertEquals(5L, f.generation(db))
        f.sameRows(forest(), f.snapshot(db))
    }
}
