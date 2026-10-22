package com.example.lifemanager.ui.diary.ai

import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.domain.diary.ai.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryAiViewModelTest {
    @Test fun deletingSourceClearsRetainedGeneratedTextAndConfirmedSnapshot() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val day = LocalDate.now()
            val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
            var current = true
            val source = object : DiaryAiDataSource {
                override suspend fun snapshot(from: LocalDate, through: LocalDate) = AiDiarySnapshot(1, from, through, listOf(AiDiarySource(1, 1, day, "", "私密散步经历", null)))
                override suspend fun isCurrent(snapshot: AiDiarySnapshot) = current
                override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot) = current
                override fun observeReviews() = flowOf(emptyList<AiDiaryReview>())
                override suspend fun deleteReview(id: String) = Unit
                override fun observeInvalidations() = changes
            }
            val config = AiServiceConfig.local("small", "a".repeat(64), 1)
            val store = object : AiStateStore {
                override suspend fun config() = config
                override suspend fun saveConfig(address: String, model: String, key: String) = config
                override suspend fun consents() = emptyList<AiAutoConsent>()
                override suspend fun setConsent(consent: AiAutoConsent) = Unit
                override suspend fun jobs() = emptyList<AiAutoJob>()
                override suspend fun putJob(job: AiAutoJob) = Unit
                override suspend fun pauseAutomatic() = Unit
            }
            val phone = object : PhoneAiRunner {
                override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
                    beforeStep()
                    return listOf(
                        AiReviewSection(AiSectionKind.LIFE, "这段时间你记下了散步经历，可以回看具体的记录。", setOf(1)),
                        AiReviewSection(AiSectionKind.MOOD, PhoneSummaryResultPolicy.moodText(sources), setOf(1)),
                        AiReviewSection(AiSectionKind.MOMENTS, PhoneSummaryResultPolicy.sourceText(sources), setOf(1)),
                        AiReviewSection(AiSectionKind.GROWTH, "参考建议：可以补记散步后的感受，方便之后回看。", setOf(1)),
                        AiReviewSection(AiSectionKind.SUPPORT, "参考建议：不妨先安排一点空闲，按自己的状态选择。", setOf(1)),
                    )
                }
                override suspend fun probe(config: AiServiceConfig) = Unit
            }
            val viewModel = DiaryAiViewModel(DiaryAiEngine(source, store, phone = phone), source, store)
            viewModel.range(day.toString(), day.toString())
            viewModel.prepare()
            viewModel.generate(viewModel.uiState.value.preview!!)
            assertNotNull(viewModel.uiState.value.result)
            current = false
            changes.emit(Unit)
            assertNull(viewModel.uiState.value.result)
            assertNull(viewModel.uiState.value.preview)
        } finally { Dispatchers.resetMain() }
    }
}
