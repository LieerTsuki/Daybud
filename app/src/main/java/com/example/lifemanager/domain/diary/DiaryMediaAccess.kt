package com.example.lifemanager.domain.diary

import java.io.Closeable
import java.io.File

/** Keeps an imported immutable file alive until its draft reference is durable. */
data class DiaryImportedAttachment(val media: DiaryMedia, val pin: Closeable)

/** Platform adapter owns content-provider access and media validation. */
interface DiaryMediaAccess {
    suspend fun importSource(source: String, remainingBytes: Long): DiaryImportedAttachment
    fun file(media: DiaryMedia): File
}
