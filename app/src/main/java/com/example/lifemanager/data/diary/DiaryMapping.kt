package com.example.lifemanager.data.diary

import com.example.lifemanager.domain.diary.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

internal val diaryJson = Json { ignoreUnknownKeys = false; isLenient = false }
internal fun DiaryEntry.entity() = DiaryEntryEntity(id,date.toEpochDay(),title,body,mood?.name,createdAt.toEpochMilli(),updatedAt.toEpochMilli(),revision)
internal fun DiaryDraft.entity() = DiaryDraftEntity(id,entryId,baseRevision,date.toEpochDay(),title,body,mood?.name,createdAt.toEpochMilli(),updatedAt.toEpochMilli(),revision)
internal fun DiaryMedia.entity() = DiaryMediaEntity(id,mimeType,bytes,sha256)
internal fun DiaryMediaEntity.domain(position: Int = 0) = DiaryMedia(id,mimeType,bytes,sha256,position)
internal fun DiaryEntryEntity.domain(media: List<DiaryMedia>) = DiaryEntry(id,LocalDate.ofEpochDay(date),title,body,mood?.let(DiaryMood::valueOf),media,Instant.ofEpochMilli(createdAt),Instant.ofEpochMilli(updatedAt),revision)
internal fun DiaryDraftEntity.domain(media: List<DiaryMedia>) = DiaryDraft(id,entryId,baseRevision,LocalDate.ofEpochDay(date),title,body,mood?.let(DiaryMood::valueOf),media,Instant.ofEpochMilli(createdAt),Instant.ofEpochMilli(updatedAt),revision)
internal fun DiaryReview.entity() = DiaryReviewEntity(id,from.toEpochDay(),to.toEpochDay(),createdAt.toEpochMilli(),JsonArray(sections.map { buildJsonObject { put("title",it.title); put("text",it.text); put("sourceIds",JsonArray(it.sourceIds.map(::JsonPrimitive))) } }).toString(),needsUpdate,provider,model,inputHash)
internal fun DiaryReviewEntity.domain(sources: List<DiarySourceRevision>) = DiaryReview(id,LocalDate.ofEpochDay(fromDate),LocalDate.ofEpochDay(toDate),Instant.ofEpochMilli(createdAt),diaryJson.parseToJsonElement(sections).jsonArray.map {
    val value=it.jsonObject
    require(value.keys == setOf("title","text","sourceIds")) { "回顾段落字段无效" }
    DiaryReviewSection(value.getValue("title").jsonPrimitive.content,value.getValue("text").jsonPrimitive.content,value.getValue("sourceIds").jsonArray.map { source -> source.jsonPrimitive.long })
},sources,needsUpdate,provider,model,inputHash)
