package com.example.lifemanager.domain.diary

import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Normal operations own global admission. Maintenance methods require the caller's gate and transaction. */
interface DiaryRepository {
    /** Signals only; consumers read and publish content under their generation admission. */
    fun observeChanges(): Flow<Unit>
    /** Entries and drafts from one database transaction. */
    suspend fun readContent(): DiaryContentSnapshot
    fun observeEntries(): Flow<List<DiaryEntry>>
    fun observeDrafts(): Flow<List<DiaryDraft>>
    fun observeReviews(): Flow<List<DiaryReview>>
    suspend fun readEntry(id: Long): DiaryEntry?
    suspend fun readDraft(id: String): DiaryDraft?
    suspend fun createDraft(expected: DataGeneration, date: LocalDate, entryId: Long? = null): DiaryDraft
    suspend fun saveDraft(draft: DiaryDraft, expectedRevision: Long, expectedGeneration: DataGeneration): DiaryDraft
    suspend fun commitDraft(id: String, expectedRevision: Long, expectedGeneration: DataGeneration): DiaryEntry
    suspend fun discardDraft(id: String, expectedRevision: Long, expectedGeneration: DataGeneration)
    suspend fun deleteEntry(id: Long, expectedRevision: Long, expectedGeneration: DataGeneration)
    suspend fun snapshotRange(from: LocalDate, to: LocalDate, expectedGeneration: DataGeneration): DiarySnapshot
    suspend fun saveReview(review: DiaryReview, snapshot: DiarySnapshot): DiaryReview
    suspend fun deleteReview(id: String, expectedGeneration: DataGeneration)
    suspend fun backupSnapshotWithinExistingTransaction(): DiaryBackupSnapshot
    suspend fun replaceWithinExistingTransaction(snapshot: DiaryBackupSnapshot)
    suspend fun clearWithinExistingTransaction()
}
