package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.Tag
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanUseCasesTest {
    private val token = DataGeneration(4)

    @Test fun `save hands persisted identity off before reminder work`() = runTest {
        val f = Fixture()
        val events = mutableListOf<String>()
        f.effects = { id, generation ->
            assertEquals(token, generation)
            assertEquals("新的计划", f.repo.rows.getValue(id).title)
            events += "reminder"
        }
        val result = f.useCases().save(timedPlan(0).copy(title = "新的计划"), emptyList(), token) {
            assertEquals(PlanWriteReceipt(41, token), it)
            events += "committed"
        }
        assertEquals(listOf("committed", "reminder"), events)
        assertEquals(PlanWriteReceipt(41, token), result.receipt)
        assertEquals(PlanReminderUpdate.Applied, result.reminders)
        assertEquals(token, f.generations.current())
    }

    @Test fun `database failure gives neither acknowledgement nor reminder`() = runTest {
        val f = Fixture()
        f.repo.beforeWrite = { throw IllegalStateException("synthetic write failure") }
        var acknowledged = false
        assertFailsWith<IllegalStateException> {
            f.useCases().save(timedPlan(0), emptyList(), token) { acknowledged = true }
        }
        assertFalse(acknowledged)
        assertTrue(f.repo.rows.isEmpty())
        assertTrue(f.reminderIds.isEmpty())
    }

    @Test fun `reminder failure keeps saved identity and retry never reinserts`() = runTest {
        val f = Fixture()
        val failure = IllegalStateException("synthetic alarm failure")
        f.effects = { _, _ -> throw failure }
        val result = f.useCases().save(timedPlan(0), emptyList(), token) {}
        assertSame(failure, (result.reminders as PlanReminderUpdate.Failed).cause)
        assertEquals(setOf(41L), f.repo.rows.keys)
        f.effects = { _, _ -> }
        assertEquals(PlanReminderUpdate.Applied, f.useCases().retryReminders(result.receipt).reminders)
        assertEquals(1, f.repo.saveCount)
        assertEquals(listOf(41L, 41L), f.reminderIds)
    }

    @Test fun `failed handoff exposes committed receipt and skips reminders`() = runTest {
        val f = Fixture()
        val failure = IllegalStateException("synthetic publication failure")
        val error = assertFailsWith<PlanCommitHandoffException> {
            f.useCases().save(timedPlan(0), emptyList(), token) { throw failure }
        }
        assertEquals(PlanWriteReceipt(41, token), error.receipt)
        // Coroutine stack-trace recovery may copy exceptions across a context handoff.
        assertIs<IllegalStateException>(error.cause)
        assertTrue(generateSequence(error.cause) { it.cause }.any { it === failure })
        assertEquals(setOf(41L), f.repo.rows.keys)
        assertTrue(f.reminderIds.isEmpty())
    }

    @Test fun `explicit post commit cancellation remains cancellation with receipt`() = runTest {
        val f = Fixture()
        f.effects = { _, _ -> throw CancellationException("synthetic cancellation") }
        val acknowledged = mutableListOf<PlanWriteReceipt>()
        val error = assertFailsWith<PlanCommittedCancellationException> {
            f.useCases().save(timedPlan(0), emptyList(), token) { acknowledged += it }
        }
        assertEquals(PlanWriteReceipt(41, token), error.receipt)
        assertEquals(listOf(error.receipt), acknowledged)
        assertIs<CancellationException>(error.cause)
        assertEquals(setOf(41L), f.repo.rows.keys)
    }

    @Test fun `cancellation during short handoff still transfers saved receipt`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<PlanWriteReceipt>()
        val writer = async {
            f.useCases().save(timedPlan(0), emptyList(), token) {
                entered.complete(Unit)
                release.await()
                acknowledged += it
            }
        }
        entered.await()
        writer.cancel()
        runCurrent()
        assertTrue(acknowledged.isEmpty())
        release.complete(Unit)
        assertFailsWith<CancellationException> { writer.await() }
        assertEquals(listOf(PlanWriteReceipt(41, token)), acknowledged)
        assertTrue(f.reminderIds.isEmpty())
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }

    @Test fun `cancelled pre commit write acknowledges nothing`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.repo.beforeWrite = { entered.complete(Unit); awaitCancellation() }
        var acknowledged = false
        val writer = async { f.useCases().save(timedPlan(0), emptyList(), token) { acknowledged = true } }
        entered.await()
        writer.cancelAndJoin()
        assertFalse(acknowledged)
        assertTrue(f.repo.rows.isEmpty())
        assertTrue(f.reminderIds.isEmpty())
        assertEquals(MaintenanceState.IDLE, f.coordinator.state.value)
    }

    @Test fun `all write and retry paths reject stale generation without effects`() = runTest {
        val f = Fixture()
        val old = DataGeneration(3)
        val cases = f.useCases()
        assertFailsWith<StaleGenerationException> { cases.save(timedPlan(0), emptyList(), old) {} }
        assertFailsWith<StaleGenerationException> { cases.setCompleted(1, true, old) {} }
        assertFailsWith<StaleGenerationException> { cases.delete(1, old) {} }
        assertFailsWith<StaleGenerationException> { cases.saveException(PlanException(1, day("2026-01-01"), true), old) {} }
        assertFailsWith<StaleGenerationException> { cases.retryReminders(PlanWriteReceipt(1, old)) }
        assertEquals(0, f.repo.writes)
        assertTrue(f.reminderIds.isEmpty())
    }

    @Test fun `maintenance rejects mutations and never queues them`() = runTest {
        val f = Fixture()
        val cases = f.useCases()
        f.coordinator.withSession {
            assertFailsWith<MaintenanceBusyException> { cases.save(timedPlan(0), emptyList(), token) {} }
            assertFailsWith<MaintenanceBusyException> { cases.setCompleted(1, true, token) {} }
            assertFailsWith<MaintenanceBusyException> { cases.delete(1, token) {} }
            assertFailsWith<MaintenanceBusyException> { cases.saveException(PlanException(1, day("2026-01-01"), true), token) {} }
            assertFailsWith<MaintenanceBusyException> { cases.retryReminders(PlanWriteReceipt(1, token)) }
        }
        runCurrent()
        assertEquals(0, f.repo.writes)
        assertTrue(f.reminderIds.isEmpty())
    }

    @Test fun `metadata read failure is not saved or an empty success`() = runTest {
        val f = Fixture()
        f.generations.failReads = true
        assertFailsWith<IllegalStateException> { f.useCases().save(timedPlan(0), emptyList(), token) {} }
        assertEquals(0, f.repo.writes)
        assertTrue(f.reminderIds.isEmpty())
    }

    @Test fun `maintenance drains acknowledgement and reminder side effects`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.effects = { _, _ -> entered.complete(Unit); release.await() }
        val writer = async { f.useCases().save(timedPlan(0), emptyList(), token) {} }
        entered.await()
        var maintained = false
        val maintenance = async { f.coordinator.withSession { maintained = true } }
        runCurrent()
        assertEquals(MaintenanceState.DRAINING, f.coordinator.state.value)
        assertFalse(maintained)
        assertFailsWith<MaintenanceBusyException> { f.useCases().delete(41, token) {} }
        release.complete(Unit)
        writer.await(); maintenance.await()
        assertTrue(maintained)
        assertEquals(setOf(41L), f.repo.rows.keys)
    }

    @Test fun `different use case instances share module serialization`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.effects = { _, _ -> entered.complete(Unit); release.await() }
        val first = async { f.useCases().save(timedPlan(0), emptyList(), token) {} }
        entered.await()
        val second = async { f.useCases().setCompleted(41, true, token) {} }
        runCurrent()
        assertFalse(f.repo.rows.getValue(41).isCompleted)
        assertEquals(1, f.repo.writes)
        release.complete(Unit)
        first.await(); second.await()
        assertTrue(f.repo.rows.getValue(41).isCompleted)
        assertEquals(listOf(41L, 41L), f.reminderIds)
    }

    @Test fun `invalid persisted identity cannot enter effect retry`() = runTest {
        assertFailsWith<IllegalArgumentException> { PlanWriteReceipt(0, token) }
        assertFailsWith<IllegalArgumentException> { PlanWriteReceipt(-1, token) }
    }

    private class Fixture {
        val generations = MemoryGenerations()
        val coordinator = MaintenanceCoordinator(generations)
        val repo = MemoryPlans()
        val reminderIds = mutableListOf<Long>()
        var effects: suspend (Long, DataGeneration) -> Unit = { _, _ -> }
        fun useCases() = PlanUseCases(repo, coordinator, object : PlanReminderEffects {
            override suspend fun reconcile(planId: Long, expected: DataGeneration) {
                reminderIds += planId
                effects(planId, expected)
            }
        })
    }

    private class MemoryGenerations : DataGenerationRepository {
        private val value = MutableStateFlow(DataGeneration(4))
        var failReads = false
        override fun observe() = value
        override suspend fun current(): DataGeneration { check(!failReads); return value.value }
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
            check(current() == expected); mutation()
            return DataGeneration(Math.addExact(expected.value, 1)).also { value.value = it }
        }
    }

    private class MemoryPlans : PlanRepository {
        val rows = linkedMapOf<Long, Plan>()
        var writes = 0
        var saveCount = 0
        var beforeWrite: suspend () -> Unit = {}
        override fun observeAll() = MutableStateFlow(rows.values.toList())
        override fun observeTags() = MutableStateFlow(emptyList<Tag>())
        override suspend fun get(id: Long) = rows[id]
        override suspend fun getExceptions(id: Long) = emptyList<PlanException>()
        override suspend fun findLegacyPlan(source: PlanLegacySource, legacyId: Long): Long? = null
        override suspend fun save(plan: Plan, tagNames: List<String>, expected: DataGeneration): Long = cancellablePlanWriteBody {
            beforeWrite(); writes++; saveCount++
            val id = if (plan.id == 0L) 40L + saveCount else plan.id
            rows[id] = plan.copy(id = id, tagNames = tagNames)
            id
        }
        override suspend fun setCompleted(id: Long, completed: Boolean, expected: DataGeneration): Unit = cancellablePlanWriteBody {
            beforeWrite(); writes++; rows[id] = rows.getValue(id).copy(isCompleted = completed)
        }
        override suspend fun delete(id: Long, expected: DataGeneration): Unit = cancellablePlanWriteBody { beforeWrite(); writes++; rows.remove(id); Unit }
        override suspend fun saveException(value: PlanException, expected: DataGeneration): Unit = cancellablePlanWriteBody { beforeWrite(); writes++; Unit }
    }
}
