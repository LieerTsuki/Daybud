package com.example.lifemanager.data.diary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC,id DESC") fun observeEntries(): Flow<List<DiaryEntryEntity>>
    @Query("SELECT * FROM diary_drafts ORDER BY updatedAt DESC,id DESC") fun observeDrafts(): Flow<List<DiaryDraftEntity>>
    @Query("SELECT * FROM diary_reviews ORDER BY createdAt DESC,id DESC") fun observeReviews(): Flow<List<DiaryReviewEntity>>
    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC,id DESC") suspend fun entries(): List<DiaryEntryEntity>
    @Query("SELECT * FROM diary_drafts ORDER BY updatedAt DESC,id DESC") suspend fun drafts(): List<DiaryDraftEntity>
    @Query("SELECT * FROM diary_media ORDER BY id") suspend fun media(): List<DiaryMediaEntity>
    @Query("SELECT * FROM diary_reviews ORDER BY createdAt DESC,id DESC") suspend fun reviews(): List<DiaryReviewEntity>
    @Query("SELECT * FROM diary_entries WHERE id=:id") suspend fun entry(id: Long): DiaryEntryEntity?
    @Query("SELECT * FROM diary_drafts WHERE id=:id") suspend fun draft(id: String): DiaryDraftEntity?
    @Query("SELECT * FROM diary_media WHERE id=:id") suspend fun medium(id: String): DiaryMediaEntity?
    @Query("SELECT * FROM diary_entry_media WHERE entryId=:id ORDER BY position") suspend fun entryMedia(id: Long): List<DiaryEntryMediaEntity>
    @Query("SELECT * FROM diary_draft_media WHERE draftId=:id ORDER BY position") suspend fun draftMedia(id: String): List<DiaryDraftMediaEntity>
    @Query("SELECT * FROM diary_review_sources WHERE reviewId=:id ORDER BY entryId") suspend fun sources(id: String): List<DiaryReviewSourceEntity>
    @Query("SELECT mediaId FROM diary_entry_media UNION SELECT mediaId FROM diary_draft_media") suspend fun referencedMediaIds(): List<String>
    @Insert suspend fun insertEntry(value: DiaryEntryEntity): Long
    @Update suspend fun updateEntry(value: DiaryEntryEntity): Int
    @Insert suspend fun insertDraft(value: DiaryDraftEntity)
    @Update suspend fun updateDraft(value: DiaryDraftEntity): Int
    @Insert suspend fun insertMedia(value: DiaryMediaEntity)
    @Insert suspend fun insertEntryMedia(value: List<DiaryEntryMediaEntity>)
    @Insert suspend fun insertDraftMedia(value: List<DiaryDraftMediaEntity>)
    @Insert suspend fun insertReview(value: DiaryReviewEntity)
    @Insert suspend fun insertSources(value: List<DiaryReviewSourceEntity>)
    @Query("DELETE FROM diary_entry_media WHERE entryId=:id") suspend fun deleteEntryMedia(id: Long)
    @Query("DELETE FROM diary_draft_media WHERE draftId=:id") suspend fun deleteDraftMedia(id: String)
    @Query("DELETE FROM diary_entries WHERE id=:id") suspend fun deleteEntry(id: Long): Int
    @Query("DELETE FROM diary_drafts WHERE id=:id") suspend fun deleteDraft(id: String): Int
    @Query("DELETE FROM diary_reviews WHERE id=:id") suspend fun deleteReview(id: String)
    @Query("DELETE FROM diary_reviews WHERE id IN (SELECT reviewId FROM diary_review_sources WHERE entryId=:id)") suspend fun deleteReviewsForEntry(id: Long)
    @Query("UPDATE diary_reviews SET needsUpdate=1 WHERE id IN (SELECT reviewId FROM diary_review_sources WHERE entryId=:id)") suspend fun invalidateReviews(id: Long)
    @Query("DELETE FROM diary_media WHERE id NOT IN (SELECT mediaId FROM diary_entry_media UNION SELECT mediaId FROM diary_draft_media)") suspend fun pruneMedia()
    @Query("DELETE FROM diary_reviews") suspend fun clearReviews()
    @Query("DELETE FROM diary_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM diary_entries") suspend fun clearEntries()
    @Query("DELETE FROM diary_media") suspend fun clearMedia()
}
