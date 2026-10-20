package com.example.lifemanager.data.diary

import android.app.Application
import androidx.room.withTransaction
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DiaryRepositoryRoomTest {
    @Test fun maintenanceReplacementIsAtomicAndPersistentDraftDoesNotDependOnOldGeneration(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val generations=DataGenerationRepositoryImpl(fixture.database)
            val maintenance=MaintenanceCoordinator(generations)
            val repository=RoomDiaryRepository(fixture.database,maintenance,Clock.systemUTC())
            val draft=repository.createDraft(DataGeneration(0),LocalDate.now(Clock.systemUTC()))
            val saved=repository.saveDraft(draft.copy(body="需保留的草稿"),draft.revision,DataGeneration(0))
            val snapshot=fixture.database.withTransaction { repository.backupSnapshotWithinExistingTransaction() }
            maintenance.withSession { session ->
                maintenance.withMaintenance(session) {
                    generations.commit(DataGeneration(0)) {
                        repository.clearWithinExistingTransaction()
                        repository.replaceWithinExistingTransaction(snapshot)
                    }
                }
            }
            assertEquals(saved,repository.readDraft(saved.id))
            val current=repository.saveDraft(saved.copy(body="恢复后继续"),saved.revision,DataGeneration(1))
            assertEquals("恢复后继续",current.body)
        }
    }

    @Test fun twoEditingDraftsCannotBothCommitOverSameEntryRevision(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val date=LocalDate.now(Clock.systemUTC())
            val initial=repository.createDraft(DataGeneration(0),date)
            val initialSaved=repository.saveDraft(initial.copy(body="原文"),initial.revision,DataGeneration(0))
            val entry=repository.commitDraft(initialSaved.id,initialSaved.revision,DataGeneration(0))
            val first=repository.createDraft(DataGeneration(0),date,entry.id)
            val second=repository.createDraft(DataGeneration(0),date,entry.id)
            val one=repository.saveDraft(first.copy(body="第一份"),first.revision,DataGeneration(0))
            val two=repository.saveDraft(second.copy(body="第二份"),second.revision,DataGeneration(0))
            repository.commitDraft(one.id,one.revision,DataGeneration(0))
            assertFailsWith<DiaryRevisionException> { repository.commitDraft(two.id,two.revision,DataGeneration(0)) }
            assertEquals("第二份",repository.readDraft(two.id)?.body)
            assertEquals("第一份",repository.readEntry(entry.id)?.body)
        }
    }

    @Test fun staleAutosaveCannotOverwriteNewerDraftOrReviveCommittedDraft(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val generations = DataGenerationRepositoryImpl(fixture.database)
            val repository = RoomDiaryRepository(fixture.database,MaintenanceCoordinator(generations),Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"),ZoneOffset.UTC))
            val draft = repository.createDraft(DataGeneration(0), LocalDate.parse("2026-10-19"))
            val saved = repository.saveDraft(draft.copy(body="新内容"),draft.revision,DataGeneration(0))
            assertFailsWith<DiaryRevisionException> { repository.saveDraft(draft.copy(body="迟到"),draft.revision,DataGeneration(0)) }
            assertEquals("新内容",repository.readDraft(draft.id)?.body)
            val entry = repository.commitDraft(saved.id,saved.revision,DataGeneration(0))
            assertEquals("新内容",repository.readEntry(entry.id)?.body)
            assertNull(repository.readDraft(draft.id))
            assertFailsWith<DiaryRevisionException> { repository.saveDraft(saved.copy(body="迟到"),saved.revision,DataGeneration(0)) }
        }
    }

    @Test fun failedCompletionPreservesDraftAndMaintenanceKeepsPersistentContent(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val generations=DataGenerationRepositoryImpl(fixture.database)
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(generations),Clock.systemUTC())
            val draft=repository.createDraft(DataGeneration(0),LocalDate.now(Clock.systemUTC()))
            assertFailsWith<IllegalArgumentException> { repository.commitDraft(draft.id,draft.revision,DataGeneration(0)) }
            assertNotNull(repository.readDraft(draft.id))
            generations.commit(DataGeneration(0)) { }
            assertFailsWith<StaleGenerationException> { repository.discardDraft(draft.id,draft.revision,DataGeneration(0)) }
            assertEquals(draft,repository.readDraft(draft.id))
        }
    }

    @Test fun deletingOneSourceRemovesWholeReviewAndEditingMarksItOutdated(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val date=LocalDate.now(Clock.systemUTC())
            val draft=repository.createDraft(DataGeneration(0),date)
            val saved=repository.saveDraft(draft.copy(body="真实记录"),draft.revision,DataGeneration(0))
            val entry=repository.commitDraft(saved.id,saved.revision,DataGeneration(0))
            val snapshot=repository.snapshotRange(date,date,DataGeneration(0))
            val review=DiaryReview("review",date,date,Instant.now(),listOf("生活回顾","心情变化","重要时刻","个人成长","温暖寄语").map { DiaryReviewSection(it,"真实记录",listOf(entry.id)) },listOf(DiarySourceRevision(entry.id,entry.revision)))
            repository.saveReview(review,snapshot)
            val edit=repository.createDraft(DataGeneration(0),date,entry.id)
            val edited=repository.saveDraft(edit.copy(body="改过"),edit.revision,DataGeneration(0))
            val revised=repository.commitDraft(edited.id,edited.revision,DataGeneration(0))
            assertTrue(fixture.database.withTransaction { repository.backupSnapshotWithinExistingTransaction() }.reviews.single().needsUpdate)
            repository.deleteEntry(entry.id,revised.revision,DataGeneration(0))
            assertTrue(fixture.database.withTransaction { repository.backupSnapshotWithinExistingTransaction() }.reviews.isEmpty())
        }
    }
}
