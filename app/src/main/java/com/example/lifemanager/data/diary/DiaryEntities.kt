package com.example.lifemanager.data.diary

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "diary_entries", indices = [Index("date"), Index("createdAt")])
data class DiaryEntryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val date: Long, val title: String, val body: String, val mood: String?, val createdAt: Long, val updatedAt: Long, val revision: Long)

@Entity(tableName = "diary_drafts", indices = [Index("entryId")], foreignKeys = [ForeignKey(entity = DiaryEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)])
data class DiaryDraftEntity(@PrimaryKey val id: String, val entryId: Long?, val baseRevision: Long?, val date: Long, val title: String, val body: String, val mood: String?, val createdAt: Long, val updatedAt: Long, val revision: Long)

@Entity(tableName = "diary_media")
data class DiaryMediaEntity(@PrimaryKey val id: String, val mimeType: String, val bytes: Long, val sha256: String)

@Entity(tableName = "diary_entry_media", primaryKeys = ["entryId", "mediaId"], indices = [Index("mediaId"), Index(value=["entryId", "position"], unique=true)], foreignKeys = [ForeignKey(entity=DiaryEntryEntity::class,parentColumns=["id"],childColumns=["entryId"],onDelete=ForeignKey.CASCADE), ForeignKey(entity=DiaryMediaEntity::class,parentColumns=["id"],childColumns=["mediaId"],onDelete=ForeignKey.RESTRICT)])
data class DiaryEntryMediaEntity(val entryId: Long, val mediaId: String, val position: Int)

@Entity(tableName = "diary_draft_media", primaryKeys = ["draftId", "mediaId"], indices = [Index("mediaId"), Index(value=["draftId", "position"], unique=true)], foreignKeys = [ForeignKey(entity=DiaryDraftEntity::class,parentColumns=["id"],childColumns=["draftId"],onDelete=ForeignKey.CASCADE), ForeignKey(entity=DiaryMediaEntity::class,parentColumns=["id"],childColumns=["mediaId"],onDelete=ForeignKey.RESTRICT)])
data class DiaryDraftMediaEntity(val draftId: String, val mediaId: String, val position: Int)

@Entity(tableName = "diary_reviews", indices = [Index("createdAt")])
data class DiaryReviewEntity(@PrimaryKey val id: String, val fromDate: Long, val toDate: Long, val createdAt: Long, val sections: String, val needsUpdate: Boolean, val provider: String, val model: String, val inputHash: String)

@Entity(tableName = "diary_review_sources", primaryKeys=["reviewId","entryId"], indices=[Index("entryId")], foreignKeys=[ForeignKey(entity=DiaryReviewEntity::class,parentColumns=["id"],childColumns=["reviewId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=DiaryEntryEntity::class,parentColumns=["id"],childColumns=["entryId"],onDelete=ForeignKey.RESTRICT)])
data class DiaryReviewSourceEntity(val reviewId: String, val entryId: Long, val revision: Long)
