package com.example.lifemanager.data.diary.ai

import androidx.room.withTransaction
import com.example.lifemanager.data.diary.domain
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.diary.ai.*
import com.example.lifemanager.domain.maintenance.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** The only bridge from persisted diary data to the text-only AI boundary. */
class RoomDiaryAiDataSource(
    private val repository: DiaryRepository,
    private val maintenance: MaintenanceCoordinator,
    private val generations: DataGenerationRepository,
    private val database: LifeManagerDatabase,
) : DiaryAiDataSource {
    override suspend fun snapshot(from: LocalDate, through: LocalDate): AiDiarySnapshot {
        val token = maintenance.capture()
        return repository.snapshotRange(from, through, token).ai()
    }
    override suspend fun currentGeneration() = maintenance.capture().value
    override suspend fun isCurrent(snapshot: AiDiarySnapshot): Boolean = try {
        repository.snapshotRange(snapshot.from, snapshot.through, DataGeneration(snapshot.generation)).ai() == snapshot
    } catch (error: CancellationException) { throw error }
    catch (_: MaintenanceBusyException) { false }
    catch (_: StaleGenerationException) { false }

    override suspend fun save(review: AiDiaryReview, snapshot: AiDiarySnapshot): Boolean = try {
        val current = repository.snapshotRange(snapshot.from, snapshot.through, DataGeneration(snapshot.generation))
        if (current.ai() != snapshot) false else {
            require(review.from == snapshot.from && review.through == snapshot.through && !review.needsUpdate)
            AiResultPolicy.validate(review.sections, review.sources.keys)
            require(review.sources.all { (id, revision) -> current.entries.any { it.id == id && it.revision == revision } })
            repository.saveReview(DiaryReview(review.id, review.from, review.through, review.createdAt,
                review.sections.map { DiaryReviewSection(it.kind.title, it.text, it.sourceIds.sorted()) },
                review.sources.map { DiarySourceRevision(it.key, it.value) }, false, review.provider, review.model, review.inputHash), current)
            true
        }
    } catch (error: CancellationException) { throw error }
    catch (_: MaintenanceBusyException) { false }
    catch (_: StaleGenerationException) { false }
    catch (_: DiaryRevisionException) { false }

    override suspend fun deleteReview(id: String) { repository.deleteReview(id, maintenance.capture()) }
    override suspend fun deleteReview(id: String, expectedGeneration: Long) { repository.deleteReview(id, DataGeneration(expectedGeneration)) }

    override fun observeReviews() = observeReviewSnapshots().map { it.second }
    override fun observeInvalidations(): Flow<Unit> = combine(generations.observe(), database.diaryDao().observeEntries(), maintenance.state) { _, _, _ -> Unit }
    override fun observeReviewSnapshots(): Flow<Pair<Long, List<AiDiaryReview>>> = combine(
        generations.observe(), database.diaryDao().observeReviews(), maintenance.state,
    ) { _, _, state -> state }.filter { it == MaintenanceState.IDLE }.mapNotNull {
        try {
            val token = maintenance.capture()
            maintenance.run(token) {
                database.withTransaction {
                    if (generations.current() != token) throw StaleGenerationException()
                    val dao = database.diaryDao()
                    val reviews = dao.reviews().map { row ->
                        row.domain(dao.sources(row.id).map { DiarySourceRevision(it.entryId, it.revision) }).ai()
                    }
                    token.value to reviews
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: MaintenanceBusyException) { null }
        catch (_: StaleGenerationException) { null }
    }

    private fun DiarySnapshot.ai() = AiDiarySnapshot(generation.value, from, to, entries.map {
        AiDiarySource(it.id, it.revision, it.date, it.title, it.body, it.mood?.label)
    })
    private fun DiaryReview.ai(): AiDiaryReview {
        val sections = sections.map { section ->
            val kind = AiSectionKind.entries.firstOrNull { it.title == section.title } ?: throw AiProtocolException()
            AiReviewSection(kind, section.text, section.sourceIds.toSet())
        }
        return AiDiaryReview(id, from, to, createdAt, sections, sources.associate { it.entryId to it.revision }, needsUpdate, provider, model, inputHash)
    }
}
