package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.data.backup.*
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class V1BackupSettingsViewModelTest {
    @Test fun `cancel before queued worker entry releases busy state`() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val world=World(dispatcher)
        try {
            world.model.start(BackupAction.CLEAR,world.token)
            world.model.cancel(world.id())
            advanceUntilIdle()
            assertFalse(world.model.uiState.value.busy)
            assertEquals(V1BackupOutcome.UNCHANGED,world.model.uiState.value.outcome)
            assertEquals(0,world.commits)
            assertTrue(world.saved.isEmpty());assertEquals(MaintenanceState.IDLE,world.coordinator.state.value)
            world.model.start(BackupAction.EXPORT,world.token);advanceUntilIdle()
            assertEquals(BackupStep.EXPORT_NOTICE,world.model.uiState.value.step)
        } finally { world.model.viewModelScope.cancel();advanceUntilIdle();Dispatchers.resetMain() }
    }
    @Test fun `export requires privacy consent writes verified v2 and never mutates`() = scenario {
        model.start(BackupAction.EXPORT, token); advanceUntilIdle()
        assertEquals(BackupStep.EXPORT_NOTICE, model.uiState.value.step)
        assertTrue(saved.isEmpty())
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/export"); advanceUntilIdle()
        assertEquals(V1BackupOutcome.EXPORTED, model.uiState.value.outcome)
        assertEquals(original, codec.decode(saved.values.single(), ZoneId.of("UTC")).document.payload)
        assertEquals(0, commits)
    }
    @Test fun `restore preview is real v2 with protection and independent final consent`() = scenario {
        preview()
        assertEquals(BackupSourceFormat.V2, model.uiState.value.sourceFormat)
        assertEquals(2, model.uiState.value.rowCounts["plans"])
        assertEquals(0, commits)
        protect()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        assertEquals(0, commits)
        val operation = id()
        model.answerFinal(operation, true); model.answerFinal(operation, true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(imported, payload)
        assertEquals(V1BackupOutcome.COMMITTED, model.uiState.value.outcome)
        model.answerFinal(operation, true); model.outputSelected(operation, "content://test/late"); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }
    @Test fun `legacy preview describes conversion timezone and actual unified row counts`() = scenario {
        source = BackupJsonCodec().encode(BackupDocument("0.1.1", "2026-10-14T00:00:00Z", BackupFixtures.fullPayload()))
        preview()
        assertEquals(BackupSourceFormat.V1, model.uiState.value.sourceFormat)
        assertEquals("Asia/Shanghai", model.uiState.value.legacyTodoTimeZone)
        assertEquals(2, model.uiState.value.rowCounts["plans"])
        assertFalse(model.uiState.value.rowCounts.containsKey("todos"))
        protect(); model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(BackupValue.Text("  旧待办\n\"🏠\"  "), payload.tables.getValue("plans").first().fields["title"])
    }
    @Test fun `cancel final confirmation preserves data generation and releases freeze`() = scenario {
        preview(); protect(); model.answerFinal(id(), false); advanceUntilIdle()
        assertEquals(original, payload); assertEquals(0, commits)
        assertEquals(token, generations.current()); assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(V1BackupOutcome.UNCHANGED, model.uiState.value.outcome)
    }
    @Test fun `invalid source fails before freeze protection or mutation`() = scenario {
        source = "{}".toByteArray(); preview(expectPreview = false)
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertTrue(saved.isEmpty()); assertEquals(0, commits)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }
    @Test fun `failed verification cannot reach final confirmation`() = scenario {
        preview(); wrongReadback = true
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/protection"); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, commits); assertEquals(original, payload)
    }
    @Test fun `source is never overwritten as protection`() = scenario {
        preview(); model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/source"); advanceUntilIdle()
        assertTrue(saved.isEmpty()); assertEquals(0, commits)
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
    }
    @Test fun `old callbacks cannot satisfy a new operation or a new process owner`() = scenario {
        model.start(BackupAction.RESTORE, token); advanceUntilIdle(); val old = id()
        model.cancel(old); advanceUntilIdle(); model.start(BackupAction.RESTORE, token); advanceUntilIdle()
        model.sourceSelected(old, "content://test/source"); model.answerFirst(old, true); model.answerFinal(old, true)
        advanceUntilIdle(); assertEquals(BackupStep.PICK_SOURCE, model.uiState.value.step)
        model.viewModelScope.cancel(); advanceUntilIdle(); model = newModel()
        model.answerFinal(old, true); assertEquals(BackupStep.IDLE, model.uiState.value.step)
        assertEquals(0, commits)
    }
    @Test fun `duplicate starts and wrong phase answers do not skip consent`() = scenario {
        model.start(BackupAction.RESTORE, token); advanceUntilIdle(); val operation = id()
        model.start(BackupAction.CLEAR, token); model.answerFirst(operation, true); model.answerFinal(operation, true)
        model.sourceSelected(operation, "content://test/source"); advanceUntilIdle()
        assertEquals(BackupStep.RESTORE_PREVIEW, model.uiState.value.step)
        assertEquals(operation, id()); assertEquals(0, commits)
    }
    @Test fun `original generation is not retagged after preview or picker`() = scenario {
        preview(); generations.commit(token) {}
        model.answerFirst(id(), true); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, commits); assertTrue(saved.isEmpty())
        model.start(BackupAction.CLEAR, token); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step); assertEquals(0, commits)
    }
    @Test fun `active maintenance rejects a start without replay`() = scenario {
        coordinator.withSession {
            model.start(BackupAction.CLEAR, token); advanceUntilIdle()
            assertEquals(BackupStep.IDLE, model.uiState.value.step)
        }
        advanceUntilIdle(); assertEquals(BackupStep.IDLE, model.uiState.value.step); assertEquals(0, commits)
    }
    @Test fun `clear without backup still requires final consent preserves settings`() = scenario {
        clearToFinal(); assertTrue(saved.isEmpty()); assertEquals(0, commits)
        model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits); assertTrue(payload.tables.values.all { it.isEmpty() })
        assertEquals(original.settings, payload.settings)
    }
    @Test fun `picker failure and owner destruction release freeze without mutation`() = scenario {
        preview(); model.answerFirst(id(), true); advanceUntilIdle()
        model.pickerFailed(id()); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step); assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        preview(); protect(); model.viewModelScope.cancel(); advanceUntilIdle()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value); assertEquals(0, commits)
        assertEquals(V1BackupOutcome.UNCHANGED, model.uiState.value.outcome)
    }
    @Test fun `reminder failure cannot masquerade as rollback or repeat restore`() = scenario {
        failReconcile = true; clearToFinal(); model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(V1BackupOutcome.COMMITTED, model.uiState.value.outcome)
        assertTrue(model.uiState.value.remindersPending); assertEquals(1, commits)
    }
    @Test fun `cancellation after commit retains durable truth`() = scenario {
        cancelAfterCommit = true; clearToFinal(); model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits); assertEquals(V1BackupOutcome.COMMITTED, model.uiState.value.outcome)
        assertTrue(model.uiState.value.remindersPending)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }
    @Test fun `unprovable commit freezes new writes until explicit fresh reread`() = scenario {
        unknownCommit = true; clearToFinal(); model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(V1BackupOutcome.UNKNOWN, model.uiState.value.outcome)
        val operation = id(); model.start(BackupAction.CLEAR, token); advanceUntilIdle()
        assertEquals(operation, id()); assertEquals(1, commits)
        model.acknowledgeUnknown(); advanceUntilIdle()
        assertEquals(V1BackupOutcome.UNKNOWN, model.uiState.value.outcome)
        generations.currentFailure = null; model.acknowledgeUnknown(); advanceUntilIdle()
        assertEquals(BackupStep.IDLE, model.uiState.value.step); assertEquals(1, commits)
    }

    private fun scenario(block: suspend World.() -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val world = World(dispatcher)
        try { world.block() } finally { world.model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
    internal class World(val dispatcher: CoroutineDispatcher,
        val generations: TestGenerations = TestGenerations(),
        val coordinator: MaintenanceCoordinator = MaintenanceCoordinator(generations)) {
        fun advanceUntilIdle() = (dispatcher as TestDispatcher).scheduler.advanceUntilIdle()
        val token = DataGeneration(0)
        val access = GenerationAccess(generations, coordinator)
        val codec = VersionedBackupJsonCodec()
        val original = V2BackupFixtures.empty().copy(settings = V2BackupFixtures.full().settings)
        val imported = V2BackupFixtures.full()
        var payload = original
        var source = codec.encode(V2BackupFixtures.document(imported))
        val saved = mutableMapOf<String, ByteArray>()
        var wrongReadback = false
        var commits = 0
        var failReconcile = false
        var cancelAfterCommit = false
        var unknownCommit = false
        val repository = object : V2BackupRepository { override suspend fun snapshot() = payload }
        val store = object : BackupFileStore {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) { saved[location.value] = bytes }
            override suspend fun read(location: BackupLocation, maxBytes: Int) =
                if (wrongReadback) byteArrayOf(123) else saved.getValue(location.value)
            override suspend fun <T> withStagedInput(location: BackupLocation, maxBytes: Int, operation: suspend (ByteArray) -> T) = operation(source)
        }
        val files = V2BackupFileUseCases(repository, codec, store, coordinator, dispatcher)
        val mutations = object : V2BackupMutationRepository {
            override suspend fun replace(expected: DataGeneration, payload: V2BackupPayload) = mutate(expected, payload)
            override suspend fun clearBusinessData(expected: DataGeneration) = mutate(expected, payload.copy(tables = payload.tables.mapValues { emptyList() }))
        }
        val effects = object : MaintenanceReminderEffects {
            override suspend fun clearPrevious(identities: ReminderIdentities) {}
            override suspend fun reconcile() { if (failReconcile) error("synthetic reminder error") }
            override fun requestReconciliation() {}
        }
        val workflow = V2BackupMaintenanceWorkflow(repository, mutations, files, generations, coordinator,
            object : BackupReminderCapture { override suspend fun snapshot() = ReminderIdentities(emptyList(), emptyList(), emptyList()) }, effects, dispatcher)
        var model = newModel()
        fun newModel() = V1BackupSettingsViewModel(files, workflow, repository, access, dispatcher, { ZoneId.of("Asia/Shanghai") })
        private suspend fun mutate(expected: DataGeneration, replacement: V2BackupPayload): DataGeneration {
            val result = generations.commit(expected) { payload = replacement; commits++ }
            if (unknownCommit) { generations.currentFailure = IllegalStateException("synthetic proof unavailable"); error("synthetic return failure") }
            if (cancelAfterCommit) throw CancellationException("synthetic committed cancellation")
            return result
        }
        fun id() = checkNotNull(model.uiState.value.operationId)
        suspend fun preview(expectPreview: Boolean = true) {
            model.start(BackupAction.RESTORE, token); yield()
            model.sourceSelected(id(), "content://test/source"); yield()
            if (expectPreview) assertEquals(BackupStep.RESTORE_PREVIEW, model.uiState.value.step)
        }
        suspend fun protect() {
            model.answerFirst(id(), true); yield()
            assertEquals(BackupStep.PICK_OUTPUT, model.uiState.value.step)
            model.outputSelected(id(), "content://test/protection"); yield()
            assertEquals(BackupStep.FINAL_CONFIRM, model.uiState.value.step)
            assertEquals(original, codec.decode(saved.getValue("content://test/protection"), ZoneId.of("UTC")).document.payload)
        }
        suspend fun clearToFinal() {
            model.start(BackupAction.CLEAR, token); yield()
            model.answerFirst(id(), false); yield()
            assertEquals(BackupStep.FINAL_CONFIRM, model.uiState.value.step)
        }
    }
}
