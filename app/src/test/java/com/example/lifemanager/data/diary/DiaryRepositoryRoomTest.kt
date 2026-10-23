package com.example.lifemanager.data.diary

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.RuntimeEnvironment
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
    @Test fun contentSnapshotKeepsSharedAttachmentPositionsPerOwner(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val dao=fixture.database.diaryDao()
            dao.insertEntry(DiaryEntryEntity(1,0,"记录","正文",null,1,1,1))
            dao.insertDraft(DiaryDraftEntity("draft",1,1,0,"","草稿",null,1,1,1))
            listOf("a","b").forEach { dao.insertMedia(DiaryMediaEntity(it,"image/jpeg",1,it.repeat(64))) }
            dao.insertEntryMedia(listOf(DiaryEntryMediaEntity(1,"a",0),DiaryEntryMediaEntity(1,"b",1)))
            dao.insertDraftMedia(listOf(DiaryDraftMediaEntity("draft","b",0),DiaryDraftMediaEntity("draft","a",1)))
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val content=repository.readContent()
            assertEquals(listOf("a" to 0,"b" to 1),content.entries.single().media.map { it.id to it.position })
            assertEquals(listOf("b" to 0,"a" to 1),content.drafts.single().media.map { it.id to it.position })
            assertEquals(content.entries.single(),repository.readEntry(1))
            assertEquals(content.drafts.single(),repository.readDraft("draft"))
        }
    }

    @Test fun contentSnapshotsNeverMixBeforeAndAfterDraftCommit(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val draft=repository.createDraft(DataGeneration(0),LocalDate.now(Clock.systemUTC()))
            val saved=repository.saveDraft(draft.copy(body="提交时保持一致"),draft.revision,DataGeneration(0))
            val before=repository.readContent()
            assertTrue(before.entries.isEmpty());assertEquals(listOf(saved),before.drafts)
            coroutineScope {
                val writer=async(Dispatchers.Default) { repository.commitDraft(saved.id,saved.revision,DataGeneration(0)) }
                repeat(30) {
                    val snapshot=repository.readContent()
                    assertEquals(1,snapshot.entries.size+snapshot.drafts.size,"A snapshot must contain either the pending draft or its committed entry")
                }
                writer.await()
            }
            val after=repository.readContent()
            assertTrue(after.drafts.isEmpty());assertEquals("提交时保持一致",after.entries.single().body)
        }
    }

    @Test fun batchedReadsRejectDanglingAttachmentReferences(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val dao=fixture.database.diaryDao()
            dao.insertEntry(DiaryEntryEntity(1,0,"记录","正文",null,1,1,1))
            dao.insertDraft(DiaryDraftEntity("draft",null,null,0,"","草稿",null,1,1,1))
            dao.insertMedia(DiaryMediaEntity("photo","image/jpeg",1,"a".repeat(64)))
            dao.insertEntryMedia(listOf(DiaryEntryMediaEntity(1,"photo",0)))
            dao.insertDraftMedia(listOf(DiaryDraftMediaEntity("draft","photo",0)))
            // Model an externally damaged file; ordinary Room writes enforce these foreign keys.
            fixture.database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
            fixture.database.openHelper.writableDatabase.execSQL("DELETE FROM diary_media")
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            assertFailsWith<IllegalArgumentException> { repository.readEntry(1) }
            assertFailsWith<IllegalArgumentException> { repository.readDraft("draft") }
            assertFailsWith<IllegalArgumentException> { repository.readContent() }
            assertFailsWith<IllegalArgumentException> { repository.observeEntries().first() }
            assertFailsWith<IllegalArgumentException> { repository.observeDrafts().first() }
        }
    }

    @Test fun entryObservationRefreshesWhenOnlyAttachmentReferencesChange(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val dao=fixture.database.diaryDao()
            dao.insertEntry(DiaryEntryEntity(1,0,"记录","正文",null,1,1,1))
            dao.insertMedia(DiaryMediaEntity("photo","image/jpeg",1,"a".repeat(64)))
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val updates=Channel<List<DiaryEntry>>(Channel.UNLIMITED)
            val observer=launch(Dispatchers.Default) { repository.observeEntries().collect { updates.send(it) } }
            try {
                assertTrue(withTimeout(5_000) { updates.receive() }.single().media.isEmpty())
                dao.insertEntryMedia(listOf(DiaryEntryMediaEntity(1,"photo",0)))
                val updated=withTimeout(5_000) { var next=updates.receive();while(next.single().media.isEmpty())next=updates.receive();next }
                assertEquals(listOf("photo"),updated.single().media.map { it.id })
            } finally { observer.cancelAndJoin();updates.close() }
        }
    }

    @Test fun draftObservationRefreshesWhenOnlyAttachmentReferencesChange(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val dao=fixture.database.diaryDao()
            dao.insertDraft(DiaryDraftEntity("draft",null,null,0,"","正文",null,1,1,1))
            dao.insertMedia(DiaryMediaEntity("photo","image/jpeg",1,"a".repeat(64)))
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            val updates=Channel<List<DiaryDraft>>(Channel.UNLIMITED)
            val observer=launch(Dispatchers.Default) { repository.observeDrafts().collect { updates.send(it) } }
            try {
                assertTrue(withTimeout(5_000) { updates.receive() }.single().media.isEmpty())
                dao.insertDraftMedia(listOf(DiaryDraftMediaEntity("draft","photo",0)))
                val updated=withTimeout(5_000) { var next=updates.receive();while(next.single().media.isEmpty())next=updates.receive();next }
                assertEquals(listOf("photo"),updated.single().media.map { it.id })
            } finally { observer.cancelAndJoin();updates.close() }
        }
    }

    @Test fun readingEntriesUsesBoundedQueriesAsRecordsGrow(): Unit = runBlocking {
        val queries=CopyOnWriteArrayList<String>()
        val database=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE)
            .setQueryCallback(object:RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery:String,bindArgs:List<Any?>) { queries.add(sqlQuery) }
            },Executor { it.run() }).build()
        try {
            val dao=database.diaryDao()
            dao.insertMedia(DiaryMediaEntity("photo","image/jpeg",1,"a".repeat(64)))
            repeat(20) { index ->
                val id=index.toLong()+1
                dao.insertEntry(DiaryEntryEntity(id,0,"记录$id","正文",null,id,id,1))
                dao.insertEntryMedia(listOf(DiaryEntryMediaEntity(id,"photo",0)))
            }
            val repository=RoomDiaryRepository(database,MaintenanceCoordinator(DataGenerationRepositoryImpl(database)),Clock.systemUTC())
            queries.clear()
            val entries=repository.observeEntries().first()
            assertEquals(20,entries.size)
            assertTrue(entries.all { it.media.single().id=="photo" })
            val contentQueries=queries.filter { it.trimStart().startsWith("SELECT",ignoreCase=true) && it.contains("diary_",ignoreCase=true) }
            assertTrue(contentQueries.size<=3,"Reading 20 entries must batch attachment lookups; executed ${contentQueries.size} queries")
            queries.clear()
            assertEquals(20,repository.readContent().entries.size)
            val snapshotQueries=queries.count { it.trimStart().startsWith("SELECT",ignoreCase=true) && it.contains("diary_",ignoreCase=true) }
            assertTrue(snapshotQueries<=4,"Entries and drafts must use bounded batched queries; executed $snapshotQueries queries")
            println("Diary reads for 20 entries: ${contentQueries.size} entry SELECTs, $snapshotQueries combined snapshot SELECTs")
        } finally { database.close() }
    }

    @Test fun entriesAndObservationUseRecordDateThenCreationTimeThenId(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val dao=fixture.database.diaryDao()
            val records=listOf(Triple(1L,"2026-10-20",1L),Triple(2L,"2026-10-19",30L),
                Triple(3L,"2026-10-01",100L),Triple(4L,"2026-10-19",40L),Triple(5L,"2026-10-19",40L))
            records.forEach { (id,date,created) ->
                dao.insertEntry(DiaryEntryEntity(id,LocalDate.parse(date).toEpochDay(),"记录$id","正文",null,created,created,1))
            }
            val expected=listOf(1L,5L,4L,2L,3L)
            assertEquals(expected,dao.entries().map { it.id })
            assertEquals(expected,dao.observeEntries().first().map { it.id })
            val repository=RoomDiaryRepository(fixture.database,MaintenanceCoordinator(DataGenerationRepositoryImpl(fixture.database)),Clock.systemUTC())
            assertEquals(expected,repository.observeEntries().first().map { it.id })
            val draft=repository.createDraft(DataGeneration(0),LocalDate.parse("2026-10-20"),3)
            val edited=repository.saveDraft(draft.copy(date=LocalDate.parse("2026-10-20")),draft.revision,DataGeneration(0))
            repository.commitDraft(edited.id,edited.revision,DataGeneration(0))
            assertEquals(listOf(3L,1L,5L,4L,2L),repository.observeEntries().first().map { it.id })
            assertEquals(100L,dao.entry(3)!!.createdAt)
        }
    }
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
