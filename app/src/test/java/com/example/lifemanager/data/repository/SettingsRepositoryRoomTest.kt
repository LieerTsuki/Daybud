package com.example.lifemanager.data.repository

import android.app.Application
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.plan.RoomV1SettingsRepository
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.V1AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
class SettingsRepositoryRoomTest {
    private lateinit var fixture: V6RoomFixture
    private val database get() = fixture.database
    private lateinit var repository: RoomV1SettingsRepository
    private val generation = DataGeneration(0)
    @Before fun setup() {
        fixture = V6RoomFixture()
        repository = RoomV1SettingsRepository(database)
    }
    @After fun cleanup() = fixture.close()

    @Test fun productionDefaultsReadWithoutCreatingBusinessRecords(): Unit = runBlocking {
        assertEquals(V1AppSettings(), repository.observe().first())
        assertTrue(database.planDao().allEntities().isEmpty())
        assertEquals(DataGeneration(0), DataGenerationRepositoryImpl(database).current())
    }

    @Test fun firstAndSubsequentFiniteObservationsNeverInitializeSettings(): Unit = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_read_insert BEFORE INSERT ON app_settings BEGIN SELECT RAISE(ABORT, 'read must not initialize'); END")
        repeat(2) { assertEquals(V1AppSettings(), repository.observe().first()) }
    }

    @Test fun missingSettingsReadAndUpdateFailWithoutRecreationOrTransformation(): Unit = runBlocking {
        database.openHelper.writableDatabase.execSQL("DELETE FROM app_settings")
        assertFailsWith<IllegalStateException> { repository.observe().first() }
        var transformed = false
        assertFailsWith<IllegalStateException> {
            repository.update(generation) { transformed = true; it.copy(theme = ThemeMode.DARK) }
        }
        assertFalse(transformed)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM app_settings").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun allReminderBitCombinationsRoundTripWithoutChangingIndependentFields(): Unit = runBlocking {
        for (mask in 0..7) {
            val days = listOf(1 to 1, 3 to 2, 7 to 4).filter { mask and it.second != 0 }.map { it.first }.toSet()
            val expected = V1AppSettings(ThemeMode.DARK, DateFormat.DMY, "EUR", false, true, days)
            assertEquals(expected, repository.update(generation) { expected })
            assertEquals(expected, repository.observe().first())
            database.openHelper.readableDatabase.query("SELECT defaultReminderMask FROM app_settings WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(mask, it.getInt(0))
            }
        }
        assertEquals(generation, DataGenerationRepositoryImpl(database).current())
    }

    @Test fun invalidCurrencyOrReminderDayCannotOverwriteSavedPreferences(): Unit = runBlocking {
        val saved = repository.update(generation) { it.copy(theme = ThemeMode.DARK, defaultCurrency = "EUR") }
        assertFailsWith<IllegalArgumentException> { repository.update(generation) { it.copy(defaultCurrency = "INVALID") } }
        assertEquals(saved, repository.observe().first())
        assertFailsWith<IllegalArgumentException> { repository.update(generation) { it.copy(defaultReminderDays = setOf(2)) } }
        assertEquals(saved, repository.observe().first())
    }

    @Test fun settingsSurviveProductionFactoryCloseAndReopen(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "e6-preferences-${System.nanoTime()}.db"
        val expected = V1AppSettings(ThemeMode.LIGHT, DateFormat.MDY, "USD", false, false, setOf(1, 7))
        try {
            val first = LifeManagerDatabaseFactory.open(context, name)
            try { RoomV1SettingsRepository(first).update(generation) { expected } } finally { first.close() }
            val reopened = LifeManagerDatabaseFactory.open(context, name)
            try { assertEquals(expected, RoomV1SettingsRepository(reopened).observe().first()) } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun concurrentIndependentTransformsReadLatestCommittedPreference(): Unit = runBlocking {
        coroutineScope {
            val theme = async { repository.update(generation) { it.copy(theme = ThemeMode.DARK) } }
            val currency = async { repository.update(generation) { it.copy(defaultCurrency = "EUR") } }
            theme.await(); currency.await()
        }
        assertEquals(V1AppSettings(theme = ThemeMode.DARK, defaultCurrency = "EUR"), repository.observe().first())
    }

    @Test fun sequentialDayChangesDoNotLoseEarlierCommitBeforeObservation(): Unit = runBlocking {
        repository.update(generation) { it.copy(defaultReminderDays = it.defaultReminderDays + 1) }
        repository.update(generation) { it.copy(defaultReminderDays = it.defaultReminderDays + 7) }
        assertEquals(setOf(1, 7), repository.observe().first().defaultReminderDays)
    }
}
