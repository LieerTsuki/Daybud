package com.example.lifemanager.data.repository

import android.app.Application
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.plan.PlanEntity
import com.example.lifemanager.data.plan.RoomV1SettingsRepository
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DataGenerationRepositoryRoomTest {
    @Test fun defaultsAreInitializedWhenBackgroundOpensDatabaseFirstAndPersistOnReopen(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "maintenance-first-${System.nanoTime()}.db"
        try {
            val first = LifeManagerDatabaseFactory.open(context, name)
            try {
                val database = first
                val repository = DataGenerationRepositoryImpl(database)
                assertEquals(DataGeneration(0), repository.current())
                assertEquals(DataGeneration(0), repository.observe().first())
                assertEquals("CNY", RoomV1SettingsRepository(database).observe().first().defaultCurrency)
                repository.commit(DataGeneration(0)) { insertPlan(database, "saved") }
            } finally { first.close() }
            val reopened = LifeManagerDatabaseFactory.open(context, name)
            try {
                val database = reopened
                assertEquals(DataGeneration(1), DataGenerationRepositoryImpl(database).current())
                assertEquals("saved", database.planDao().allEntities().single().title)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun businessChangesAndGenerationCommitTogether() = withDatabase { database, repository ->
        insertPlan(database, "original")
        val result = repository.commit(DataGeneration(0)) {
            database.planDao().delete(41)
            insertPlan(database, "replacement with reused ID")
        }
        assertEquals(DataGeneration(1), result)
        assertEquals(result, repository.current())
        assertEquals(result, repository.observe().first())
        assertEquals("replacement with reused ID", database.planDao().allEntities().single().title)
        assertEquals("CNY", RoomV1SettingsRepository(database).observe().first().defaultCurrency)
    }

    @Test fun failedAndCancelledMutationRollBackBothBusinessDataAndGeneration() = withDatabase { database, repository ->
        insertPlan(database, "original")
        assertFailsWith<IllegalArgumentException> {
            repository.commit(DataGeneration(0)) {
                database.planDao().delete(41)
                throw IllegalArgumentException("synthetic insert failure")
            }
        }
        assertEquals("original", database.planDao().allEntities().single().title)
        assertEquals(DataGeneration(0), repository.current())
        assertFailsWith<CancellationException> {
            repository.commit(DataGeneration(0)) {
                database.planDao().delete(41)
                throw CancellationException("cancel before commit")
            }
        }
        assertEquals("original", database.planDao().allEntities().single().title)
        assertEquals(DataGeneration(0), repository.current())
    }

    @Test fun oldGenerationFailsBeforeInvokingMutation() = withDatabase { database, repository ->
        repository.commit(DataGeneration(0)) { insertPlan(database, "restored") }
        var invoked = false
        assertFailsWith<StaleGenerationException> {
            repository.commit(DataGeneration(0)) { invoked = true; database.planDao().delete(41) }
        }
        assertEquals(false, invoked)
        assertEquals("restored", database.planDao().allEntities().single().title)
        assertEquals(DataGeneration(1), repository.current())
    }

    @Test fun competingCommitsUsingSameTokenCannotBothChangeData() = withDatabase { database, repository ->
        val results = coroutineScope {
            listOf("first", "second").map { name ->
                async {
                    try { repository.commit(DataGeneration(0)) { insertPlan(database, name) }; true }
                    catch (_: StaleGenerationException) { false }
                }
            }.map { it.await() }
        }
        assertEquals(1, results.count { it })
        assertEquals(DataGeneration(1), repository.current())
        assertEquals(1, database.planDao().allEntities().size)
    }

    @Test fun generationOverflowFailsBeforeMutationInsteadOfConvertingSQLiteToReal() = withDatabase { database, repository ->
        database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=${Long.MAX_VALUE} WHERE id=1")
        var invoked = false
        assertFailsWith<IllegalStateException> { repository.commit(DataGeneration(Long.MAX_VALUE)) { invoked = true } }
        assertEquals(false, invoked)
        assertEquals(DataGeneration(Long.MAX_VALUE), repository.current())
    }

    @Test fun corruptOrMissingGenerationFailsClosedWithoutSeedingItBackToZero() = withDatabase { database, repository ->
        val sqlite = database.openHelper.writableDatabase
        for (invalid in listOf("-1", "1.5", "'not a generation'")) {
            sqlite.execSQL("UPDATE app_maintenance SET generation=$invalid WHERE id=1")
            assertFailsWith<IllegalStateException> { repository.current() }
            assertFailsWith<IllegalStateException> { repository.observe().first() }
        }
        sqlite.execSQL("DELETE FROM app_maintenance")
        assertFailsWith<IllegalStateException> { repository.current() }
        assertFailsWith<IllegalStateException> { repository.commit(DataGeneration(0)) { error("must not run") } }
        sqlite.query("SELECT COUNT(*) FROM app_maintenance").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun accidentalGenerationMutationIsDetectedAndEntireTransactionRollsBack() = withDatabase { database, repository ->
        insertPlan(database, "original")
        assertFailsWith<IllegalStateException> {
            repository.commit(DataGeneration(0)) {
                database.planDao().delete(41)
                database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=123 WHERE id=1")
            }
        }
        assertEquals(DataGeneration(0), repository.current())
        assertEquals("original", database.planDao().allEntities().single().title)
    }

    @Test fun settingsReadsAndWritesMustNotResetMissingMaintenanceMetadata() = withDatabase { database, _ ->
        database.openHelper.writableDatabase.execSQL("DELETE FROM app_maintenance")
        val settings = RoomV1SettingsRepository(database)
        assertEquals("CNY", settings.observe().first().defaultCurrency)
        assertFailsWith<IllegalStateException> {
            settings.update(DataGeneration(0)) { error("missing generation must reject before transformation") }
        }
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM app_maintenance").use {
            assertEquals(true, it.moveToFirst())
            assertEquals(0, it.getInt(0), "missing generation must not revive old generation-zero tokens")
        }
    }

    @Test fun extraOrWrongSingletonIdentityRejectsReadsObservationAndCommitWithoutRepair() = withDatabase { database, repository ->
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO app_maintenance (id,generation) VALUES (2,0)")
        for (keepExpectedRow in listOf(true, false)) {
            if (!keepExpectedRow) sqlite.execSQL("DELETE FROM app_maintenance WHERE id=1")
            assertFailsWith<IllegalStateException> { repository.current() }
            assertFailsWith<IllegalStateException> { repository.observe().first() }
            var invoked = false
            assertFailsWith<IllegalStateException> { repository.commit(DataGeneration(0)) { invoked = true } }
            assertEquals(false, invoked)
            sqlite.query("SELECT id,generation FROM app_maintenance ORDER BY id").use {
                val rows = buildList { while (it.moveToNext()) add(it.getLong(0) to it.getLong(1)) }
                assertEquals(if (keepExpectedRow) listOf(1L to 0L, 2L to 0L) else listOf(2L to 0L), rows)
            }
        }
    }

    private fun withDatabase(block: suspend (LifeManagerDatabase, DataGenerationRepositoryImpl) -> Unit): Unit = runBlocking {
        val fixture = V6RoomFixture()
        val database = fixture.database
        try { block(database, DataGenerationRepositoryImpl(database)) } finally { fixture.close() }
    }

    private suspend fun insertPlan(database: LifeManagerDatabase, title: String) {
        database.planDao().insert(PlanEntity(id = 41, title = title, timeZone = "UTC", createdAt = 1, updatedAt = 1))
    }
}
