package com.example.lifemanager.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema8 is finalized in one additive migration. Existing fifteen tables and sequences are untouched. */
class DiaryMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, mood TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_entries_date ON diary_entries(date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_entries_createdAt ON diary_entries(createdAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_drafts (id TEXT NOT NULL, entryId INTEGER, baseRevision INTEGER, date INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, mood TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES diary_entries(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_drafts_entryId ON diary_drafts(entryId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_media (id TEXT NOT NULL, mimeType TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_entry_media (entryId INTEGER NOT NULL, mediaId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(entryId,mediaId), FOREIGN KEY(entryId) REFERENCES diary_entries(id) ON DELETE CASCADE, FOREIGN KEY(mediaId) REFERENCES diary_media(id) ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_entry_media_mediaId ON diary_entry_media(mediaId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diary_entry_media_entryId_position ON diary_entry_media(entryId,position)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_draft_media (draftId TEXT NOT NULL, mediaId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(draftId,mediaId), FOREIGN KEY(draftId) REFERENCES diary_drafts(id) ON DELETE CASCADE, FOREIGN KEY(mediaId) REFERENCES diary_media(id) ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_draft_media_mediaId ON diary_draft_media(mediaId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diary_draft_media_draftId_position ON diary_draft_media(draftId,position)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_reviews (id TEXT NOT NULL, fromDate INTEGER NOT NULL, toDate INTEGER NOT NULL, createdAt INTEGER NOT NULL, sections TEXT NOT NULL, needsUpdate INTEGER NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, inputHash TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_reviews_createdAt ON diary_reviews(createdAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary_review_sources (reviewId TEXT NOT NULL, entryId INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(reviewId,entryId), FOREIGN KEY(reviewId) REFERENCES diary_reviews(id) ON DELETE CASCADE, FOREIGN KEY(entryId) REFERENCES diary_entries(id) ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_review_sources_entryId ON diary_review_sources(entryId)")
    }
}
