package com.example.lifemanager.data.backup

import android.app.Application
import com.example.lifemanager.data.diary.RoomDiaryRepository
import com.example.lifemanager.data.diary.ai.RoomDiaryAiDataSource
import com.example.lifemanager.domain.diary.ai.*
import java.time.Instant
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class V4BackupRepositoryRoomTest {
    @Test fun mixedCloudExcerptAndSummaryReviewsSurviveManifestRestoreAndDeleteWithTheirSource():Unit=runBlocking {
        V6RoomFixture().use { fixture ->
            val db = fixture.database
            val generations = DataGenerationRepositoryImpl(db)
            val maintenance = MaintenanceCoordinator(generations)
            val diary = RoomDiaryRepository(db, maintenance, Clock.systemUTC())
            val adapter = RoomDiaryAiDataSource(diary, maintenance, generations, db)
            val repo = V4BackupRepository(db, V2BackupRepositoryImpl(db, db.backupSnapshotDao()), diary)
            val mutations = V4BackupMutationRepository(db, V2BackupMutationRepositoryImpl(db, db.backupSnapshotDao()), diary)
            val day = LocalDate.now(Clock.systemUTC())
            val draft = diary.createDraft(DataGeneration(0), day)
            val updated = diary.saveDraft(draft.copy(body="散步后准备学习。"), draft.revision, DataGeneration(0))
            val entry = diary.commitDraft(updated.id, updated.revision, DataGeneration(0))
            val snapshot = adapter.snapshot(day, day)
            val ids = setOf(entry.id)
            val sha = "a".repeat(64)
            val summary = AiDiaryReview("summary", day, day,
                Instant.ofEpochMilli(123), listOf(
                    AiReviewSection(AiSectionKind.LIFE,"记录了散步和学习安排。",ids),
                    AiReviewSection(AiSectionKind.MOOD,PhoneSummaryResultPolicy.moodText(snapshot.sources),ids),
                    AiReviewSection(AiSectionKind.MOMENTS,PhoneSummaryResultPolicy.sourceText(snapshot.sources),ids),
                    AiReviewSection(AiSectionKind.GROWTH,"参考建议：可以先整理学习资料。",ids),
                    AiReviewSection(AiSectionKind.SUPPORT,"参考建议：不妨先选择一个小任务。",ids),
                ), mapOf(entry.id to entry.revision), provider="phone-summary:v1:$sha",model="small@$sha",inputHash="b".repeat(64))
            assertTrue(adapter.save(summary, snapshot))
            val excerpt = summary.copy(id="excerpt", provider="phone-offline:$sha", sections=summary.sections.map {
                it.copy(text=when(it.kind) {
                    AiSectionKind.LIFE -> GroundedAiResultPolicy.EXCERPT_PREFIX + "$day #${entry.id} 正文：“散步后准备学习。”"
                    AiSectionKind.MOOD -> GroundedAiResultPolicy.moodText(snapshot.sources)
                    else -> GroundedAiResultPolicy.emptySelectionText(it.kind)
                })
            })
            assertTrue(adapter.save(excerpt, snapshot))
            assertTrue(adapter.save(summary.copy(id="cloud-history",provider="https://old.example",model="historical"),snapshot))
            val full = repo.snapshot()
            val codec = V4BackupManifest()
            val decoded = codec.decode(codec.encode(V2BackupFixtures.document(full))).payload
            assertEquals(full, decoded)
            mutations.clearBusinessData(DataGeneration(0))
            assertTrue(repo.snapshot().diary!!.reviews.isEmpty())
            mutations.replace(DataGeneration(1), decoded)
            assertEquals(full, repo.snapshot())
            assertFalse(adapter.save(summary.copy(id="late-result"), snapshot))
            diary.deleteEntry(entry.id, entry.revision, DataGeneration(2))
            assertTrue(repo.snapshot().diary!!.reviews.isEmpty())
        }
    }
    @Test fun durableReferencesAndExportPinsProtectRollbackAndRestoreButClearCollectsOrphans():Unit=runBlocking {
        val folder=java.nio.file.Files.createTempDirectory("v4-commit-gc").toFile()
        try { V6RoomFixture().use { fixture ->
            val db=fixture.database
            val storage=com.example.lifemanager.data.diary.FileDiaryStorage(folder)
            val generations=DataGenerationRepositoryImpl(db)
            val diary=RoomDiaryRepository(db,MaintenanceCoordinator(generations),Clock.systemUTC(),storage)
            val repo=V4BackupRepository(db,V2BackupRepositoryImpl(db,db.backupSnapshotDao()),diary)
            val mutations=V4BackupMutationRepository(db,V2BackupMutationRepositoryImpl(db,db.backupSnapshotDao()),diary,storage)
            val imported=storage.importPinned(java.io.ByteArrayInputStream(byteArrayOf(1,2,3)),"image/jpeg")
            val draft=diary.createDraft(DataGeneration(0),LocalDate.now(Clock.systemUTC()))
            diary.saveDraft(draft.copy(body="附件内容",media=listOf(imported.media)),draft.revision,DataGeneration(0))
            imported.pin.close()
            val before=repo.snapshot()
            assertFails { mutations.replace(DataGeneration(0),before.copy(diary=before.diary!!.copy(entrySequence=-1))) }
            assertEquals(DataGeneration(0),generations.current())
            assertTrue(storage.file(imported.media).isFile)
            val failingDiary=object:com.example.lifemanager.domain.diary.DiaryRepository by diary {
                override suspend fun clearWithinExistingTransaction() {
                    diary.clearWithinExistingTransaction()
                    error("Simulated failure after deleting diary rows")
                }
            }
            val failing=V4BackupMutationRepository(db,V2BackupMutationRepositoryImpl(db,db.backupSnapshotDao()),failingDiary,storage)
            assertFails { failing.clearBusinessData(DataGeneration(0)) }
            assertEquals(DataGeneration(0),generations.current())
            assertEquals(before,repo.snapshot())
            assertTrue(storage.file(imported.media).isFile)
            val exportPin=storage.withGate { storage.pin(before.diary!!.media) }
            mutations.clearBusinessData(DataGeneration(0))
            assertTrue(storage.file(imported.media).isFile)
            exportPin.close()
            val orphan=storage.importPinned(java.io.ByteArrayInputStream(byteArrayOf(9)),"image/jpeg")
            orphan.pin.close()
            mutations.replace(DataGeneration(1),before)
            assertTrue(storage.file(imported.media).isFile)
            assertFalse(storage.file(orphan.media).exists())
            mutations.clearBusinessData(DataGeneration(2))
            assertFalse(storage.file(imported.media).exists())
        } } finally { folder.deleteRecursively() }
    }
    @Test fun historicalRestorePreservesDiaryAndNewRestoreReplacesItAtomically():Unit=runBlocking {
        V6RoomFixture().use { fixture ->
            val db=fixture.database
            val generations=DataGenerationRepositoryImpl(db)
            val diary=RoomDiaryRepository(db,MaintenanceCoordinator(generations),Clock.systemUTC())
            val legacy=V2BackupRepositoryImpl(db,db.backupSnapshotDao())
            val repo=V4BackupRepository(db,legacy,diary)
            val mutations=V4BackupMutationRepository(db,V2BackupMutationRepositoryImpl(db,db.backupSnapshotDao()),diary)
            val draft=diary.createDraft(DataGeneration(0),LocalDate.now(Clock.systemUTC()))
            val saved=diary.saveDraft(draft.copy(body="手机里的真实草稿"),draft.revision,DataGeneration(0))
            val full=repo.snapshot()
            assertEquals(saved,full.diary!!.drafts.single())
            assertEquals(DataGeneration(1),mutations.replace(DataGeneration(0),full.copy(diary=null)))
            assertEquals(saved,diary.readDraft(saved.id))
            val empty=full.copy(diary=com.example.lifemanager.domain.diary.DiaryBackupSnapshot())
            assertEquals(DataGeneration(2),mutations.replace(DataGeneration(1),empty))
            assertNull(diary.readDraft(saved.id))
            assertEquals(DataGeneration(3),mutations.replace(DataGeneration(2),full))
            assertEquals(saved,diary.readDraft(saved.id))
        }
    }

    @Test fun failedDiaryReplacementRollsBackLegacyRowsAndGeneration():Unit=runBlocking {
        V6RoomFixture().use { fixture ->
            val db=fixture.database
            val generations=DataGenerationRepositoryImpl(db)
            val diary=RoomDiaryRepository(db,MaintenanceCoordinator(generations),Clock.systemUTC())
            val legacy=V2BackupRepositoryImpl(db,db.backupSnapshotDao())
            val repo=V4BackupRepository(db,legacy,diary)
            val mutations=V4BackupMutationRepository(db,V2BackupMutationRepositoryImpl(db,db.backupSnapshotDao()),diary)
            val before=repo.snapshot()
            val invalid=com.example.lifemanager.domain.diary.DiaryBackupSnapshot(entrySequence=-1)
            assertFails { mutations.replace(DataGeneration(0),before.copy(diary=invalid)) }
            assertEquals(DataGeneration(0),generations.current())
            assertEquals(before,repo.snapshot())
        }
    }
}
