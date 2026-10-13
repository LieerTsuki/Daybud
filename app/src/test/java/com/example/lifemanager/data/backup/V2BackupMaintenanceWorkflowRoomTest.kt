package com.example.lifemanager.data.backup

import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import androidx.room.withTransaction
import com.example.lifemanager.data.plan.PlanMigrationTestDatabase
import com.example.lifemanager.data.plan.PlanRepositoryImpl
import com.example.lifemanager.data.plan.RoomPlanReminderSources
import com.example.lifemanager.data.plan.RoomPlanReminderStore
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderToken
import com.example.lifemanager.notification.*
import java.io.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class V2BackupMaintenanceWorkflowRoomTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = V2BackupRoomFixtures
    private val source = BackupLocation("content://synthetic/source")
    private val protection = BackupLocation("content://synthetic/protection")
    private val date = "2026-10-13T00:00:00Z"
    private val incoming = V2BackupFixtures.document(V2BackupFixtures.empty().copy(settings = V2BackupSettings(defaultCurrency = "USD")))
    private val card = PlanReminderToken(17, DataGeneration(3), LocalDate.ofEpochDay(20999), Instant.ofEpochMilli(1000))

    @Test fun `verified v2 protection independent confirm and captured identities precede one real commit`() = withFixture { f ->
        assertEquals(BackupMaintenanceResult(true, false), f.restore())
        fixture.sameRows(incoming.payload, fixture.snapshot(f.db)); assertEquals(6, fixture.generation(f.db))
        fixture.sameRows(fixture.full(), VersionedBackupJsonCodec().decode(f.saved, ZoneId.of("UTC")).document.payload)
        assertEquals(listOf("capture-card", "snapshot", "choose", "write", "read", "confirm", "replace", "clear-reminders", "enqueue", "reconcile"), f.events)
        assertEquals(listOf(card), assertNotNull(f.cleared).planNotificationTokens)
        assertEquals(21000, assertNotNull(f.cleared).planAlarmTokens.single().occurrenceDate.toEpochDay())
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `same source uri refuses protection before opening provider output`() = withFixture { f ->
        f.destination = source
        assertFailsWith<BackupFileException> { f.restore() }
        assertFalse("write" in f.events); assertFalse("confirm" in f.events); f.unchanged()
    }
    @Test fun `provider close and readback failures cannot permit final confirmation`() = withFixture { f ->
        f.failWrite = true; assertFailsWith<BackupFileException> { f.restore() }; f.unchanged()
        f.failWrite = false; f.reopened = VersionedBackupJsonCodec().encode(incoming)
        assertFailsWith<BackupFileException> { f.restore() }; f.unchanged(); assertFalse("confirm" in f.events)
    }
    @Test fun `picker and final confirmation cancellation preserve database and release freeze`() = withFixture { f ->
        f.destination = null; assertFalse(f.restore().committed); f.unchanged()
        f.destination = protection; f.confirmed = false; assertFalse(f.restore().committed); f.unchanged()
        assertTrue(f.saved.isNotEmpty()); assertFalse("replace" in f.events)
        assertEquals(2, f.events.count { it == "enqueue" })
    }
    @Test fun `real job cancellation in picker releases session without writing or clearing reminders`() = withFixture { f ->
        coroutineScope {
            f.choiceGate = CompletableDeferred(); val task = async { f.restore() }
            try { withTimeout(5000) { f.choiceEntered.await() }; task.cancelAndJoin() }
            finally { task.cancelAndJoin() }
        }
        f.unchanged(); assertFalse("replace" in f.events); assertFalse("clear-reminders" in f.events)
        assertEquals(1, f.events.count { it == "enqueue" })
    }
    @Test fun `no backup clear still independently confirms and preserves settings technical rows and sequences`() = withFixture { f ->
        f.failSnapshot = true
        assertEquals(BackupMaintenanceResult(true, false), f.run(V2BackupMaintenanceRequest.Clear(DataGeneration(5), false)))
        val actual = fixture.snapshot(f.db)
        assertEquals(fixture.full().settings, actual.settings); assertTrue(actual.tables.values.all { it.isEmpty() })
        assertFalse("snapshot" in f.events); assertFalse("choose" in f.events); assertEquals(6, fixture.generation(f.db))
        assertEquals(1, fixture.technical(f.db).size)
        assertEquals(9007199254740993L, fixture.scalar(f.db, "SELECT seq FROM sqlite_sequence WHERE name='plans'"))
    }
    @Test fun `clear with protection cancellation never becomes unconfirmed no backup clear`() = withFixture { f ->
        f.confirmed = false; assertFalse(f.run(V2BackupMaintenanceRequest.Clear(DataGeneration(5), true)).committed)
        assertTrue(f.saved.isNotEmpty()); f.unchanged()
    }
    @Test fun `invalid document or stale request cannot touch provider or database`() = withFixture { f ->
        assertFailsWith<BackupValidationException> { f.restore(incoming.copy(payload = incoming.payload.copy(tables = emptyMap()))) }
        assertTrue(f.events.isEmpty()); f.unchanged()
        assertFailsWith<StaleGenerationException> { f.run(V2BackupMaintenanceRequest.Restore(DataGeneration(4), source, incoming)) }
        assertEquals(listOf("enqueue"), f.events); f.unchanged()
    }
    @Test fun `sql fault after delete rolls back data preferences generation inventory and sequence`() = withFixture { f ->
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advance BEFORE UPDATE OF generation ON app_maintenance BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
        assertFailsWith<Exception> { f.restore() }
        f.unchanged(); assertFalse("clear-reminders" in f.events); assertTrue("enqueue" in f.events)
    }
    @Test fun `notification enumeration failure aborts preparation without treating missing service as empty`() = withFixture { f ->
        f.failCapture = true; assertFailsWith<IllegalStateException> { f.restore() }
        f.unchanged(); assertEquals(listOf("capture-card", "enqueue"), f.events)
    }
    @Test fun `cleanup reconciliation and enqueue failures report committed data as reminders pending`() = withFixture { f ->
        f.failCleanup = true; f.failReconcile = true; f.failEnqueue = true
        assertEquals(BackupMaintenanceResult(true, true), f.restore())
        assertEquals(6, fixture.generation(f.db)); fixture.sameRows(incoming.payload, fixture.snapshot(f.db))
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `restore owns nested caller collections across picker suspension`() = withFixture { f ->
        val original = V2BackupFixtures.document(fixture.full())
        val first = original.payload.tables.getValue("plans").first().fields.toMutableMap()
        val rows = original.payload.tables.getValue("plans").toMutableList().apply { this[0] = BackupRow(first) }
        val tables = original.payload.tables.toMutableMap().apply { this["plans"] = rows }
        val days = original.payload.settings.defaultReminderDays.toMutableSet()
        val mutable = original.copy(payload = original.payload.copy(tables = tables, settings = original.payload.settings.copy(defaultReminderDays = days)))
        coroutineScope {
            f.choiceGate = CompletableDeferred(); val task = async { f.restore(mutable) }
            try {
                withTimeout(5000) { f.choiceEntered.await() }; first["title"] = BackupValue.Text("caller changed")
                rows.removeAt(1); tables.remove("tags"); days.clear(); f.choiceGate!!.complete(Unit)
                assertTrue(task.await().committed)
            } finally { task.cancelAndJoin() }
        }
        fixture.sameRows(original.payload, fixture.snapshot(f.db)); assertEquals(6, fixture.generation(f.db))
    }
    @Test fun `maintenance drains admitted writes then freezes picker without a db transaction`() = withFixture { f ->
        coroutineScope {
            val admitted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val ordinary = async { f.coordinator.run(DataGeneration(5)) { admitted.complete(Unit); release.await() } }
            admitted.await(); f.choiceGate = CompletableDeferred(); val task = async { f.restore() }
            try {
                yield(); assertEquals(MaintenanceState.DRAINING, f.coordinator.state.value)
                assertTrue(f.events.isEmpty()); assertFailsWith<MaintenanceBusyException> { f.coordinator.capture() }
                release.complete(Unit); ordinary.await(); withTimeout(5000) { f.choiceEntered.await() }
                assertFalse(f.db.inTransaction()); assertEquals(MaintenanceState.READY, f.coordinator.state.value)
                assertFailsWith<MaintenanceBusyException> { f.coordinator.run(DataGeneration(5)) { error("must reject") } }
                f.choiceGate!!.complete(Unit); assertTrue(task.await().committed)
            } finally { release.complete(Unit); ordinary.cancelAndJoin(); task.cancelAndJoin() }
        }
    }
    @Test fun `actual cancellation before Room commit rolls back and is not reported as committed`() = withFixture { f ->
        // Cancel the actual transaction operation, not merely its asynchronously returning caller.
        // This is the same precise fault boundary exercised by D3's real Job cancellation test.
        val operation = Job(); f.mutationJob = operation; var reached = false
        f.readHook = { if (fixture.generation(f.db) == 6L) { reached = true; operation.cancel() } }
        val observed = assertFailsWith<CancellationException> { f.restore() }
        assertTrue(reached); assertFalse(observed is BackupCommittedCancellationException)
        f.readHook = {}; f.unchanged(); assertFalse("clear-reminders" in f.events); assertTrue("enqueue" in f.events)
    }
    @Test fun `real job cancellation after persisted commit preserves committed marker and pending cleanup`() = withFixture { f ->
        coroutineScope {
            var observed: CancellationException? = null
            val task = async(start = CoroutineStart.LAZY) { try { f.restore() } catch (e: CancellationException) { observed = e; throw e } }
            f.afterMutation = { assertEquals(6, fixture.generation(f.db)); task.cancel(); yield() }
            task.start(); assertFailsWith<CancellationException> { task.await() }; task.join()
            assertIs<BackupCommittedCancellationException>(observed)
        }
        assertEquals(6, fixture.generation(f.db)); fixture.sameRows(incoming.payload, fixture.snapshot(f.db))
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value); assertTrue("enqueue" in f.events)
    }
    @Test fun `original caller cancellation at final SQL read prevents commit across maintenance scopes`() = withFixture { f ->
        var observed: CancellationException? = null
        coroutineScope {
            var reached = false
            val task = async(start = CoroutineStart.LAZY) {
                try { f.restore() } catch (e: CancellationException) { observed = e; throw e }
            }
            f.readHook = { if (fixture.generation(f.db) == 6L) { reached = true; task.cancel() } }
            task.start(); assertFailsWith<CancellationException> { task.await() }; task.join()
            assertTrue(reached); assertNotNull(observed)
        }
        f.readHook = {}
        f.unchanged(); assertFalse(observed is BackupCommittedCancellationException); assertFalse("clear-reminders" in f.events)
    }
    @Test fun `outer dispatcher return cancellation does not erase committed evidence`() = withFixture { f ->
        coroutineScope {
            val armed = AtomicBoolean(); var observed: CancellationException? = null
            val caller = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (armed.get()) context[Job]!!.cancel()
                    Dispatchers.Default.dispatch(context, block)
                }
            }
            f.dispatcher = Dispatchers.IO; f.reconcileHook = { armed.set(true) }
            val task = async(caller) { try { f.restore() } catch (e: CancellationException) { observed = e; throw e } }
            assertFailsWith<CancellationException> { task.await() }; task.join()
            assertIs<BackupCommittedCancellationException>(observed)
        }
        assertEquals(6, fixture.generation(f.db)); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `unreadable same database generation after mutation is unknown not proof of rollback`() = withFixture { f ->
        f.afterMutation = { f.failGeneration = true; error("synthetic lost return") }
        assertFailsWith<BackupCommitOutcomeUnknownException> { f.restore() }
        f.failGeneration = false; assertEquals(6, fixture.generation(f.db)); fixture.sameRows(incoming.payload, fixture.snapshot(f.db))
        assertEquals(1, f.events.count { it == "replace" }); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `cancelled unreadable commit outcome has a separate uncertainty marker`() = withFixture { f ->
        coroutineScope {
            var observed: CancellationException? = null
            val task = async(start = CoroutineStart.LAZY) { try { f.restore() } catch (e: CancellationException) { observed = e; throw e } }
            f.afterMutation = { f.failGeneration = true; task.cancel(); throw CancellationException("synthetic lost committed return") }
            task.start(); assertFailsWith<CancellationException> { task.await() }; task.join()
            assertIs<BackupCommitOutcomeUnknownCancellationException>(observed)
        }
        f.failGeneration = false; assertEquals(6, fixture.generation(f.db)); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `readable unexpected generation cannot be treated as proof of rollback`() = withFixture { f ->
        f.afterMutation = {
            f.db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=7")
            error("synthetic unexpected generation at lost return")
        }
        assertFailsWith<BackupCommitOutcomeUnknownException> { f.restore() }
        assertEquals(7, fixture.generation(f.db)); assertEquals(1, f.events.count { it == "replace" })
        fixture.sameRows(incoming.payload, fixture.snapshot(f.db)); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `cancelled readable unexpected generation is uncertainty not ordinary cancellation`() = withFixture { f ->
        coroutineScope {
            var observed: CancellationException? = null
            val task = async(start = CoroutineStart.LAZY) { try { f.restore() } catch (e: CancellationException) { observed = e; throw e } }
            f.afterMutation = {
                f.db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=7")
                task.cancel(); throw CancellationException("synthetic lost committed return")
            }
            task.start(); assertFailsWith<CancellationException> { task.await() }; task.join()
            assertIs<BackupCommitOutcomeUnknownCancellationException>(observed)
        }
        assertEquals(7, fixture.generation(f.db)); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }
    @Test fun `real staged legacy file converts previews protects and restores target database`() = withFixture { f ->
        f.sourceBytes = BackupJsonCodec().encode(BackupDocument("0.1.1", date, BackupFixtures.fullPayload()))
        val preview = V2BackupFileUseCases(f.snapshots, VersionedBackupJsonCodec(), f.files, f.coordinator, Dispatchers.Unconfined)
            .preview(source, ZoneId.of("Asia/Tokyo"))
        assertEquals(BackupSourceFormat.V1, preview.sourceFormat); assertEquals("Asia/Tokyo", preview.legacyTodoTimeZone)
        assertTrue(f.staging.listFiles()!!.isEmpty()); assertEquals(12, preview.rowCounts.values.sum())
        assertTrue(f.restore(preview.document).committed)
        val payload = fixture.snapshot(f.db)
        assertEquals(listOf(BackupValue.Integer(1), BackupValue.Integer(2)), payload.tables.getValue("plans").map { it.fields.getValue("id") })
        assertEquals(BackupValue.Text("  旧待办\n\"🏠\"  "), payload.tables.getValue("plans")[0].fields["title"])
        assertEquals(BackupValue.Text("Asia/Tokyo"), payload.tables.getValue("plans")[0].fields["timeZone"])
        assertEquals(2, payload.tables.getValue("plan_legacy_refs").size)
        assertEquals(BackupValue.Integer(Long.MAX_VALUE), payload.tables.getValue("subscriptions").single().fields["amountMinor"])
        fixture.sameRows(fixture.full(), VersionedBackupJsonCodec().decode(f.saved, ZoneOffset.UTC).document.payload)
        assertEquals(6, fixture.generation(f.db))
    }
    @Test fun `reconciliation timeout is pending after committed data and released session`() = withFixture { f ->
        f.reconcileHook = { awaitCancellation() }
        assertEquals(BackupMaintenanceResult(true, true), withTimeout(12_000) { f.restore() })
        assertEquals(6, fixture.generation(f.db)); assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
        assertEquals(1, f.events.count { it == "enqueue" })
    }
    @Test fun `real notification capture and unified cleanup preserve new generation same id card`() = withFixture { f ->
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(PlanNotificationHelper.CHANNEL, "synthetic", 3))
        fun post(token: PlanReminderToken) {
            val metadata = PlanReminderIntents.metadata(android.content.Intent(), token)
            manager.notify(PlanReminderIdentity.data(token), 0, android.app.Notification.Builder(context, PlanNotificationHelper.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("synthetic test card").addExtras(metadata.extras).build())
        }
        post(card)
        val newer = card.copy(generation = DataGeneration(6))
        val notifications = PlanNotificationHelper(context)
        f.notificationSource = PlanBackupNotificationSource(notifications)
        val clock = Clock.fixed(Instant.parse(date), ZoneOffset.UTC)
        val scheduler = PlanReminderScheduler(context, PlanRepositoryImpl(f.db, f.db.plans(), clock), f.generations,
            RoomPlanReminderSources(f.db.plans()), RoomPlanReminderStore(f.db.reminders()), notifications, clock)
        val subscriptions = mutableListOf<Long>()
        f.cleanupHook = { identities ->
            UnifiedMaintenanceReminderEffects(context, scheduler, notifications, LegacyPlanReminderCleaner(context),
                object : SubscriptionReminderSchedulerContract {
                    override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) { subscriptions += subscriptionId }
                    override fun cancelAll(subscriptionId: Long) = error("must not use broad cancelAll")
                }, object : UnifiedReminderReconciliation { override suspend fun reconcile(resetSystem: Boolean) = Unit }, {}).clearPrevious(identities)
        }
        f.afterMutation = { post(newer) }
        assertTrue(f.restore().committed)
        assertEquals(listOf(card), assertNotNull(f.cleared).planNotificationTokens)
        assertEquals(listOf(newer), notifications.tokens()); assertEquals(listOf(8L), subscriptions)
        assertTrue(fixture.technical(f.db).isEmpty())
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = fixture.withDatabase { db ->
        fixture.seed(db); fixture.inventory(db); block(Fixture(db))
    }
    private inner class Fixture(val db: PlanMigrationTestDatabase) : BackupMaintenanceInteraction {
        val events = mutableListOf<String>(); var saved = byteArrayOf(); var reopened: ByteArray? = null
        var destination: BackupLocation? = protection; var confirmed = true
        var failWrite = false; var failSnapshot = false; var failCapture = false; var failGeneration = false
        var failCleanup = false; var failReconcile = false; var failEnqueue = false
        var choiceGate: CompletableDeferred<Unit>? = null; val choiceEntered = CompletableDeferred<Unit>()
        var afterMutation: suspend () -> Unit = {}; var reconcileHook: suspend () -> Unit = {}
        var cleanupHook: suspend (ReminderIdentities) -> Unit = {}
        var notificationSource = BackupPlanNotificationSource { listOf(card) }
        var sourceBytes = byteArrayOf(); val staging = temporary.newFolder()
        var readHook: () -> Unit = {}; var dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        var mutationJob: Job? = null
        var cleared: ReminderIdentities? = null
        val generations = object : DataGenerationRepository {
            override fun observe() = flow { emit(current()) }
            override suspend fun current(): DataGeneration {
                if (failGeneration) error("synthetic unreadable generation")
                return db.withTransaction { V2BackupSql(db.backup(), BackupLimits()).generation() }
            }
            override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration = error("D3 repository commits atomically")
        }
        val coordinator = MaintenanceCoordinator(generations)
        val snapshots = object : V2BackupRepository {
            override suspend fun snapshot(): V2BackupPayload { events += "snapshot"; if (failSnapshot) throw BackupValidationException("synthetic too large"); return fixture.snapshot(db) }
        }
        val rawDao = object : BackupSnapshotDao {
            override fun read(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor { readHook(); return db.backup().read(query) }
        }
        val mutations = object : V2BackupMutationRepository {
            val real = V2BackupMutationRepositoryImpl(db, rawDao)
            override suspend fun replace(expected: DataGeneration, payload: V2BackupPayload): DataGeneration {
                events += "replace"
                val next = mutationJob?.let { withContext(it + Dispatchers.IO) { real.replace(expected, payload) } } ?: real.replace(expected, payload)
                afterMutation(); return next
            }
            override suspend fun clearBusinessData(expected: DataGeneration): DataGeneration {
                events += "clear"; return real.clearBusinessData(expected).also { afterMutation() }
            }
        }
        val files = AndroidBackupFileStore(object : BackupStreamAccess {
            override fun openWrite(location: BackupLocation): OutputStream {
                assertEquals(MaintenanceState.READY, coordinator.state.value); assertFalse(db.inTransaction()); events += "write"
                assertEquals(protection, location)
                return object : ByteArrayOutputStream() {
                    override fun close() { if (failWrite) throw IOException("synthetic close failure"); saved = toByteArray(); super.close() }
                }
            }
            override fun openRead(location: BackupLocation): InputStream {
                if (location == source) {
                    assertEquals(MaintenanceState.IDLE, coordinator.state.value); assertFalse(db.inTransaction())
                    return ByteArrayInputStream(sourceBytes)
                }
                assertEquals(MaintenanceState.READY, coordinator.state.value); assertFalse(db.inTransaction()); events += "read"
                return ByteArrayInputStream(reopened ?: saved)
            }
        }, staging, Dispatchers.Unconfined)
        val capture = BackupReminderCapture {
            val source = BackupPlanNotificationSource {
                events += "capture-card"; assertFalse(db.inTransaction())
                assertEquals(MaintenanceState.RUNNING, coordinator.state.value)
                if (failCapture) error("synthetic notification enumeration failure")
                notificationSource.tokens()
            }
            V2BackupReminderCaptureImpl(db, db.backup(), source).snapshot()
        }
        val reminders = object : MaintenanceReminderEffects {
            override suspend fun clearPrevious(identities: ReminderIdentities) {
                assertEquals(MaintenanceState.RUNNING, coordinator.state.value); assertFalse(db.inTransaction())
                assertEquals(6, fixture.generation(db)); assertEquals(1, fixture.technical(db).size)
                cleared = identities; events += "clear-reminders"; if (failCleanup) error("synthetic cleanup failure")
                cleanupHook(identities)
            }
            override fun requestReconciliation() {
                assertEquals(MaintenanceState.IDLE, coordinator.state.value); events += "enqueue"; if (failEnqueue) error("synthetic enqueue failure")
            }
            override suspend fun reconcile() {
                assertEquals(MaintenanceState.IDLE, coordinator.state.value); assertFalse(db.inTransaction()); events += "reconcile"
                if (failReconcile) error("synthetic reconciliation failure"); reconcileHook()
            }
        }
        override fun progress(stage: BackupMaintenanceStage) = Unit
        override suspend fun chooseProtection(): BackupLocation? {
            assertEquals(MaintenanceState.READY, coordinator.state.value); assertFalse(db.inTransaction())
            events += "choose"; choiceEntered.complete(Unit); choiceGate?.await(); return destination
        }
        override suspend fun confirm(protection: BackupLocation?): Boolean {
            assertEquals(MaintenanceState.READY, coordinator.state.value); assertFalse(db.inTransaction()); events += "confirm"; return confirmed
        }
        suspend fun restore(value: V2BackupDocument = incoming) = run(V2BackupMaintenanceRequest.Restore(DataGeneration(5), source, value))
        suspend fun run(request: V2BackupMaintenanceRequest): BackupMaintenanceResult {
            return V2BackupMaintenanceWorkflow(snapshots, mutations,
                V2BackupFileUseCases(snapshots, VersionedBackupJsonCodec(), files, coordinator, dispatcher),
                generations, coordinator, capture, reminders, dispatcher).execute(request, "1.0.0", date, this)
        }
        suspend fun unchanged() {
            fixture.sameRows(fixture.full(), fixture.snapshot(db)); assertEquals(5, fixture.generation(db))
            assertEquals(1, fixture.technical(db).size); assertEquals(MaintenanceState.IDLE, coordinator.state.value)
            assertEquals(9007199254740993L, fixture.scalar(db, "SELECT seq FROM sqlite_sequence WHERE name='plans'"))
        }
    }
}
