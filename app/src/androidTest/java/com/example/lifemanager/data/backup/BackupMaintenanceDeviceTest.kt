package com.example.lifemanager.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ThemeMode
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Native v2 restore/clear with real Downloads protection IO and one production-factory database. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupMaintenanceDeviceTest {
    @Test fun restoreRequiresVerifiedProtectionAndIndependentConfirmation(): Unit = scenario { f, protection ->
        val before = f.snapshots.snapshot()
        val rows = f.rows()
        val technical = f.rows(listOf("plan_reminder_state", "sqlite_sequence"))
        val restored = before.copy(tables = before.tables.mapValues { emptyList() })
        var confirmations = 0
        suspend fun restore(confirmed: Boolean) = f.workflow.execute(
            V2BackupMaintenanceRequest.Restore(DataGeneration(73), BackupLocation("content://test/source"),
                V2BackupDocument("1.0.0", "2026-10-15T00:00:00Z", restored)), "1.0.0", "2026-10-15T00:00:00Z",
            interaction(protection) {
                assertEquals(MaintenanceState.READY, f.coordinator.state.value)
                assertFalse(f.database.inTransaction(), "provider/confirmation must not hold a SQLite transaction")
                val verified = f.files.preview(protection, ZoneId.of("UTC"))
                assertEquals(BackupSourceFormat.V2, verified.sourceFormat)
                f.assertPayloadRows(rows, verified.document.payload)
                assertEquals(before.settings, verified.document.payload.settings)
                assertEquals(rows, f.rows())
                assertEquals(DataGeneration(73), f.generations.current())
                confirmations++
                confirmed
            })
        assertFalse(restore(false).committed)
        assertEquals(rows, f.rows())
        assertEquals(DataGeneration(73), f.generations.current())
        assertNull(f.cleared)
        assertTrue(restore(true).committed)
        assertEquals(restored, f.snapshots.snapshot())
        assertEquals(DataGeneration(74), f.generations.current())
        assertEquals(technical, f.rows(technical.keys.toList()))
        assertEquals(listOf(Long.MIN_VALUE), f.cleared!!.todoIds)
        assertEquals(listOf(Long.MAX_VALUE), f.cleared!!.scheduleIds)
        assertEquals(listOf(8L), f.cleared!!.subscriptionIds)
        assertEquals(1, f.cleared!!.planAlarmTokens.size)
        assertEquals(2, confirmations)
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
        f.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun insertFailureRollsBackRowsPreferencesGenerationInventoryAndSequences(): Unit = scenario { f, protection ->
        val before = f.snapshots.snapshot()
        val raw = f.rows(V2BackupDeviceFixture.businessTables + V2BackupDeviceFixture.technicalTables + "app_settings")
        f.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restore BEFORE INSERT ON plans BEGIN SELECT RAISE(ABORT, 'synthetic insert failure'); END")
        assertFails {
            f.workflow.execute(V2BackupMaintenanceRequest.Restore(DataGeneration(73), BackupLocation("content://test/source"),
                V2BackupDocument("1.0.0", "2026-10-15T00:00:00Z", before.copy(settings = before.settings.copy(theme = ThemeMode.LIGHT)))),
                "1.0.0", "2026-10-15T00:00:00Z", interaction(protection) { true })
        }
        assertEquals(raw, f.rows(raw.keys.toList()))
        assertEquals(DataGeneration(73), f.generations.current())
        assertNull(f.cleared)
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }

    @Test fun clearPreservesPreferencesInventoryAndSequencesAndAdvancesGenerationOnce(): Unit = scenario { f, _ ->
        val before = f.snapshots.snapshot()
        val technical = f.rows(listOf("plan_reminder_state", "sqlite_sequence"))
        val result = f.workflow.execute(V2BackupMaintenanceRequest.Clear(DataGeneration(73), false),
            "1.0.0", "2026-10-15T00:00:00Z", object : BackupMaintenanceInteraction {
                override fun progress(stage: BackupMaintenanceStage) = Unit
                override suspend fun chooseProtection(): BackupLocation = error("no-backup clear must not pick output")
                override suspend fun confirm(protection: BackupLocation?): Boolean {
                    assertNull(protection)
                    assertEquals(before, f.snapshots.snapshot())
                    return true
                }
            })
        assertTrue(result.committed)
        val after = f.snapshots.snapshot()
        assertEquals(before.settings, after.settings)
        assertTrue(after.tables.values.all { it.isEmpty() })
        assertEquals(technical, f.rows(technical.keys.toList()))
        assertEquals(DataGeneration(74), f.generations.current())
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }

    private fun interaction(location: BackupLocation, confirm: suspend () -> Boolean) = object : BackupMaintenanceInteraction {
        override fun progress(stage: BackupMaintenanceStage) = Unit
        override suspend fun chooseProtection() = location
        override suspend fun confirm(protection: BackupLocation?): Boolean {
            assertEquals(location, protection)
            return confirm()
        }
    }
    private fun scenario(block: suspend (V2BackupDeviceFixture, BackupLocation) -> Unit): Unit = runBlocking {
        val fixture = V2BackupDeviceFixture()
        var uri: android.net.Uri? = null
        try {
            fixture.seed()
            val document = V2BackupDeviceFixture.newDocument().also { uri = it }
            block(fixture, BackupLocation(document.toString()))
        } finally {
            fixture.close()
            uri?.let { assertEquals(1, V2BackupDeviceFixture.context.contentResolver.delete(it, null, null)) }
        }
    }
}
