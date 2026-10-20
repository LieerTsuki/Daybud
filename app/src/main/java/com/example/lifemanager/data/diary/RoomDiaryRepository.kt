package com.example.lifemanager.data.diary

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDiaryRepository(
    private val database: LifeManagerDatabase,
    private val maintenance: MaintenanceCoordinator,
    private val clock: Clock,
    private val storage: FileDiaryStorage? = null,
) : DiaryRepository {
    private val dao get() = database.diaryDao()

    override fun observeEntries(): Flow<List<DiaryEntry>> = dao.observeEntries().map { database.withTransaction { dao.entries().map { entry(it) } } }
    override fun observeDrafts(): Flow<List<DiaryDraft>> = dao.observeDrafts().map { database.withTransaction { dao.drafts().map { draft(it) } } }
    override fun observeReviews(): Flow<List<DiaryReview>> = dao.observeReviews().map { database.withTransaction { dao.reviews().map { review(it) } } }
    override suspend fun readEntry(id: Long): DiaryEntry? = database.withTransaction { dao.entry(id)?.let { entry(it) } }
    override suspend fun readDraft(id: String): DiaryDraft? = database.withTransaction { dao.draft(id)?.let { draft(it) } }

    override suspend fun createDraft(expected: DataGeneration, date: LocalDate, entryId: Long?): DiaryDraft = mutate(expected) {
        require(date <= LocalDate.now(clock)) { "日记日期不能晚于今天" }
        val source = entryId?.let { dao.entry(it)?.let { row -> entry(row) } ?: throw DiaryRevisionException() }
        val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val value = DiaryDraft(UUID.randomUUID().toString(),source?.id,source?.revision,source?.date ?: date,source?.title ?: "",source?.body ?: "",source?.mood,source?.media.orEmpty().toList(),now,now,1)
        dao.insertDraft(value.entity())
        dao.insertDraftMedia(value.media.mapIndexed { index, media -> DiaryDraftMediaEntity(value.id,media.id,index) })
        value
    }

    override suspend fun saveDraft(draft: DiaryDraft, expectedRevision: Long, expectedGeneration: DataGeneration): DiaryDraft = mutate(expectedGeneration) {
        val persisted = requireDraft(draft.id, expectedRevision)
        require(draft.entryId == persisted.entryId && draft.baseRevision == persisted.baseRevision) { "草稿来源不可更改" }
        require(draft.date <= LocalDate.now(clock)) { "日记日期不能晚于今天" }
        val media = draft.media.mapIndexed { position, value -> value.copy(position = position) }
        DiaryRules.validateMedia(media)
        media.forEach { addMedia(it) }
        val updated = draft.copy(media = media,createdAt=persisted.createdAt,updatedAt=maxOf(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),persisted.updatedAt),revision=nextRevision(persisted.revision))
        check(dao.updateDraft(updated.entity()) == 1)
        dao.deleteDraftMedia(updated.id)
        dao.insertDraftMedia(media.map { DiaryDraftMediaEntity(updated.id,it.id,it.position) })
        updated
    }

    override suspend fun commitDraft(id: String, expectedRevision: Long, expectedGeneration: DataGeneration): DiaryEntry = mutate(expectedGeneration) {
        val draft = requireDraft(id, expectedRevision)
        DiaryRules.validateContent(draft.date,draft.body,draft.media,LocalDate.now(clock))
        val previous = draft.entryId?.let { dao.entry(it) ?: throw DiaryRevisionException() }
        if (previous != null && previous.revision != draft.baseRevision) throw DiaryRevisionException()
        val now = maxOf(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),previous?.let { java.time.Instant.ofEpochMilli(it.updatedAt) } ?: draft.createdAt)
        val value = DiaryEntry(previous?.id ?: 0,draft.date,draft.title.trim().ifEmpty { "${draft.date} 日记" },draft.body,draft.mood,draft.media.toList(),previous?.let { java.time.Instant.ofEpochMilli(it.createdAt) } ?: now,now,previous?.revision?.let(::nextRevision) ?: 1)
        val entryId = if (previous == null) dao.insertEntry(value.entity()) else {
            check(dao.updateEntry(value.entity()) == 1)
            dao.invalidateReviews(previous.id)
            previous.id
        }
        dao.deleteEntryMedia(entryId)
        dao.insertEntryMedia(value.media.map { DiaryEntryMediaEntity(entryId,it.id,it.position) })
        check(dao.deleteDraft(id) == 1)
        value.copy(id=entryId)
    }

    override suspend fun discardDraft(id: String, expectedRevision: Long, expectedGeneration: DataGeneration): Unit = mutate(expectedGeneration) {
        requireDraft(id,expectedRevision)
        check(dao.deleteDraft(id) == 1)
    }

    override suspend fun deleteEntry(id: Long, expectedRevision: Long, expectedGeneration: DataGeneration): Unit = mutate(expectedGeneration) {
        val stored = dao.entry(id) ?: throw DiaryRevisionException()
        if (stored.revision != expectedRevision) throw DiaryRevisionException()
        // Entire derivative record is removed before the FK parent, including all its text.
        dao.deleteReviewsForEntry(id)
        check(dao.deleteEntry(id) == 1)
    }

    override suspend fun snapshotRange(from: LocalDate, to: LocalDate, expectedGeneration: DataGeneration): DiarySnapshot = maintenance.run(expectedGeneration) {
        database.withTransaction {
            checkGeneration(expectedGeneration)
            require(from <= to) { "日期范围无效" }
            DiarySnapshot(from,to,dao.entries().filter { it.date in from.toEpochDay()..to.toEpochDay() }.map { entry(it).copy(media=emptyList()) },expectedGeneration)
        }
    }

    override suspend fun saveReview(review: DiaryReview, snapshot: DiarySnapshot): DiaryReview = mutate(snapshot.generation) {
        DiaryBackupValidator.validateReviewSections(review)
        require(review.from == snapshot.from && review.to == snapshot.to && !review.needsUpdate) { "回顾范围无效" }
        val current = dao.entries().filter { it.date in snapshot.from.toEpochDay()..snapshot.to.toEpochDay() }.map { entry(it).copy(media=emptyList()) }
        if (current != snapshot.entries) throw DiaryRevisionException()
        val revisions = current.associate { it.id to it.revision }
        require(review.sources.isNotEmpty() && review.sources.map { it.entryId }.distinct().size == review.sources.size && review.sources.all { revisions[it.entryId] == it.revision }) { "回顾引用无效" }
        val ids = review.sources.map { it.entryId }.toSet()
        require(review.sections.isNotEmpty() && review.sections.all { it.title.isNotBlank() && it.text.isNotBlank() && it.sourceIds.all { id -> id in ids } }) { "回顾段落无效" }
        val detached = review.copy(createdAt=review.createdAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),sections=review.sections.map { it.copy(sourceIds=it.sourceIds.toList()) },sources=review.sources.toList())
        dao.insertReview(detached.entity())
        dao.insertSources(detached.sources.map { DiaryReviewSourceEntity(detached.id,it.entryId,it.revision) })
        detached
    }

    override suspend fun deleteReview(id: String, expectedGeneration: DataGeneration): Unit = mutate(expectedGeneration) { dao.deleteReview(id) }

    override suspend fun backupSnapshotWithinExistingTransaction(): DiaryBackupSnapshot {
        check(database.inTransaction()) { "日记快照必须在已有Room事务中执行" }
        val entries = dao.entries().map { entry(it) }
        val drafts = dao.drafts().map { draft(it) }
        val referenced = (entries.flatMap { it.media } + drafts.flatMap { it.media }).map { it.id }.toSet()
        val media = dao.media().filter { it.id in referenced }.map { it.domain() }
        val sequence = database.backupSnapshotDao().read(SimpleSQLiteQuery("SELECT seq FROM sqlite_sequence WHERE name='diary_entries'")).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        return DiaryBackupSnapshot(entries,drafts,media,dao.reviews().map { review(it) },sequence).also(DiaryBackupValidator::validate)
    }

    override suspend fun replaceWithinExistingTransaction(snapshot: DiaryBackupSnapshot) {
        check(database.inTransaction()) { "日记替换必须在已有Room事务中执行" }
        DiaryBackupValidator.validate(snapshot)
        clearWithinExistingTransaction()
        snapshot.media.forEach { dao.insertMedia(it.entity()) }
        snapshot.entries.forEach { dao.insertEntry(it.entity()); dao.insertEntryMedia(it.media.map { medium -> DiaryEntryMediaEntity(it.id,medium.id,medium.position) }) }
        snapshot.drafts.forEach { dao.insertDraft(it.entity()); dao.insertDraftMedia(it.media.map { medium -> DiaryDraftMediaEntity(it.id,medium.id,medium.position) }) }
        snapshot.reviews.forEach { dao.insertReview(it.entity()); dao.insertSources(it.sources.map { source -> DiaryReviewSourceEntity(it.id,source.entryId,source.revision) }) }
        val sql = database.openHelper.writableDatabase
        sql.execSQL("DELETE FROM sqlite_sequence WHERE name='diary_entries'")
        if (snapshot.entrySequence > 0) sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES('diary_entries',?)",arrayOf(snapshot.entrySequence))
    }

    override suspend fun clearWithinExistingTransaction() {
        check(database.inTransaction()) { "日记清空必须在已有Room事务中执行" }
        dao.clearReviews(); dao.clearDrafts(); dao.clearEntries(); dao.clearMedia()
        database.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name='diary_entries'")
    }

    private suspend fun entry(value: DiaryEntryEntity): DiaryEntry = value.domain(dao.entryMedia(value.id).map { requireNotNull(dao.medium(it.mediaId)) { "日记附件引用缺失" }.domain(it.position) })
    private suspend fun draft(value: DiaryDraftEntity): DiaryDraft = value.domain(dao.draftMedia(value.id).map { requireNotNull(dao.medium(it.mediaId)) { "草稿附件引用缺失" }.domain(it.position) })
    private suspend fun review(value: DiaryReviewEntity): DiaryReview = value.domain(dao.sources(value.id).map { DiarySourceRevision(it.entryId,it.revision) })
    private suspend fun requireDraft(id: String, revision: Long): DiaryDraft {
        val stored = dao.draft(id) ?: throw DiaryRevisionException()
        if (stored.revision != revision) throw DiaryRevisionException()
        return draft(stored)
    }
    private suspend fun addMedia(value: DiaryMedia) {
        val previous = dao.medium(value.id)
        if (previous == null) {
            require(storage == null || storage.isAvailable(value)) { "附件未完整导入" }
            dao.insertMedia(value.entity())
        } else require(previous == value.entity()) { "不可变附件元数据不一致" }
    }
    private fun nextRevision(revision: Long): Long { check(revision in 1 until Long.MAX_VALUE) { "日记修订已达上限" }; return revision + 1 }

    private suspend fun <T> mutate(expected: DataGeneration, operation: suspend () -> T): T = maintenance.run(expected) {
        suspend fun write(): T {
            val result = database.withTransaction {
                checkGeneration(expected)
                val value = operation()
                dao.pruneMedia()
                currentCoroutineContext().ensureActive()
                checkGeneration(expected)
                value
            }
            // Only after a successful commit; failure cannot delete still-referenced files.
            storage?.garbageCollect(dao.referencedMediaIds().toSet())
            return result
        }
        if (storage == null) write() else storage.withGate { write() }
    }
    private fun checkGeneration(expected: DataGeneration) {
        database.backupSnapshotDao().read(SimpleSQLiteQuery("SELECT id,generation FROM app_maintenance")).use { cursor ->
            check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 1L && cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER) { "本机世代记录缺失或损坏" }
            val value = cursor.getLong(1)
            check(value >= 0 && !cursor.moveToNext()) { "本机世代记录无效" }
            if (value != expected.value) throw StaleGenerationException()
        }
    }
}
