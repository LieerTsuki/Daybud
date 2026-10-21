package com.example.lifemanager.data.diary.ai

import android.app.Application
import com.example.lifemanager.data.diary.RoomDiaryRepository
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.diary.ai.*
import com.example.lifemanager.domain.maintenance.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomDiaryAiDataSourceTest {
    @Test fun localMedicalQuoteIsSavedButForgedPhoneProseIsRejected() = runBlocking {
        V6RoomFixture().use { fixture ->
            val generations = DataGenerationRepositoryImpl(fixture.database)
            val maintenance = MaintenanceCoordinator(generations)
            val repository = RoomDiaryRepository(fixture.database, maintenance, Clock.systemUTC())
            val adapter = RoomDiaryAiDataSource(repository, maintenance, generations, fixture.database)
            val day = LocalDate.now(Clock.systemUTC())
            val draft = repository.createDraft(DataGeneration(0), day)
            val saved = repository.saveDraft(draft.copy(body = "今天确诊了感冒。"), draft.revision, DataGeneration(0))
            val entry = repository.commitDraft(saved.id, saved.revision, DataGeneration(0))
            val snapshot = adapter.snapshot(day, day)
            val ids = setOf(entry.id)
            val sections = listOf(
                AiReviewSection(AiSectionKind.LIFE, GroundedAiResultPolicy.EXCERPT_PREFIX + "$day #${entry.id} 正文：“今天确诊了感冒。”", ids),
                AiReviewSection(AiSectionKind.MOOD, GroundedAiResultPolicy.moodText(snapshot.sources), ids),
                AiReviewSection(AiSectionKind.MOMENTS, GroundedAiResultPolicy.emptySelectionText(AiSectionKind.MOMENTS), ids),
                AiReviewSection(AiSectionKind.GROWTH, GroundedAiResultPolicy.emptySelectionText(AiSectionKind.GROWTH), ids),
                AiReviewSection(AiSectionKind.SUPPORT, GroundedAiResultPolicy.emptySelectionText(AiSectionKind.SUPPORT), ids),
            )
            val review = AiDiaryReview("medical-local", day, day, Instant.now(), sections, mapOf(entry.id to entry.revision),
                provider = "phone-offline:${"a".repeat(64)}", model = "small@${"a".repeat(64)}")
            assertTrue(adapter.save(review, snapshot))
            assertEquals("medical-local", adapter.observeReviewSnapshots().first().second.single().id)
            val forged = review.copy(id = "forged", sections = sections.map {
                if (it.kind == AiSectionKind.LIFE) it.copy(text = "今天获得比赛冠军。") else it
            })
            assertFailsWith<AiProtocolException> { adapter.save(forged, snapshot) }
            assertEquals(listOf("medical-local"), adapter.observeReviewSnapshots().first().second.map { it.id })
        }
    }

    @Test fun rangeChangeOrNewEntryInvalidatesSnapshotAndStaleDeleteCannotTouchRestoredReview() = runBlocking {
        V6RoomFixture().use { fixture ->
            val generations = DataGenerationRepositoryImpl(fixture.database)
            val maintenance = MaintenanceCoordinator(generations)
            val repository = RoomDiaryRepository(fixture.database, maintenance, Clock.systemUTC())
            val adapter = RoomDiaryAiDataSource(repository, maintenance, generations, fixture.database)
            val day = LocalDate.now(Clock.systemUTC())
            val draft = repository.createDraft(DataGeneration(0), day)
            val saved = repository.saveDraft(draft.copy(body = "真实生活", mood = com.example.lifemanager.domain.diary.DiaryMood.CALM), draft.revision, DataGeneration(0))
            val entry = repository.commitDraft(saved.id, saved.revision, DataGeneration(0))
            val snapshot = adapter.snapshot(day, day)
            assertEquals("平静", snapshot.sources.single().mood)
            val review = AiDiaryReview("review", day, day, Instant.now(), AiSectionKind.entries.map { AiReviewSection(it, "真实生活", setOf(entry.id)) }, mapOf(entry.id to entry.revision))
            assertTrue(adapter.save(review, snapshot))
            val published = adapter.observeReviewSnapshots().first()
            assertEquals(0L, published.first)
            assertEquals("review", published.second.single().id)
            generations.commit(DataGeneration(0)) { }
            assertFalse(adapter.isCurrent(snapshot))
            assertFailsWith<StaleGenerationException> { adapter.deleteReview("review", 0) }
            assertEquals("review", adapter.observeReviewSnapshots().first().second.single().id)
            assertFalse(adapter.save(review.copy(id = "late"), snapshot))
        }
    }
}
