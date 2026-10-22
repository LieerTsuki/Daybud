package com.example.lifemanager.ui.diary.ai

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.example.lifemanager.data.diary.ai.AiModeStore
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.data.diary.ai.local.LocalModelCatalog
import com.example.lifemanager.data.diary.ai.local.LocalModelStore
import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AiModeViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectingAlreadyLocalWithoutModelKeepsSelectedModelAndConfirmedPreview() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val owners = ViewModelStore()
        try {
            val modes = AiModeStore(temporary.newFolder("mode"))
            val selected = LocalModelCatalog.models.first { it.id.startsWith("qwen3-1.7b") }
            modes.selectLocal(selected.id, selected.sha256)
            val source = object : DiaryAiDataSource {
                override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through,
                    listOf(AiDiarySource(11, 1, from, "记录", "今天整理了课程笔记。", "平静")))
                override suspend fun isCurrent(snapshot: AiDiarySnapshot) = true
                override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot) = true
                override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
                override suspend fun deleteReview(id: String) = Unit
                override suspend fun currentGeneration() = 1L
            }
            val gateway = object : AiCompletionGateway {
                override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> = error("unused")
                override suspend fun probe(config: AiServiceConfig) = Unit
            }
            val models = LocalModelStore(temporary.newFolder("models"))
            val model = DiaryAiViewModel(DiaryAiEngine(source, modes, gateway), source, modes, models)
            owners.put("ai", model)
            withTimeout(10_000) { model.uiState.first { it.mode == AiExecutionMode.LOCAL && it.model == selected.id } }
            model.range("2026-10-18", "2026-10-20")
            model.prepare()
            val original = withTimeout(10_000) { model.uiState.first { !it.busy && it.preview != null }.preview!! }

            // Re-selecting the already selected mode must not change the chosen model,
            // revoke the user's confirmed range, or make that preview stale.
            model.selectLocal()
            val after = withTimeout(10_000) { model.uiState.first { !it.busy } }
            assertEquals(selected.id, after.model)
            assertSame(original, after.preview)
            assertEquals(selected.id, modes.config()!!.model)
            assertEquals(original.configRevision, modes.config()!!.revision)
        } finally {
            owners.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun newActionWaitsForCancelledGenerationToReleaseItsResources() = runBlocking {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        val owners = ViewModelStore()
        val cleanupRelease = CompletableDeferred<Unit>()
        try {
            val modes = AiModeStore(temporary.newFolder("mode-cleanup"))
            val selected = LocalModelCatalog.models.first { it.id.startsWith("qwen3-1.7b") }
            modes.selectLocal(selected.id, selected.sha256)
            val snapshots = AtomicInteger()
            val nextSnapshot = CompletableDeferred<Unit>()
            val source = object : DiaryAiDataSource {
                override suspend fun snapshot(from: LocalDate, through: LocalDate): AiDiarySnapshot {
                    if (snapshots.incrementAndGet() > 1) nextSnapshot.complete(Unit)
                    return AiDiarySnapshot(1, from, through,
                        listOf(AiDiarySource(11, 1, from, "记录", "今天整理了课程笔记。", "平静")))
                }
                override suspend fun isCurrent(snapshot: AiDiarySnapshot) = true
                override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot) = true
                override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
                override suspend fun deleteReview(id: String) = Unit
                override suspend fun currentGeneration() = 1L
            }
            val started = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val phone = object : PhoneAiRunner {
                override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
                    beforeStep()
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); cleanupRelease.await() } }
                }
                override suspend fun probe(config: AiServiceConfig) = Unit
            }
            val gateway = object : AiCompletionGateway {
                override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> = error("unused")
                override suspend fun probe(config: AiServiceConfig) = Unit
            }
            val model = DiaryAiViewModel(DiaryAiEngine(source, modes, gateway, phone = phone), source, modes,
                LocalModelStore(temporary.newFolder("models-cleanup")))
            owners.put("ai", model)
            withTimeout(10_000) { model.uiState.first { it.mode == AiExecutionMode.LOCAL && it.model == selected.id } }
            model.range("2026-10-18", "2026-10-20")
            model.prepare()
            val preview = withTimeout(10_000) { model.uiState.first { !it.busy && it.preview != null }.preview!! }
            model.generate(preview)
            withTimeout(10_000) { started.await() }
            kotlinx.coroutines.delay(1_100)
            main.scheduler.advanceTimeBy(1_001)
            assertTrue(model.uiState.value.elapsedSeconds >= 1)

            model.cancel()
            withTimeout(10_000) { cleanupStarted.await() }
            model.prepare()
            // Uses a real wall-clock timeout: the mode store performs actual Dispatchers.IO
            // work, while native cancellation cleanup intentionally remains incomplete.
            val readBeforeCleanup = withTimeoutOrNull(1_000) { nextSnapshot.await(); true } ?: false
            assertFalse("A new action read diary data before cancelled inference cleanup completed", readBeforeCleanup)
            cleanupRelease.complete(Unit)
            withTimeout(10_000) { nextSnapshot.await() }
            withTimeout(10_000) { model.uiState.first { !it.busy } }
            assertEquals(2, snapshots.get())
        } finally {
            cleanupRelease.complete(Unit)
            owners.clear()
            Dispatchers.resetMain()
        }
    }
}
