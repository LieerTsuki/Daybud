package com.example.lifemanager.domain.backup

import com.example.lifemanager.data.backup.*
import com.example.lifemanager.domain.maintenance.*
import java.io.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class V2BackupFileUseCasesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val destination = BackupLocation("content://synthetic/output")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val document = CurrentBackupFixtures.document()
    private val codec = VersionedBackupJsonCodec()

    @Test fun `export writes v2 closes and independently rereads all raw fields`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertEquals(document, f.cases.export(destination, document.appVersion, document.exportedAt, DataGeneration(5)))
        assertEquals(document, codec.decode(f.streams.saved, zone).document)
        assertEquals(listOf("snapshot", "open-write", "close-write", "open-read", "close-read"), f.events)
        assertEquals(DataGeneration(5), f.generations.current())
    }
    @Test fun `different valid v2 output cannot stand in for exported document`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        f.streams.reopened = codec.encode(document.copy(appVersion = "different"))
        assertFailsWith<BackupFileException> { f.cases.writeVerified(destination, document) }
    }
    @Test fun `converted equivalent v1 output is not accepted as v2 protection`() = runTest {
        val old = BackupJsonCodec().encode(BackupDocument("0.1.1", document.exportedAt, BackupFixtures.fullPayload()))
        val converted = codec.decode(old, zone).document
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.reopened = old
        assertFailsWith<BackupFileException> { f.cases.writeVerified(destination, converted) }
    }
    @Test fun `converted equivalent old v2 cannot substitute current forest protection`() = runTest {
        val old = V2BackupJsonCodec().encode(V2BackupFixtures.document())
        val converted = codec.decode(old, zone).document
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.reopened = old
        assertFailsWith<BackupFileException> { f.cases.writeVerified(destination, converted) }
    }
    @Test fun `truncated output rejects and close failure never attempts readback`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.reopened = byteArrayOf(123)
        assertFailsWith<BackupValidationException> { f.cases.writeVerified(destination, document) }
        f.events.clear(); f.streams.failClose = true
        assertFailsWith<BackupFileException> { f.cases.writeVerified(destination, document) }
        assertFalse("open-read" in f.events)
    }
    @Test fun `invalid snapshot and byte limit reject before opening destination`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.payload = document.payload.copy(tables = emptyMap())
        assertFailsWith<BackupValidationException> { f.cases.export(destination, document.appVersion, document.exportedAt, null) }
        assertEquals(listOf("snapshot"), f.events)
        val small = Fixture(UnconfinedTestDispatcher(testScheduler), BackupLimits(maxBytes = 64))
        assertFailsWith<BackupValidationException> { small.cases.writeVerified(destination, document) }
        assertTrue(small.events.isEmpty())
    }
    @Test fun `preview stages v1 then returns converted real counts and timezone provenance`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        f.streams.reopened = BackupJsonCodec().encode(BackupDocument("0.1.1", document.exportedAt, BackupFixtures.fullPayload()))
        val preview = f.cases.preview(destination, zone)
        assertEquals(BackupSourceFormat.V1, preview.sourceFormat)
        assertEquals("Asia/Shanghai", preview.legacyTodoTimeZone)
        assertEquals(mapOf("plans" to 2, "tags" to 1, "plan_tag_cross_ref" to 1, "plan_exceptions" to 1,
            "plan_legacy_refs" to 2, "habits" to 1, "habit_records" to 1, "subscriptions" to 1,
            "subscription_payments" to 1, "subscription_reminders" to 1, "habit_trees" to 1, "habit_waterings" to 0), preview.rowCounts)
        assertEquals(BackupValue.Text("  旧待办\n\"🏠\"  "), preview.document.payload.tables.getValue("plans").first().fields["title"])
        assertEquals(listOf("open-read", "close-read"), f.events)
        assertTrue(f.staging.listFiles()!!.isEmpty())
    }
    @Test fun `native v2 preview has no invented legacy description and preserves extreme longs`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.reopened = codec.encode(document)
        val preview = f.cases.preview(destination, zone)
        assertEquals(document, preview.document); assertEquals(BackupSourceFormat.V3, preview.sourceFormat)
        assertNull(preview.legacyTodoTimeZone); assertTrue(f.staging.listFiles()!!.isEmpty())
    }
    @Test fun `invalid and oversized unknown length input cleans private stage without touching database`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.reopened = "broken".toByteArray()
        assertFailsWith<BackupValidationException> { f.cases.preview(destination, zone) }
        assertTrue(f.staging.listFiles()!!.isEmpty())
        val small = Fixture(UnconfinedTestDispatcher(testScheduler), BackupLimits(maxBytes = 64))
        small.streams.reopened = ByteArray(65)
        assertFailsWith<BackupValidationException> { small.cases.preview(destination, zone) }
        assertTrue(small.staging.listFiles()!!.isEmpty()); assertEquals(DataGeneration(5), small.generations.current())
    }
    @Test fun `permission failure is not successful preview or verified output`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler)); f.streams.failRead = true
        assertFailsWith<BackupFileException> { f.cases.preview(destination, zone) }
        assertTrue(f.staging.listFiles()!!.isEmpty())
        assertFailsWith<BackupFileException> { f.cases.writeVerified(destination, document) }
    }
    @Test fun `old generation and active maintenance refuse export before snapshot or provider`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertFailsWith<StaleGenerationException> { f.cases.export(destination, document.appVersion, document.exportedAt, DataGeneration(4)) }
        f.maintenance.withSession {
            assertFailsWith<MaintenanceBusyException> { f.cases.export(destination, document.appVersion, document.exportedAt, null) }
        }
        assertTrue(f.events.isEmpty())
    }
    @Test fun `slow provider output does not hold ordinary database admission`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slowFiles = object : BackupFileStore by f.files {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) {
                entered.complete(Unit); release.await(); f.files.write(location, bytes)
            }
        }
        val cases = V2BackupFileUseCases(f.repository, codec, slowFiles, f.maintenance, UnconfinedTestDispatcher(testScheduler), BackupLimits())
        val export = async { cases.export(destination, document.appVersion, document.exportedAt, DataGeneration(5)) }
        try {
            entered.await(); f.maintenance.withSession { assertEquals(MaintenanceState.READY, f.maintenance.state.value) }
        } finally { release.complete(Unit) }
        assertEquals(document, export.await())
    }
    @Test fun `write owns source document while provider suspends and cancellation never reports success`() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler))
        val fields = document.payload.tables.getValue("plans").first().fields.toMutableMap()
        val mutable = document.copy(payload = document.payload.table("plans", listOf(BackupRow(fields), document.payload.tables.getValue("plans")[1])))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slow = object : BackupFileStore by f.files {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) {
                entered.complete(Unit); release.await(); f.files.write(location, bytes)
            }
        }
        val cases = V2BackupFileUseCases(f.repository, codec, slow, f.maintenance, UnconfinedTestDispatcher(testScheduler), BackupLimits())
        val task = async { cases.writeVerified(destination, mutable) }
        entered.await(); fields["title"] = BackupValue.Text("caller changed after validation"); release.complete(Unit)
        assertEquals(document, task.await()); assertEquals(document, codec.decode(f.streams.saved, zone).document)
        val writeEntered = CompletableDeferred<Unit>()
        val blocked = object : BackupFileStore by f.files {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) { writeEntered.complete(Unit); awaitCancellation() }
        }
        f.events.clear()
        val cancel = async { V2BackupFileUseCases(f.repository, codec, blocked, f.maintenance, UnconfinedTestDispatcher(testScheduler), BackupLimits()).writeVerified(destination, document) }
        writeEntered.await(); cancel.cancel()
        assertFailsWith<CancellationException> { cancel.await() }; cancel.join()
        assertTrue(f.events.isEmpty()); assertEquals(DataGeneration(5), f.generations.current())
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher, limits: BackupLimits = BackupLimits()) {
        val events = mutableListOf<String>()
        var payload = document.payload
        val generations = object : DataGenerationRepository {
            private val value = MutableStateFlow(DataGeneration(5))
            override fun observe() = value
            override suspend fun current() = value.value
            override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration = error("file flow must not write database")
        }
        val maintenance = MaintenanceCoordinator(generations)
        val repository = object : V2BackupRepository { override suspend fun snapshot(): V2BackupPayload { events += "snapshot"; return payload } }
        val staging = temporary.newFolder()
        val streams = Streams(events)
        val files = AndroidBackupFileStore(streams, staging, dispatcher, limits)
        val cases = V2BackupFileUseCases(repository, VersionedBackupJsonCodec(limits), files, maintenance, dispatcher, limits)
    }
    private class Streams(private val events: MutableList<String>) : BackupStreamAccess {
            var saved = byteArrayOf(); var reopened: ByteArray? = null; var failClose = false; var failRead = false
            override fun openWrite(location: BackupLocation): OutputStream {
                events += "open-write"
                return object : ByteArrayOutputStream() {
                    override fun close() { events += "close-write"; if (failClose) throw IOException("synthetic close failure"); saved = toByteArray(); super.close() }
                }
            }
            override fun openRead(location: BackupLocation): InputStream {
                events += "open-read"; if (failRead) throw SecurityException("synthetic denied read")
                return object : ByteArrayInputStream(reopened ?: saved) {
                    override fun available() = 0
                    override fun close() { events += "close-read"; super.close() }
                }
            }
    }
}
