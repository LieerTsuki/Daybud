package com.example.lifemanager.data.plan

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class V1SettingsRoomTest {
    @Test fun `complete target database reads defaults and persists only unified preferences`()=fixture { db,repo ->
        assertEquals(V1AppSettings(),repo.observe().first())
        repo.update(DataGeneration(0)) { it.copy(theme=ThemeMode.DARK,dateFormat=DateFormat.DMY,defaultCurrency="EUR",planReminders=false,defaultReminderDays=setOf(1,7)) }
        assertEquals(V1AppSettings(ThemeMode.DARK,DateFormat.DMY,"EUR",false,true,setOf(1,7)),repo.observe().first())
        db.openHelper.readableDatabase.query("SELECT generation FROM app_maintenance").use { it.moveToFirst();assertEquals(0,it.getLong(0)) }
        assertTrue(db.plans().allEntities().isEmpty())
    }
    @Test fun `real settings invalidation updates active observer`()=fixture { _,repo ->
        coroutineScope {
            val initial=CompletableDeferred<V1AppSettings>()
            val observed=async(Dispatchers.IO) { repo.observe().onEach { initial.complete(it) }.first { !it.planReminders } }
            assertTrue(withTimeout(5000) { initial.await() }.planReminders)
            repo.update(DataGeneration(0)) { it.copy(planReminders=false) }
            assertFalse(withTimeout(5000) { observed.await() }.planReminders)
        }
    }
    @Test fun `bad SQL flags mask text and cardinality are never silently defaulted`()=fixture { db,repo ->
        for(sql in listOf("planReminders=2","defaultReminderMask=8","theme='INVALID'","defaultCurrency='BAD'","planReminders='invalid'")) {
            db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET $sql WHERE id=1")
            assertFails { repo.observe().first() }
            db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET planReminders=1,defaultReminderMask=0,theme='SYSTEM',defaultCurrency='CNY' WHERE id=1")
        }
        db.openHelper.writableDatabase.execSQL("INSERT INTO app_settings VALUES (2,'SYSTEM','YMD','CNY',1,1,0)")
        assertFails { repo.observe().first() }
    }
    @Test fun `missing settings and broken generation reject writes rather than insert defaults`()=fixture { db,repo ->
        db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings")
        assertFails { repo.update(DataGeneration(0)) { it.copy(theme=ThemeMode.DARK) } }
        db.openHelper.readableDatabase.query("SELECT count(*) FROM app_settings").use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
    }
    @Test fun `stale expected generation and invalid transform rollback preferences`()=fixture { db,repo ->
        assertFailsWith<StaleGenerationException> { repo.update(DataGeneration(1)) { it.copy(planReminders=false) } }
        assertFailsWith<IllegalArgumentException> { repo.update(DataGeneration(0)) { it.copy(defaultReminderDays=setOf(2)) } }
        assertEquals(V1AppSettings(),repo.observe().first())
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation='bad'")
        assertFails { repo.update(DataGeneration(0)) { it.copy(theme=ThemeMode.DARK) } }
        assertEquals(V1AppSettings(),repo.observe().first())
    }
    @Test fun `late SQL metadata mutation rolls back preference and generation together`()=fixture { db,repo ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER settings_generation AFTER UPDATE ON app_settings BEGIN UPDATE app_maintenance SET generation=1; END")
        assertFailsWith<StaleGenerationException> { repo.update(DataGeneration(0)) { it.copy(planReminders=false) } }
        assertEquals(V1AppSettings(),repo.observe().first())
        db.openHelper.readableDatabase.query("SELECT generation FROM app_maintenance").use { it.moveToFirst();assertEquals(0,it.getLong(0)) }
    }
    @Test fun `original writer cancellation during transaction rolls back real SQL`()=fixture { _,repo ->
        val writer=Job()
        withContext(NonCancellable+com.example.lifemanager.domain.repository.SettingsWriteCancellation(writer)) {
            assertFailsWith<CancellationException> { repo.update(DataGeneration(0)) { writer.cancel();it.copy(planReminders=false) } }
        }
        assertEquals(V1AppSettings(),repo.observe().first())
    }
    private fun fixture(block:suspend (PlanMigrationTestDatabase,RoomV1SettingsRepository)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),PlanMigrationTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).build()
        try { block(db,RoomV1SettingsRepository(db)) } finally { db.close() }
    }
}
