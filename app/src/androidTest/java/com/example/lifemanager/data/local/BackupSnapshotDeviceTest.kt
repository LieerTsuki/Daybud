package com.example.lifemanager.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.backup.V2BackupDeviceFixture
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class BackupSnapshotDeviceTest {
    @Test fun v2RoundTripPreservesEverySeededColumnAndExcludesLocalTechnicalRows(): Unit = runBlocking {
        val fixture = V2BackupDeviceFixture()
        try {
            fixture.seed()
            val rows = fixture.rows()
            val metadata = fixture.rows(V2BackupDeviceFixture.technicalTables)
            val snapshot = fixture.snapshots.snapshot()
            val decoded = fixture.codec.decode(fixture.codec.encode(V2BackupDocument("1.0.0", "2026-10-15T00:00:00Z", snapshot)),
                ZoneId.of("UTC"))
            assertEquals(BackupSourceFormat.V3, decoded.sourceFormat)
            fixture.assertPayloadRows(rows, decoded.document.payload)
            assertEquals(V2BackupSettings(ThemeMode.DARK, DateFormat.DMY, "EUR", false, true, setOf(1,3,7)), decoded.document.payload.settings)
            val plans = decoded.document.payload.tables.getValue("plans")
            assertEquals(BackupValue.Integer(Long.MIN_VALUE), plans.single { it.fields["id"] == BackupValue.Integer(41) }.fields["dueAt"])
            assertEquals(BackupValue.Integer(Long.MAX_VALUE), decoded.document.payload.tables.getValue("subscriptions").single().fields["amountMinor"])
            assertEquals(BackupValue.Integer(9007199254740993), decoded.document.payload.tables.getValue("subscription_payments").single().fields["amountMinor"])
            assertEquals(rows, fixture.rows())
            assertEquals(metadata, fixture.rows(V2BackupDeviceFixture.technicalTables))
        } finally { fixture.close() }
    }
}
