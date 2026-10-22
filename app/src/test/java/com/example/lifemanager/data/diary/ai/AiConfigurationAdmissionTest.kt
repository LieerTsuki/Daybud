package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import com.example.lifemanager.domain.maintenance.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiConfigurationAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val day = LocalDate.of(2026, 10, 19)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val sha = "a".repeat(64)
    private class Source : DiaryAiDataSource {
        val entries = listOf(AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "记录", "散步后准备学习。", "平静"))
        var current: suspend () -> Boolean = { true }
        var persist: suspend () -> Unit = {}
        var saved = 0
        override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through, entries)
        override suspend fun isCurrent(snapshot: AiDiarySnapshot) = current()
        override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean { persist(); saved++; return true }
        override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
        override suspend fun deleteReview(id: String) = Unit
    }
    private class Store(var value: AiServiceConfig) : AiStateStore {
        override suspend fun config() = value
        override suspend fun saveConfig(address: String, model: String, key: String) = error("retired")
        override suspend fun consents() = emptyList<AiAutoConsent>()
        override suspend fun setConsent(consent: AiAutoConsent) = Unit
        override suspend fun jobs() = emptyList<AiAutoJob>()
        override suspend fun putJob(job: AiAutoJob) = Unit
        override suspend fun pauseAutomatic() = Unit
    }
    private class Phone : PhoneAiRunner {
        var after: () -> Unit = {}
        var during: suspend () -> Unit = {}
        var calls = 0
        override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
            beforeStep(); calls++; during(); after()
            val ids = setOf(1L)
            return listOf(
                AiReviewSection(AiSectionKind.LIFE, "你记录了散步和接下来的学习安排。", ids),
                AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), ids),
                AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), ids),
                AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以先列出一个准备学习的小任务。", ids),
                AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨记下散步后的感受。", ids),
            )
        }
        override suspend fun probe(config: AiServiceConfig) = Unit
    }

    @Test fun configChangedWhileThePublicationSourceCheckIsSuspendedCannotPublishOldModelResult() = runTest {
        val source = Source(); val store = Store(AiServiceConfig.local("small", sha, 1))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val phone = Phone().apply { after = { source.current = { entered.complete(Unit); release.await(); true } } }
        val engine = DiaryAiEngine(source, store, clock, phone)
        val preview = engine.preview(day, day)
        val work = async { runCatching { engine.generate(preview) } }
        entered.await()
        store.value = AiServiceConfig.local("other", "b".repeat(64), 2)
        release.complete(Unit)
        val result = work.await()
        assertTrue("A model change during source verification published an old result", result.exceptionOrNull() is AiStaleException)
        assertEquals(0, source.saved)
    }

    @Test fun modelIdentityChangeWithTheSameRevisionAndDigestStillInvalidatesPreview() = runTest {
        val source = Source(); val store = Store(AiServiceConfig.local("small", sha, 1)); val phone = Phone()
        val engine = DiaryAiEngine(source, store, clock, phone)
        val preview = engine.preview(day, day)
        store.value = AiServiceConfig.local("different-model", sha, 1)
        try { engine.generate(preview); fail("Different model reused the old preview") }
        catch (_: AiStaleException) { }
        assertEquals(0, phone.calls)
    }

    @Test fun productionSelectionWaitsForAnAlreadyAdmittedSaveToCommit() = runBlocking {
        withTimeout(10_000) {
            val store = AiModeStore(temporary.newFolder())
            store.selectLocal("small", sha)
            val source = Source(); val engine = DiaryAiEngine(source, store, clock, Phone())
            val preview = engine.preview(day, day); val review = engine.generate(preview)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            source.persist = { entered.complete(Unit); release.await(); events += "save" }
            val save = async { engine.save(review, preview) }
            entered.await()
            val selection = async { store.selectLocal("other", "b".repeat(64)).also { events += "select" } }
            try {
                assertNull("Model selection overtook an admitted save", withTimeoutOrNull(300) { selection.await() })
            } finally { release.complete(Unit) }
            assertTrue(save.await()); selection.await()
            assertEquals(listOf("save", "select"), events)
            assertEquals("other", store.config()!!.model)
        }
    }

    @Test fun productionConfigurationMutexIsNotHeldAcrossModelInference() = runBlocking {
        withTimeout(10_000) {
            val store = AiModeStore(temporary.newFolder()); store.selectLocal("small", sha)
            val source = Source(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val phone = Phone().apply { during = { entered.complete(Unit); release.await() } }
            val engine = DiaryAiEngine(source, store, clock, phone); val preview = engine.preview(day, day)
            val generation = async { runCatching { engine.generate(preview) } }
            entered.await()
            try {
                assertEquals("other", store.selectLocal("other", "b".repeat(64)).model)
            } finally { release.complete(Unit) }
            assertTrue(generation.await().exceptionOrNull() is AiStaleException)
            assertEquals(0, source.saved)
        }
    }

    @Test fun staleProductionAdmissionNeverEntersSourceCommit() = runBlocking {
        val store = AiModeStore(temporary.newFolder())
        val first = store.selectLocal("small", sha)
        store.selectLocal("other", "b".repeat(64))
        var entered = false
        try { store.withCurrentConfiguration(first) { entered = true }; fail("Stale model entered a commit") }
        catch (_: AiStaleException) { }
        assertFalse(entered)
    }

    @Test fun defaultAdmissionComparesEveryLocalIdentityFieldForMemoryAdapters() = runTest {
        val expected = AiServiceConfig.local("small", sha, 1)
        val configurations = listOf(
            AiServiceConfig.local("small", sha, 2),
            AiServiceConfig.local("other", sha, 1),
            AiServiceConfig.local("small", "b".repeat(64), 1),
            AiServiceConfig.checked("https://retired.example", "small", "synthetic", 1),
        )
        for (current in configurations) {
            var entered = false
            try { Store(current).withCurrentConfiguration(expected) { entered = true }; fail("Mismatched identity admitted") }
            catch (_: AiStaleException) { }
            assertFalse(entered)
        }
    }

    @Test fun cancelledAdmittedSaveReleasesConfigurationMutexForWaitingSelection() = runBlocking {
        withTimeout(10_000) {
            val store = AiModeStore(temporary.newFolder()); store.selectLocal("small", sha)
            val source = Source(); val engine = DiaryAiEngine(source, store, clock, Phone())
            val preview = engine.preview(day, day); val review = engine.generate(preview)
            val entered = CompletableDeferred<Unit>()
            source.persist = { entered.complete(Unit); awaitCancellation() }
            val save = async { engine.save(review, preview) }
            entered.await()
            val selection = async { store.selectLocal("other", "b".repeat(64)) }
            save.cancelAndJoin()
            assertEquals("other", selection.await().model)
            assertEquals(0, source.saved)
        }
    }

    @Test fun realMaintenanceDrainsAnAdmittedSaveBeforeInvalidatingConfiguration() = runBlocking {
        withTimeout(10_000) {
            val maintenance = MaintenanceCoordinator(Generations())
            val store = AiModeStore(temporary.newFolder()); val first = store.selectLocal("small", sha)
            val source = Source(); val engine = DiaryAiEngine(source, store, clock, Phone())
            val preview = engine.preview(day, day); val review = engine.generate(preview)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            source.persist = {
                maintenance.run(DataGeneration(1)) { entered.complete(Unit); release.await(); events += "save" }
            }
            val save = async { engine.save(review, preview) }
            entered.await()
            val backup = async {
                maintenance.withSession { session ->
                    maintenance.withMaintenance(session) { store.invalidateRequests(); events += "invalidate" }
                }
            }
            try {
                maintenance.state.first { it == MaintenanceState.DRAINING }
                assertFalse(backup.isCompleted)
            } finally { release.complete(Unit) }
            assertTrue(save.await()); backup.await()
            assertEquals(listOf("save", "invalidate"), events)
            assertEquals(1, source.saved)
            assertTrue(store.config()!!.revision > first.revision)
            assertEquals(MaintenanceState.IDLE, maintenance.state.value)
            try { engine.save(review, preview); fail("Pre-maintenance save action survived invalidation") }
            catch (_: AiStaleException) { }
        }
    }

    @Test fun realMaintenanceFreezeRejectsNewSaveAndAllowsPendingInvalidationWithoutDeadlock() = runBlocking {
        withTimeout(10_000) {
            val maintenance = MaintenanceCoordinator(Generations())
            val store = AiModeStore(temporary.newFolder()); val first = store.selectLocal("small", sha)
            val source = Source(); val engine = DiaryAiEngine(source, store, clock, Phone())
            val preview = engine.preview(day, day); val review = engine.generate(preview)
            val ready = CompletableDeferred<Unit>()
            val requestInvalidation = CompletableDeferred<Unit>(); val invalidationEntered = CompletableDeferred<Unit>()
            val backup = async {
                maintenance.withSession { session ->
                    ready.complete(Unit); requestInvalidation.await()
                    maintenance.withMaintenance(session) {
                        invalidationEntered.complete(Unit)
                        store.invalidateRequests()
                    }
                }
            }
            ready.await()
            source.persist = {
                requestInvalidation.complete(Unit)
                invalidationEntered.await()
                maintenance.run(DataGeneration(1)) { fail("A frozen coordinator admitted a new write") }
            }
            val result = async { runCatching { engine.save(review, preview) } }.await()
            assertTrue("Frozen admission must fail immediately instead of waiting for backup", result.exceptionOrNull() is MaintenanceBusyException)
            backup.await()
            assertEquals(0, source.saved)
            assertTrue(store.config()!!.revision > first.revision)
            assertEquals(MaintenanceState.IDLE, maintenance.state.value)
        }
    }

    private class Generations : DataGenerationRepository {
        override fun observe() = flowOf(DataGeneration(1))
        override suspend fun current() = DataGeneration(1)
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
            error("This lock-order regression does not mutate or replace business data")
        }
    }
}
