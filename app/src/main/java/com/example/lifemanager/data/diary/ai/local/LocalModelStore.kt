package com.example.lifemanager.data.diary.ai.local

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReadyLocalModel(val spec: LocalModelSpec, val file: File) {
    val id: String get() = spec.id
    val sha256: String get() = spec.sha256
    val contextTokens: Int get() = spec.contextTokens
    val bytes: Long get() = spec.expectedBytes
}

class LocalModelLease internal constructor(
    val model: ReadyLocalModel,
    val descriptor: ParcelFileDescriptor,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val modelId: String get() = model.id
    val sha256: String get() = model.sha256
    val contextTokens: Int get() = model.contextTokens
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { descriptor.close() } finally { release() }
        }
    }
}

enum class LocalModelFailure { BUSY, NOT_READY, STALE_MODEL, INVALID_MODEL, INSUFFICIENT_SPACE, UNSAFE_DIRECTORY }

class LocalModelStoreException(val reason: LocalModelFailure, message: String) : IOException(message)

/** Context construction always uses an app-private, Android-backup-excluded directory. */
class LocalModelStore(
    private val directory: File,
    private val catalog: List<LocalModelSpec> = LocalModelCatalog.models,
    private val availableBytes: () -> Long = { directory.usableSpace },
    private val reserveBytes: Long = 64L * 1024 * 1024,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "diary-ai-models"))
    private val root = directory.canonicalFile
    private val specs = catalog.associateBy { it.id }
    private val state = states.computeIfAbsent(root.path) { DirectoryState() }

    init {
        require(specs.size == catalog.size && reserveBytes >= 0)
        if (Files.isSymbolicLink(directory.toPath())) failure(LocalModelFailure.UNSAFE_DIRECTORY)
        ensureDirectory()
        synchronized(state) {
            if (state.transfer == null) {
                val names = catalog.map { Regex("\\.${Regex.escape(it.id)}\\.[0-9a-fA-F-]{36}(?:\\.ready)?\\.part") }
                root.listFiles().orEmpty().filter { file -> names.any { it.matches(file.name) } }.forEach { file ->
                    val owned = child(file.name)
                    if (owned.isFile && !owned.delete()) throw IOException("无法清理中断的模型导入")
                }
            }
        }
    }

    suspend fun readyModels(): List<ReadyLocalModel> = withContext(Dispatchers.IO) {
        synchronized(state) { catalog.mapNotNull(::readyInternal) }
    }

    suspend fun readyModel(modelId: String): ReadyLocalModel? = withContext(Dispatchers.IO) {
        synchronized(state) { specs[modelId]?.let(::readyInternal) }
    }
    suspend fun importModel(
        modelId: String,
        open: () -> InputStream,
        progress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ReadyLocalModel = withContext(Dispatchers.IO) {
        val spec = specs[modelId] ?: failure(LocalModelFailure.INVALID_MODEL)
        val job = currentCoroutineContext()[Job]!!
        val transfer = Transfer(modelId)
        synchronized(state) {
            ensureDirectory()
            if (state.transfer != null || state.leases.getOrDefault(modelId, 0) != 0) failure(LocalModelFailure.BUSY)
            space(spec.expectedBytes)
            state.transfer = transfer
        }
        val temporary = child(".${spec.id}.${UUID.randomUUID()}.part")
        try {
            checkCancelled(transfer, job)
            val input = open()
            transfer.input = input
            input.use {
                coroutineScope {
                    // A child waiting for cancellation closes a provider's blocked
                    // read even when that provider ignores Thread.interrupt().
                    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { runCatching { input.close() } }
                    }
                    try {
                        copyVerified(spec, input, temporary, transfer, job, progress)
                    } finally { closer.cancel() }
                }
            }
            checkCancelled(transfer, job)
            GgufReader(temporary) { checkCancelled(transfer, job) }.use { it.validate(spec) }
            synchronized(state) {
                checkCancelled(transfer, job)
                val target = modelFile(spec)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                publishMarker(spec, transfer, job)
                state.pendingDeletes.remove(modelId)
                ReadyLocalModel(spec, target)
            }
        } catch (error: Exception) {
            if (transfer.cancelled.get() || !job.isActive) {
                throw CancellationException("模型导入已取消").apply { initCause(error) }
            }
            throw error
        } finally {
            runCatching { transfer.input?.close() }
            if (temporary.exists()) temporary.delete()
            synchronized(state) { if (state.transfer === transfer) state.transfer = null }
        }
    }

    suspend fun cancelImport(): Unit = withContext(Dispatchers.IO) {
        val input = synchronized(state) {
            state.transfer?.let { it.cancelled.set(true); it.input }
        }
        runCatching { input?.close() }
        Unit
    }

    suspend fun deleteModel(modelId: String): Unit = withContext(Dispatchers.IO) {
        val spec = specs[modelId] ?: return@withContext
        val input = synchronized(state) {
            val marker = markerFile(spec)
            if (marker.exists() && !marker.delete()) throw IOException("无法撤销模型就绪状态")
            state.pendingDeletes.add(modelId)
            val active = state.transfer?.takeIf { it.modelId == modelId }
            active?.cancelled?.set(true)
            if (state.leases.getOrDefault(modelId, 0) == 0) {
                val file = modelFile(spec)
                if (file.exists() && !file.delete()) throw IOException("无法删除模型文件")
                state.pendingDeletes.remove(modelId)
            }
            active?.input
        }
        runCatching { input?.close() }
        Unit
    }

    suspend fun acquire(modelId: String, expectedSha256: String): LocalModelLease = withContext(Dispatchers.IO) {
        val lease = synchronized(state) {
            val spec = specs[modelId] ?: failure(LocalModelFailure.NOT_READY)
            if (expectedSha256 != spec.sha256) failure(LocalModelFailure.STALE_MODEL)
            val ready = readyInternal(spec) ?: failure(LocalModelFailure.NOT_READY)
            val descriptor = ParcelFileDescriptor.open(ready.file, ParcelFileDescriptor.MODE_READ_ONLY)
            state.leases[modelId] = state.leases.getOrDefault(modelId, 0) + 1
            LocalModelLease(ready, descriptor) { releaseLease(spec) }
        }
        try {
            val job = currentCoroutineContext()[Job]!!
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            ParcelFileDescriptor.AutoCloseInputStream(lease.descriptor.dup()).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    job.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read <= 0 || count > lease.model.bytes - read) failure(LocalModelFailure.INVALID_MODEL)
                    count += read
                    digest.update(buffer, 0, read)
                }
                // dup shares the offset with the owned FD; native receives byte0.
                input.channel.position(0)
            }
            if (count != lease.model.bytes || hex(digest.digest()) != lease.sha256) failure(LocalModelFailure.INVALID_MODEL)
            GgufReader(lease.model.file) { job.ensureActive() }.use { it.validate(lease.model.spec) }
            synchronized(state) {
                if (readyInternal(lease.model.spec) == null) failure(LocalModelFailure.NOT_READY)
                job.ensureActive()
            }
            lease
        } catch (error: Exception) {
            lease.close()
            throw error
        }
    }

    private fun copyVerified(spec: LocalModelSpec, input: InputStream, target: File, transfer: Transfer,
                             job: Job, progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var zeroReads = 0
        check(target.createNewFile())
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancelled(transfer, job)
                space(spec.expectedBytes - count)
                val requested = minOf(buffer.size.toLong(), spec.expectedBytes - count + 1).toInt()
                val read = input.read(buffer, 0, requested)
                checkCancelled(transfer, job)
                if (read == -1) break
                if (read == 0) {
                    if (++zeroReads > 16) failure(LocalModelFailure.INVALID_MODEL)
                    continue
                }
                if (read !in 1..requested || count > spec.expectedBytes - read) failure(LocalModelFailure.INVALID_MODEL)
                zeroReads = 0
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                count += read
                progress(count, spec.expectedBytes)
            }
            if (count != spec.expectedBytes || hex(digest.digest()) != spec.sha256) failure(LocalModelFailure.INVALID_MODEL)
            output.flush()
            output.fd.sync()
        }
    }

    private fun publishMarker(spec: LocalModelSpec, transfer: Transfer, job: Job) {
        val temporary = child(".${spec.id}.${UUID.randomUUID()}.ready.part")
        try {
            check(temporary.createNewFile())
            FileOutputStream(temporary).use { output ->
                output.write((spec.sha256 + "\n").toByteArray(Charsets.US_ASCII))
                output.flush()
                output.fd.sync()
            }
            checkCancelled(transfer, job)
            Files.move(temporary.toPath(), markerFile(spec).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (temporary.exists()) temporary.delete() }
    }

    private fun releaseLease(spec: LocalModelSpec) = synchronized(state) {
        val remaining = state.leases.getOrDefault(spec.id, 1) - 1
        if (remaining > 0) state.leases[spec.id] = remaining else state.leases.remove(spec.id)
        if (remaining == 0 && spec.id in state.pendingDeletes && !markerFile(spec).exists()) {
            val file = modelFile(spec)
            if (!file.exists() || file.delete()) state.pendingDeletes.remove(spec.id)
        }
        Unit
    }

    private fun readyInternal(spec: LocalModelSpec): ReadyLocalModel? {
        val marker = markerFile(spec)
        val file = modelFile(spec)
        if (!marker.isFile || marker.length() != 65L || !file.isFile || file.length() != spec.expectedBytes) return null
        if (marker.readText(Charsets.US_ASCII) != spec.sha256 + "\n") return null
        return ReadyLocalModel(spec, file)
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(root.toPath())) failure(LocalModelFailure.UNSAFE_DIRECTORY)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建私有模型目录")
    }

    private fun child(name: String): File {
        ensureDirectory()
        return File(root, name).also {
            if (it.parentFile != root || Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != root) {
                failure(LocalModelFailure.UNSAFE_DIRECTORY)
            }
        }
    }
    private fun markerFile(spec: LocalModelSpec) = child(spec.id + ".ready")
    private fun modelFile(spec: LocalModelSpec) = child(spec.id + "-" + spec.sha256 + ".gguf")
    private fun space(remaining: Long) {
        if (remaining > Long.MAX_VALUE - reserveBytes || availableBytes() < remaining + reserveBytes) {
            failure(LocalModelFailure.INSUFFICIENT_SPACE)
        }
    }
    private fun checkCancelled(transfer: Transfer, job: Job) {
        job.ensureActive()
        if (transfer.cancelled.get()) throw CancellationException("模型导入已取消")
    }

    private class Transfer(val modelId: String) {
        val cancelled = AtomicBoolean(false)
        @Volatile var input: InputStream? = null
    }
    private class DirectoryState {
        var transfer: Transfer? = null
        val leases = mutableMapOf<String, Int>()
        val pendingDeletes = mutableSetOf<String>()
    }
    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private val states = ConcurrentHashMap<String, DirectoryState>()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun failure(reason: LocalModelFailure): Nothing = throw LocalModelStoreException(reason, when (reason) {
            LocalModelFailure.BUSY -> "另一个模型操作或推理正在进行"
            LocalModelFailure.NOT_READY -> "模型尚未准备好"
            LocalModelFailure.STALE_MODEL -> "模型已变更，请重新预览"
            LocalModelFailure.INVALID_MODEL -> "模型文件格式、架构、长度或摘要不符"
            LocalModelFailure.INSUFFICIENT_SPACE -> "剩余存储空间不足，旧模型已保留"
            LocalModelFailure.UNSAFE_DIRECTORY -> "模型私有目录不可用"
        })
    }

    /** Bounded GGUFv3 directory validation, in addition to the catalog SHA256. */
    private class GgufReader(file: File, private val checkCancelled: () -> Unit) : Closeable {
        private val input = BoundedModelReader(file)
        private val length = input.length()
        private val headerLimit = minOf(length, 32L * 1024 * 1024)
        override fun close() = input.close()
        private fun number(width: Int): Long {
            checkCancelled()
            if (input.filePointer > headerLimit - width) failure(LocalModelFailure.INVALID_MODEL)
            var value = 0L
            repeat(width) { value = value or (input.readUnsignedByte().toLong() shl (it * 8)) }
            if (value < 0) failure(LocalModelFailure.INVALID_MODEL)
            return value
        }
        private fun skip(bytes: Long) {
            checkCancelled()
            if (bytes < 0 || input.filePointer > headerLimit - bytes) failure(LocalModelFailure.INVALID_MODEL)
            input.seek(input.filePointer + bytes)
        }
        private fun string(): String {
            val size = number(8)
            if (size > 1024 * 1024 || input.filePointer > headerLimit - size) failure(LocalModelFailure.INVALID_MODEL)
            val bytes = ByteArray(size.toInt()).also(input::readFully)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { failure(LocalModelFailure.INVALID_MODEL) }
        }
        private fun skipValue(type: Long) {
            when (type.toInt()) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> { val size = number(8); if (size > 1024 * 1024) failure(LocalModelFailure.INVALID_MODEL); skip(size) }
                9 -> {
                    val child = number(4)
                    val count = number(8)
                    if (child == 9L || count > 500_000) failure(LocalModelFailure.INVALID_MODEL)
                    repeat(count.toInt()) { skipValue(child) }
                }
                else -> failure(LocalModelFailure.INVALID_MODEL)
            }
        }
        fun validate(spec: LocalModelSpec) {
            if (number(4) != 0x46554747L || number(4) != 3L) failure(LocalModelFailure.INVALID_MODEL)
            val tensors = number(8)
            val metadata = number(8)
            if (tensors !in 1..100_000L || metadata !in 1..1024L) failure(LocalModelFailure.INVALID_MODEL)
            var architecture: String? = null
            var fileType: Long? = null
            var alignment = 32L
            val keys = mutableSetOf<String>()
            repeat(metadata.toInt()) {
                val key = string()
                if (key.length > 1024 || !keys.add(key)) failure(LocalModelFailure.INVALID_MODEL)
                val type = number(4)
                when (key) {
                    "general.architecture" -> { if (type != 8L) failure(LocalModelFailure.INVALID_MODEL); architecture = string() }
                    "general.file_type" -> { if (type != 4L) failure(LocalModelFailure.INVALID_MODEL); fileType = number(4) }
                    "general.alignment" -> { if (type != 4L) failure(LocalModelFailure.INVALID_MODEL); alignment = number(4) }
                    else -> skipValue(type)
                }
            }
            val expectedType = when (spec.quantization) { "Q8_0" -> 7L; "Q4_K_M" -> 15L; "Q4_0" -> 2L; else -> -1L }
            if (architecture != spec.architecture || fileType != expectedType || alignment !in 1..4096L ||
                alignment and (alignment - 1) != 0L) failure(LocalModelFailure.INVALID_MODEL)
            val names = mutableSetOf<String>()
            val ranges = mutableListOf<Pair<Long, Long>>()
            repeat(tensors.toInt()) {
                val name = string()
                if (name.isEmpty() || name.length > 1024 || !names.add(name)) failure(LocalModelFailure.INVALID_MODEL)
                val dimensions = number(4)
                if (dimensions !in 1..4L) failure(LocalModelFailure.INVALID_MODEL)
                var elements = 1L
                repeat(dimensions.toInt()) {
                    val size = number(8)
                    if (size == 0L || elements > Long.MAX_VALUE / size) failure(LocalModelFailure.INVALID_MODEL)
                    elements *= size
                }
                val type = number(4)
                val offset = number(8)
                val block = when (type.toInt()) {
                    0 -> 1L to 4L; 1, 30 -> 1L to 2L; 2 -> 32L to 18L; 3 -> 32L to 20L
                    6 -> 32L to 22L; 7 -> 32L to 24L; 8 -> 32L to 34L; 9 -> 32L to 40L
                    10 -> 256L to 84L; 11 -> 256L to 110L; 12 -> 256L to 144L
                    13 -> 256L to 176L; 14 -> 256L to 210L; 15 -> 256L to 292L
                    else -> failure(LocalModelFailure.INVALID_MODEL)
                }
                if (elements % block.first != 0L || elements / block.first > Long.MAX_VALUE / block.second || offset % alignment != 0L) {
                    failure(LocalModelFailure.INVALID_MODEL)
                }
                ranges.add(offset to elements / block.first * block.second)
            }
            val start = (input.filePointer + alignment - 1) / alignment * alignment
            var end = 0L
            for ((offset, size) in ranges.sortedBy { it.first }) {
                if (offset < end || start > length || offset > length - start || size > length - start - offset) {
                    failure(LocalModelFailure.INVALID_MODEL)
                }
                end = offset + size
            }
        }
    }
}
