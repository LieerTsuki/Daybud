package com.example.lifemanager.data.plan

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
class PlanUseCasesRoomTest {
    private val now = Instant.parse("2026-01-01T09:00:00Z")
    private val token = DataGeneration(0)
    private fun draft() = Plan(title = "长期目标", timeZone = "UTC", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test fun `same identity deadline conversion and completion reconcile actual Room values`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), listOf("学习"), token) {}.receipt
        assertNull(f.scheduled.last().second)
        val loaded = assertNotNull(f.repo.get(receipt.planId))
        f.cases.save(loaded.copy(timeMode = PlanTimeMode.DEADLINE, dueAt = Instant.parse("2026-01-01T10:00:00Z")), loaded.tagNames, token) {}
        assertEquals(listOf(receipt.planId), f.db.plans().allEntities().map { it.id })
        assertEquals(Instant.parse("2026-01-01T09:45:00Z"), f.scheduled.last().second?.triggerAt)
        f.cases.setCompleted(receipt.planId, true, token) {}
        assertTrue(f.repo.get(receipt.planId)!!.isCompleted)
        assertNull(f.scheduled.last().second)
        f.cases.setCompleted(receipt.planId, false, token) {}
        assertFalse(f.repo.get(receipt.planId)!!.isCompleted)
        assertEquals(Instant.parse("2026-01-01T09:45:00Z"), f.scheduled.last().second?.triggerAt)
        assertEquals(listOf("学习"), f.repo.get(receipt.planId)!!.tagNames)
        assertEquals(token, f.generations.current())
    }

    @Test fun `exception completion and undo reuse shared recurrence without reanchoring`() = withDatabase { f ->
        val id = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED,
            startAt = Instant.parse("2026-01-01T10:00:00Z"), endAt = Instant.parse("2026-01-01T11:00:00Z"),
            repeatRule = ScheduleRepeatRule.DAILY, repeatInterval = 2), emptyList(), token) {}.receipt.planId
        f.cases.saveException(PlanException(id, LocalDate.of(2026, 1, 1), true), token) {}
        assertEquals(Instant.parse("2026-01-03T09:45:00Z"), f.scheduled.last().second?.triggerAt)
        f.cases.setCompleted(id, true, token) {}
        assertNull(f.scheduled.last().second)
        f.cases.setCompleted(id, false, token) {}
        assertEquals(Instant.parse("2026-01-03T09:45:00Z"), f.scheduled.last().second?.triggerAt)
        assertEquals(listOf(PlanException(id, LocalDate.of(2026, 1, 1), true)), f.repo.getExceptions(id))
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), f.repo.get(id)!!.startAt)
    }

    @Test fun `time editing disabling and removing date synchronize current qualification`() = withDatabase { f ->
        val receipt = f.cases.save(draft().copy(timeMode = PlanTimeMode.TIMED,
            startAt = Instant.parse("2026-01-01T10:00:00Z"), endAt = Instant.parse("2026-01-01T11:00:00Z")), listOf("学习"), token) {}.receipt
        val first = assertNotNull(f.scheduled.last().second)
        val current = assertNotNull(f.repo.get(receipt.planId))
        f.cases.save(current.copy(startAt = Instant.parse("2026-01-01T11:00:00Z"),
            endAt = Instant.parse("2026-01-01T12:00:00Z")), current.tagNames, token) {}
        assertEquals(Instant.parse("2026-01-01T10:45:00Z"), f.scheduled.last().second?.triggerAt)
        assertNotEquals(PlanReminderToken.from(first, token), PlanReminderToken.from(f.scheduled.last().second!!, token))
        val edited = assertNotNull(f.repo.get(receipt.planId))
        f.cases.save(edited.copy(reminderEnabled = false), edited.tagNames, token) {}
        assertNull(f.scheduled.last().second)
        f.cases.save(edited.copy(reminderEnabled = true), edited.tagNames, token) {}
        assertEquals(Instant.parse("2026-01-01T10:45:00Z"), f.scheduled.last().second?.triggerAt)
        f.cases.save(edited.copy(timeMode = PlanTimeMode.NONE), edited.tagNames, token) {}
        assertNull(f.scheduled.last().second)
        val undated = assertNotNull(f.repo.get(receipt.planId))
        assertEquals(Instant.parse("2026-01-01T11:00:00Z"), undated.startAt)
        assertEquals(listOf("学习"), undated.tagNames)
        assertEquals(listOf(receipt.planId), f.db.plans().allEntities().map { it.id })
    }

    @Test fun `alarm failure retry reads current completed record without repeating insert`() = withDatabase { f ->
        f.failEffects = true
        val acknowledgements = mutableListOf<PlanWriteReceipt>()
        val result = f.cases.save(draft(), emptyList(), token) { acknowledgements += it }
        assertIs<PlanReminderUpdate.Failed>(result.reminders)
        assertEquals(listOf(result.receipt), acknowledgements)
        f.cases.setCompleted(result.receipt.planId, true, token) {}
        f.failEffects = false
        assertEquals(PlanReminderUpdate.Applied, f.cases.retryReminders(result.receipt).reminders)
        assertEquals(1, f.db.plans().allEntities().size)
        assertTrue(f.repo.get(result.receipt.planId)!!.isCompleted)
        assertNull(f.scheduled.last().second)
    }

    @Test fun `deleted record retry cancels identity without resurrecting it`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), emptyList(), token) {}.receipt
        f.cases.delete(receipt.planId, token) {}
        assertNull(f.repo.get(receipt.planId))
        f.cases.retryReminders(receipt)
        assertTrue(f.db.plans().allEntities().isEmpty())
        assertEquals(receipt.planId, f.scheduled.last().first)
        assertNull(f.scheduled.last().second)
    }

    @Test fun `post commit handoff failure exposes actual saved ID for recovery`() = withDatabase { f ->
        val error = assertFailsWith<PlanCommitHandoffException> {
            f.cases.save(draft(), emptyList(), token) { throw IllegalStateException("synthetic handoff failure") }
        }
        assertEquals(listOf(error.receipt.planId), f.db.plans().allEntities().map { it.id })
        assertTrue(f.scheduled.isEmpty())
        f.cases.retryReminders(error.receipt)
        assertEquals(1, f.db.plans().allEntities().size)
    }

    @Test fun `real SQLite trigger rollback cannot acknowledge or schedule phantom save`() = withDatabase { f ->
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_plan BEFORE INSERT ON plans BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        var acknowledged = false
        assertFailsWith<android.database.sqlite.SQLiteException> { f.cases.save(draft(), emptyList(), token) { acknowledged = true } }
        assertFalse(acknowledged)
        assertTrue(f.db.plans().allEntities().isEmpty())
        assertTrue(f.scheduled.isEmpty())
        assertEquals(token, f.generations.current())
    }

    @Test fun `generation change refuses old draft and effect retry in real database`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), emptyList(), token) {}.receipt
        f.coordinator.withSession { session ->
            f.coordinator.withMaintenance(session) { f.generations.commit(token) {} }
        }
        val effectsBefore = f.scheduled.size
        assertFailsWith<StaleGenerationException> { f.cases.save(draft(), emptyList(), token) {} }
        assertFailsWith<StaleGenerationException> { f.cases.retryReminders(receipt) }
        assertEquals(effectsBefore, f.scheduled.size)
        assertEquals(1, f.db.plans().allEntities().size)
        assertEquals(DataGeneration(1), f.generations.current())
    }

    @Test fun `corrupt current data during effect retry is failure not no reminder`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), emptyList(), token) {}.receipt
        f.db.openHelper.writableDatabase.execSQL("UPDATE plans SET timeZone='bad/zone'")
        val count = f.scheduled.size
        val result = f.cases.retryReminders(receipt)
        assertIs<PlanReminderUpdate.Failed>(result.reminders)
        assertEquals(count, f.scheduled.size)
        assertEquals(1, f.db.plans().allEntities().size)
    }

    @Test fun `late metadata change inside transaction rolls back all stored fields`() = withDatabase { f ->
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER change_generation AFTER INSERT ON plans BEGIN UPDATE app_maintenance SET generation=1 WHERE id=1; END")
        var acknowledged = false
        assertFailsWith<StaleGenerationException> { f.cases.save(draft(), listOf("学习"), token) { acknowledged = true } }
        assertFalse(acknowledged)
        assertTrue(f.db.plans().allEntities().isEmpty())
        assertTrue(f.scheduled.isEmpty())
        assertEquals(token, f.generations.current())
    }

    @Test fun `module cleanup observes detached child and missing parent after real delete`() = withDatabase { f ->
        val parent = f.cases.save(draft(), emptyList(), token) {}.receipt.planId
        val child = f.cases.save(draft().copy(parentId = parent), emptyList(), token) {}.receipt.planId
        f.cases.delete(parent, token) {}
        assertNull(f.repo.get(parent))
        assertNull(f.repo.get(child)!!.parentId)
        assertEquals(listOf(child), f.db.plans().allEntities().map { it.id })
        assertEquals(parent, f.scheduled.last().first)
        assertNull(f.scheduled.last().second)
    }

    @Test fun `cancellation during real insert rolls back before receipt`() = withDatabase { f ->
        coroutineScope {
            var reached = false
            var acknowledged = false
            val writer = async(start = CoroutineStart.LAZY) { f.cases.save(draft(), emptyList(), token) { acknowledged = true } }
            f.sqlHook.set { sql ->
                if (sql.replace("`", "").uppercase().startsWith("INSERT OR ABORT INTO PLANS")) {
                    reached = true
                    writer.cancel()
                }
            }
            writer.start()
            assertFailsWith<CancellationException> { writer.await() }
            assertTrue(reached, "Cancellation must reach real insert, not just admission")
            assertFalse(acknowledged)
            assertTrue(f.db.plans().allEntities().isEmpty())
            assertTrue(f.scheduled.isEmpty())
            assertEquals(token, f.generations.current())
        }
    }

    @Test fun `cancelled dispatched return after real commit still acknowledges saved identity`() = withDatabase { f ->
        coroutineScope {
            f.dispatchSaveReturn = true
            val acknowledged = mutableListOf<PlanWriteReceipt>()
            var reached = false
            var observed: CancellationException? = null
            val writer = async(start = CoroutineStart.LAZY) {
                try { f.cases.save(draft(), emptyList(), token) { acknowledged += it } }
                catch (error: CancellationException) { observed = error; throw error }
            }
            f.afterSaved = { id ->
                assertNotNull(f.repo.get(id)) // Real transaction has already committed.
                reached = true
                writer.cancel()
            }
            writer.start()
            assertFailsWith<CancellationException> { writer.await() }
            assertTrue(reached)
            val stored = f.db.plans().allEntities().single()
            assertEquals(listOf(PlanWriteReceipt(stored.id, token)), acknowledged)
            assertEquals(acknowledged.single(), assertIs<PlanCommittedCancellationException>(observed).receipt)
            assertTrue(f.scheduled.isEmpty())
            f.afterSaved = {}
            f.cases.retryReminders(acknowledged.single())
            assertEquals(1, f.db.plans().allEntities().size)
        }
    }

    @Test fun `real job cancellation during handoff exposes receipt at use case boundary`() = withDatabase { f ->
        coroutineScope {
            var observed: CancellationException? = null
            val acknowledged = mutableListOf<PlanWriteReceipt>()
            val writer = async(start = CoroutineStart.LAZY) {
                val originalWriter = currentCoroutineContext().job
                try {
                    f.cases.save(draft(), emptyList(), token) {
                        acknowledged += it
                        originalWriter.cancel()
                    }
                } catch (error: CancellationException) { observed = error; throw error }
            }
            writer.start()
            assertFailsWith<CancellationException> { writer.await() }
            val receipt = assertIs<PlanCommittedCancellationException>(observed).receipt
            assertEquals(listOf(receipt), acknowledged)
            assertEquals(listOf(receipt.planId), f.db.plans().allEntities().map { it.id })
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test fun `real job cancellation during effects exposes receipt at use case boundary`() = withDatabase { f ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            val acknowledged = mutableListOf<PlanWriteReceipt>()
            var observed: CancellationException? = null
            f.effectHook = { entered.complete(Unit); neverReleased.await() }
            val writer = async {
                try { f.cases.save(draft(), emptyList(), token) { acknowledged += it } }
                catch (error: CancellationException) { observed = error; throw error }
            }
            withTimeout(5000) { entered.await() }
            writer.cancel()
            assertFailsWith<CancellationException> { writer.await() }
            val receipt = assertIs<PlanCommittedCancellationException>(observed).receipt
            assertEquals(listOf(receipt), acknowledged)
            assertEquals(listOf(receipt.planId), f.db.plans().allEntities().map { it.id })
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test fun `real job cancellation during retry exposes existing receipt without another write`() = withDatabase { f ->
        val receipt = f.cases.save(draft(), emptyList(), token) {}.receipt
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            var observed: CancellationException? = null
            f.effectHook = { entered.complete(Unit); neverReleased.await() }
            val retry = async {
                try { f.cases.retryReminders(receipt) }
                catch (error: CancellationException) { observed = error; throw error }
            }
            withTimeout(5000) { entered.await() }
            retry.cancel()
            assertFailsWith<CancellationException> { retry.await() }
            assertEquals(receipt, assertIs<PlanCommittedCancellationException>(observed).receipt)
            assertEquals(listOf(receipt.planId), f.db.plans().allEntities().map { it.id })
            assertEquals(1, f.scheduled.size)
        }
    }

    @Test fun `effect cancellation after real commit preserves row and receipt`() = withDatabase { f ->
        f.effectHook = { throw CancellationException("synthetic effects cancellation") }
        val acknowledged = mutableListOf<PlanWriteReceipt>()
        val error = assertFailsWith<PlanCommittedCancellationException> {
            f.cases.save(draft(), emptyList(), token) { acknowledged += it }
        }
        assertEquals(listOf(error.receipt), acknowledged)
        assertEquals(listOf(error.receipt.planId), f.db.plans().allEntities().map { it.id })
        assertTrue(f.scheduled.isEmpty())
    }

    @Test fun `handoff cancellation after real commit exposes committed receipt`() = withDatabase { f ->
        val error = assertFailsWith<PlanCommittedCancellationException> {
            f.cases.save(draft(), emptyList(), token) { throw CancellationException("synthetic handoff cancellation") }
        }
        assertEquals(listOf(error.receipt.planId), f.db.plans().allEntities().map { it.id })
        assertTrue(f.scheduled.isEmpty())
        f.cases.retryReminders(error.receipt)
        assertEquals(1, f.db.plans().allEntities().size)
    }

    @Test fun `real Room completion waits for effects and maintenance drains both writers`() = withDatabase { f ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.effectHook = { entered.complete(Unit); release.await() }
            val first = async { f.cases.save(draft(), emptyList(), token) {} }
            withTimeout(5000) { entered.await() }
            val id = f.db.plans().allEntities().single().id
            val secondAdmitted = CompletableDeferred<Unit>()
            f.generations.onRead = { secondAdmitted.complete(Unit) }
            val second = async { f.cases.setCompleted(id, true, token) {} }
            withTimeout(5000) { secondAdmitted.await() }
            assertFalse(f.repo.get(id)!!.isCompleted)
            var maintained = false
            val maintenance = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.withSession { maintained = true } }
            assertEquals(MaintenanceState.DRAINING, f.coordinator.state.value)
            assertFalse(maintained)
            assertFailsWith<MaintenanceBusyException> { f.cases.retryReminders(PlanWriteReceipt(id, token)) }
            release.complete(Unit)
            first.await(); second.await(); maintenance.await()
            assertTrue(maintained)
            assertTrue(f.repo.get(id)!!.isCompleted)
            assertEquals(listOf(id, id), f.scheduled.map { it.first })
        }
    }

    @Test fun `real Room delete and retry serialize behind earlier reminder effects`() = withDatabase { f ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.effectHook = { entered.complete(Unit); release.await() }
            val first = async { f.cases.save(draft().copy(timeMode = PlanTimeMode.DEADLINE,
                dueAt = Instant.parse("2026-01-01T10:00:00Z")), emptyList(), token) {} }
            withTimeout(5000) { entered.await() }
            val id = f.db.plans().allEntities().single().id
            val deleteAdmitted = CompletableDeferred<Unit>()
            f.generations.onRead = { deleteAdmitted.complete(Unit) }
            val deletion = async { f.cases.delete(id, token) {} }
            withTimeout(5000) { deleteAdmitted.await() }
            val retryAdmitted = CompletableDeferred<Unit>()
            f.generations.onRead = { retryAdmitted.complete(Unit) }
            val retry = async { f.cases.retryReminders(PlanWriteReceipt(id, token)) }
            withTimeout(5000) { retryAdmitted.await() }
            assertNotNull(f.repo.get(id))
            release.complete(Unit)
            first.await(); deletion.await(); retry.await()
            assertNull(f.repo.get(id))
            assertEquals(listOf(id, id, id), f.scheduled.map { it.first })
            assertEquals(listOf(Instant.parse("2026-01-01T09:45:00Z"), null, null), f.scheduled.map { it.second?.triggerAt })
        }
    }

    private inner class Fixture(val db: PlanMigrationTestDatabase, val sqlHook: AtomicReference<(String) -> Unit>) {
        val repo = PlanRepositoryImpl(db, db.plans(), Clock.fixed(now, ZoneOffset.UTC))
        val generations = FixtureGenerations(db)
        val coordinator = MaintenanceCoordinator(generations)
        var failEffects = false
        var effectHook: suspend () -> Unit = {}
        var afterSaved: suspend (Long) -> Unit = {}
        var dispatchSaveReturn = false
        val scheduled = mutableListOf<Pair<Long, PlanReminder?>>()
        // Only the dispatch-return boundary is substituted; all data work remains real Room.
        private val boundary = object : PlanRepository by repo {
            override suspend fun save(plan: Plan, tagNames: List<String>, expected: DataGeneration): Long {
                suspend fun saveCurrent(): Long = repo.save(plan, tagNames, expected).also { afterSaved(it) }
                return if (dispatchSaveReturn) withContext(Dispatchers.Default) { saveCurrent() } else saveCurrent()
            }
        }
        val cases = PlanUseCases(boundary, coordinator, object : PlanReminderEffects {
            override suspend fun reconcile(planId: Long, expected: DataGeneration) {
                check(generations.current() == expected)
                check(!failEffects) { "synthetic alarm failure" }
                effectHook()
                val current = repo.get(planId)
                val next = current?.let { PlanReminderRules.next(it, now, repo.getExceptions(planId)) }
                scheduled += planId to next
            }
        })
    }

    /** Fixture adapter reads the same real metadata as PlanRepositoryImpl, not a second memory token. */
    private class FixtureGenerations(private val db: PlanMigrationTestDatabase) : DataGenerationRepository {
        var onRead: suspend () -> Unit = {}
        override fun observe() = flow { emit(current()) }
        override suspend fun current(): DataGeneration = db.withTransaction {
            db.plans().read(SimpleSQLiteQuery("SELECT generation FROM app_maintenance WHERE id=1")).use {
                check(it.moveToFirst()); DataGeneration(it.getLong(0))
            }
        }.also { onRead() }
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration = db.withTransaction {
            if (current() != expected) throw StaleGenerationException()
            val next = DataGeneration(Math.addExact(expected.value, 1))
            mutation()
            check(current() == expected)
            db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1", arrayOf(next.value))
            next
        }
    }
    private fun withDatabase(block: suspend (Fixture) -> Unit): Unit = runBlocking {
        val sqlHook = AtomicReference<(String) -> Unit>({})
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlanMigrationTestDatabase::class.java)
            .addCallback(PlanV6Schema.INITIALIZE).allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { sqlHook.get()(sqlQuery) }
            }, Executor { it.run() }).build()
        try { block(Fixture(db, sqlHook)) } finally { db.close() }
    }
}
