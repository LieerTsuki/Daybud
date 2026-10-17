package com.example.lifemanager.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Real Downloads provider and production-factory v6 SQLite; no picker or device-run claim. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupFilesDeviceTest {
    private val context get() = V2BackupDeviceFixture.context
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun roomSnapshotExportsReopensAndPreviewsV2WithoutChangingAnyCurrentState(): Unit = runBlocking {
        val fixture = V2BackupDeviceFixture()
        var uri: android.net.Uri? = null
        try {
            fixture.seed()
            val document = V2BackupDeviceFixture.newDocument().also { uri = it }
            val rows = fixture.rows()
            val technical = fixture.rows(V2BackupDeviceFixture.technicalTables)
            val before = fixture.snapshots.snapshot()
            val staging = stagedNames()
            context.contentResolver.openOutputStream(document, "wt")!!.use { it.write(ByteArray(32768) { 65 }) }
            val location = BackupLocation(document.toString())
            val exported = fixture.files.export(location, "1.0.0", "2026-10-15T00:00:00Z", DataGeneration(73))
            fixture.assertPayloadRows(rows, exported.payload)
            assertEquals(before.settings, exported.payload.settings)
            val readback = fixture.codec.decode(fixture.store.read(location, BackupLimits().maxBytes), zone)
            assertEquals(BackupSourceFormat.V3, readback.sourceFormat)
            assertEquals(exported, readback.document)
            val preview = fixture.files.preview(location, zone)
            assertEquals(BackupSourceFormat.V3, preview.sourceFormat)
            assertNull(preview.legacyTodoTimeZone)
            assertEquals(exported, preview.document)
            assertEquals(mapOf("plans" to 2, "tags" to 1, "plan_tag_cross_ref" to 1, "plan_exceptions" to 1,
                "plan_legacy_refs" to 2, "habits" to 1, "habit_records" to 1, "subscriptions" to 1,
                "subscription_payments" to 1, "subscription_reminders" to 1, "habit_trees" to 1, "habit_waterings" to 1), preview.rowCounts)
            assertEquals(rows, fixture.rows())
            assertEquals(technical, fixture.rows(V2BackupDeviceFixture.technicalTables))
            assertEquals(staging, stagedNames())
        } finally {
            fixture.close()
            uri?.let { assertEquals(1, context.contentResolver.delete(it, null, null)) }
        }
    }

    @Test fun corruptOrDeletedInputCannotChangeDatabaseOrLeaveStagingFiles(): Unit = runBlocking {
        val fixture = V2BackupDeviceFixture()
        var uri: android.net.Uri? = null
        try {
            fixture.seed()
            val document = V2BackupDeviceFixture.newDocument().also { uri = it }
            val before = fixture.rows(V2BackupDeviceFixture.businessTables + V2BackupDeviceFixture.technicalTables + "app_settings")
            val staging = stagedNames()
            val location = BackupLocation(document.toString())
            fixture.store.write(location, "{\"format\":\"broken\"}".toByteArray())
            assertFailsWith<BackupValidationException> { fixture.files.preview(location, zone) }
            assertEquals(before, fixture.rows(before.keys.toList()))
            assertEquals(staging, stagedNames())
            assertEquals(1, context.contentResolver.delete(document, null, null))
            uri = null
            assertFailsWith<BackupFileException> { fixture.files.preview(location, zone) }
            assertEquals(before, fixture.rows(before.keys.toList()))
            assertEquals(staging, stagedNames())
        } finally {
            fixture.close()
            uri?.let { assertEquals(1, context.contentResolver.delete(it, null, null)) }
        }
    }

    private fun stagedNames() = File(context.cacheDir, "backup-inputs").listFiles()?.map { it.name }?.toSet().orEmpty()
}
