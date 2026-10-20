package com.example.lifemanager.data.diary

import com.example.lifemanager.domain.diary.DiaryMedia
import com.example.lifemanager.domain.diary.DiaryRules
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DiaryMediaPin internal constructor(private val release: () -> Unit) : Closeable {
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; release() } }
}
data class DiaryImportedMedia(val media: DiaryMedia, val pin: DiaryMediaPin)
class DiaryPreparedMedia internal constructor(val media: DiaryMedia, internal val staging: File, internal val owner: FileDiaryStorage) : Closeable {
    override fun close() { staging.delete() }
}

/** Lock order: global maintenance admission → this gate → Room transaction. Files are immutable. */
class FileDiaryStorage(private val directory: File) {
    private val gate = Mutex()
    private val pins = mutableMapOf<String, Int>()
    init {
        val canonical=directory.canonicalPath
        synchronized(cleanedDirectories) {
            if(cleanedDirectories.add(canonical)) directory.listFiles()?.forEach { file ->
                if(file.isFile && file.name.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.part"))) file.delete()
            }
        }
    }
    suspend fun <T> withGate(block: suspend () -> T): T = gate.withLock { block() }

    suspend fun importPinned(stream: InputStream, mimeType: String, maxBytes: Long = Long.MAX_VALUE): DiaryImportedMedia {
        var handedOff:DiaryImportedMedia?=null
        return try { withContext(Dispatchers.IO) {
        withGate {
            val limit = try { minOf(DiaryRules.mediaLimit(mimeType), maxBytes).also { require(it > 0) { "附件已达容量上限" } } }
                catch (error: Throwable) { stream.close(); throw error }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建附件目录" }
            val id = UUID.randomUUID().toString()
            val temporary = File(directory, "$id.part")
            val target = File(directory, id)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                stream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - size + 1).toInt())
                            if (read < 0) break
                            if (read == 0) { // A broken provider must not spin indefinitely.
                                val byte = input.read()
                                if (byte < 0) break
                                require(size < limit) { "附件超过容量限制" }
                                output.write(byte); digest.update(byte.toByte()); size++
                            } else {
                                require(read.toLong() <= limit - size) { "附件超过容量限制" }
                                output.write(buffer, 0, read); digest.update(buffer, 0, read); size += read
                            }
                        }
                        require(size > 0) { "附件为空或无法读取" }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                check(!target.exists() && temporary.renameTo(target)) { "无法完成附件导入" }
                val media = DiaryMedia(id, mimeType, size, digest.digest().joinToString("") { "%02x".format(it) })
                DiaryImportedMedia(media, pin(listOf(media))).also { handedOff=it }
            } catch (error: Throwable) { temporary.delete(); target.delete(); throw error }
        }
    } } catch(error:Throwable) { handedOff?.pin?.close(); throw error } finally { stream.close() }
    }

    /** Caller holds gate while fixing the DB snapshot and obtaining these pins. */
    fun pin(media: List<DiaryMedia>): DiaryMediaPin {
        val ids = media.map { validatedId(it.id) }.distinct()
        synchronized(pins) { ids.forEach { pins[it] = (pins[it] ?: 0) + 1 } }
        return DiaryMediaPin { synchronized(pins) { ids.forEach { id -> val count = pins.getValue(id) - 1; if (count == 0) pins.remove(id) else pins[id] = count } } }
    }

    fun file(media: DiaryMedia): File = File(directory, validatedId(media.id))
    fun open(media: DiaryMedia): InputStream = FileInputStream(file(media))
    fun isAvailable(media: DiaryMedia): Boolean = file(media).let { it.isFile && it.length() == media.bytes }

    /** Stream preparation is outside the gate; promotion and the DB commit use the shared gate. */
    fun prepareRestore(media: DiaryMedia, input: InputStream): DiaryPreparedMedia {
        val staging = File(directory, "${UUID.randomUUID()}.part")
        try {
            DiaryRules.validateMedia(listOf(media))
            validatedId(media.id)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建附件目录" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            input.use { source ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer,0,minOf(buffer.size.toLong(),media.bytes-size+1).toInt())
                        if (read < 0) break
                        if (read == 0) {
                            val byte = source.read()
                            if (byte < 0) break
                            require(size < media.bytes) { "附件大小不匹配" }
                            output.write(byte); digest.update(byte.toByte()); size++
                        } else {
                            require(read.toLong() <= media.bytes-size) { "附件大小不匹配" }
                            output.write(buffer,0,read); digest.update(buffer,0,read); size+=read
                        }
                    }
                    output.fd.sync()
                }
            }
            require(size == media.bytes && digest.digest().joinToString("") { "%02x".format(it) } == media.sha256) { "附件摘要或大小不匹配" }
            return DiaryPreparedMedia(media,staging,this)
        } catch (error: Throwable) { input.close(); staging.delete(); throw error }
    }

    /** Caller holds gate until the transaction references every promoted file. Never overwrites. */
    fun promotePrepared(prepared: DiaryPreparedMedia): DiaryMediaPin {
        require(prepared.owner === this && prepared.staging.isFile) { "附件准备结果无效" }
        val target = file(prepared.media)
        if (target.exists()) {
            require(target.isFile && target.length() == prepared.media.bytes) { "同标识附件内容冲突" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(target).use { input ->
                val buffer=ByteArray(64*1024)
                while(true) { val read=input.read(buffer); if(read<0) break; digest.update(buffer,0,read) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == prepared.media.sha256) { "同标识附件摘要冲突" }
            prepared.close()
        } else check(prepared.staging.renameTo(target)) { "无法完成附件恢复" }
        return pin(listOf(prepared.media))
    }

    /** Caller holds gate; referenced IDs were read under the same gate after the committing transaction. */
    fun garbageCollect(referencedIds: Set<String>) {
        val protected = synchronized(pins) { pins.keys.toSet() }
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.matches(Regex("[a-f0-9-]{36}")) && file.name !in referencedIds && file.name !in protected) file.delete()
        }
    }

    private fun validatedId(id: String): String {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "附件标识无效" }
        return id
    }
    private companion object { val cleanedDirectories=mutableSetOf<String>() }
}
