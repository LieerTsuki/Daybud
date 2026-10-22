package com.example.lifemanager.data.diary.ai.local

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class LocalModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes get() = tinyGguf()
    private val spec get() = fixtureSpec(bytes)
    private fun store(root: File, value: LocalModelSpec = spec, free: () -> Long = { Long.MAX_VALUE }) =
        LocalModelStore(root, listOf(value), free, reserveBytes = 64)

    @Test fun leaseCloseClosesOwnedReadonlyDescriptorAndReleasesOnlyOnce() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val raw = descriptor.fileDescriptor
        var released = 0
        val lease = LocalModelLease(ReadyLocalModel(spec, file), descriptor) { released++ }
        lease.close()
        lease.close()
        assertFalse(raw.valid())
        assertEquals(1, released)
    }

    @Test fun completedImportSurvivesStoreRecreationAndContainsExactOriginalBytes() = runBlocking {
        val root = temporary.newFolder()
        val ready = store(root).importModel(spec.id, { ByteArrayInputStream(bytes) })
        assertArrayEquals(bytes, ready.file.readBytes())
        assertEquals(spec.sha256, store(root).readyModel(spec.id)?.sha256)
        assertEquals(listOf(spec.id), store(root).readyModels().map { it.id })
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun truncatedOrOversizedStreamNeverReplacesReadyModel() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val old = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1))) {
            expectFailure(LocalModelFailure.INVALID_MODEL) { models.importModel(spec.id, { ByteArrayInputStream(bad) }) }
            assertArrayEquals(bytes, old.file.readBytes())
            assertNotNull(models.readyModel(spec.id))
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun sameLengthCorruptionDoesNotReplaceReadyModel() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = 99 }
        expectFailure(LocalModelFailure.INVALID_MODEL) { models.importModel(spec.id, { ByteArrayInputStream(corrupt) }) }
        assertArrayEquals(bytes, ready.file.readBytes())
    }

    @Test fun matchingDigestDoesNotMakeWrongArchitectureOrMalformedGgufAcceptable() = runBlocking {
        for (bad in listOf(tinyGguf("llama"), bytes.copyOf().also { it[0] = 0 }, bytes.dropLast(1).toByteArray())) {
            val root = temporary.newFolder()
            val metadata = fixtureSpec(bad)
            val models = store(root, metadata)
            expectFailure(LocalModelFailure.INVALID_MODEL) { models.importModel(metadata.id, { ByteArrayInputStream(bad) }) }
            assertNull(models.readyModel(metadata.id))
        }
    }

    @Test fun insufficientSpaceRefusesBeforeOpeningSourceAndCreatesNoReadyModel() = runBlocking {
        val root = temporary.newFolder()
        val opened = AtomicBoolean(false)
        val models = store(root, free = { bytes.size.toLong() + 63 })
        expectFailure(LocalModelFailure.INSUFFICIENT_SPACE) {
            models.importModel(spec.id, { opened.set(true); ByteArrayInputStream(bytes) })
        }
        assertFalse(opened.get())
        assertNull(models.readyModel(spec.id))
    }

    @Test fun storageExhaustionDuringCopyRemovesPartialAndPreservesOldModel() = runBlocking {
        val root = temporary.newFolder()
        var free = Long.MAX_VALUE
        val models = store(root, free = { free })
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        expectFailure(LocalModelFailure.INSUFFICIENT_SPACE) {
            models.importModel(spec.id, { ChunkedInput(bytes) }) { copied, _ -> if (copied > 0) free = 0 }
        }
        assertArrayEquals(bytes, ready.file.readBytes())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun cancelledCoroutineClosesBlockedInputAndNeverPublishesPartial() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val input = BlockingInput()
        val job = launch(Dispatchers.IO) { models.importModel(spec.id, { input }) }
        assertTrue(withContext(Dispatchers.IO) { input.entered.await(2, TimeUnit.SECONDS) })
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(input.closed.get())
        assertNull(models.readyModel(spec.id))
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun explicitCancelClosesBlockedInputAndNextImportCanSucceed() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val input = BlockingInput()
        val operation = async(Dispatchers.IO) { models.importModel(spec.id, { input }) }
        assertTrue(withContext(Dispatchers.IO) { input.entered.await(2, TimeUnit.SECONDS) })
        withTimeout(3000) { models.cancelImport() }
        try { operation.await(); fail("Cancelled transfer was published") } catch (_: CancellationException) { }
        assertTrue(input.closed.get())
        assertEquals(spec.sha256, models.importModel(spec.id, { ByteArrayInputStream(bytes) }).sha256)
    }

    @Test fun parallelImportIsRejectedWithoutOpeningSecondStream() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val input = BlockingInput()
        val operation = async(Dispatchers.IO) { models.importModel(spec.id, { input }) }
        assertTrue(withContext(Dispatchers.IO) { input.entered.await(2, TimeUnit.SECONDS) })
        var opened = false
        expectFailure(LocalModelFailure.BUSY) { models.importModel(spec.id, { opened = true; ByteArrayInputStream(bytes) }) }
        assertFalse(opened)
        models.cancelImport()
        try { operation.await() } catch (_: CancellationException) { }
        Unit
    }

    @Test fun deletingModelRevokesNewLeasesButExistingReadonlyLeaseStillReadsBytes() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        val lease = models.acquire(spec.id, spec.sha256)
        val duplicate = ParcelFileDescriptor.dup(lease.descriptor.fileDescriptor)
        models.deleteModel(spec.id)
        assertNull(models.readyModel(spec.id))
        expectFailure(LocalModelFailure.NOT_READY) { models.acquire(spec.id, spec.sha256) }
        ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { assertArrayEquals(bytes, it.readBytes()) }
        lease.close()
        lease.close()
        assertFalse(ready.file.exists())
    }

    @Test fun acquireRejectsStaleDigestAndPrivateFileCorruption() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        expectFailure(LocalModelFailure.STALE_MODEL) { models.acquire(spec.id, "0".repeat(64)) }
        ready.file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 88 })
        expectFailure(LocalModelFailure.INVALID_MODEL) { models.acquire(spec.id, spec.sha256) }
    }

    @Test fun everyAcquisitionRevalidatesDigestAfterPreviousSuccessfulLeases() = runBlocking {
        val models = store(temporary.newFolder())
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        repeat(2) {
            models.acquire(spec.id, spec.sha256).use { lease ->
                ParcelFileDescriptor.AutoCloseInputStream(lease.descriptor.dup()).use {
                    assertEquals('G'.code, it.read())
                }
            }
        }
        ready.file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 88 })
        expectFailure(LocalModelFailure.INVALID_MODEL) { models.acquire(spec.id, spec.sha256) }
    }

    @Test fun largeSkippedMetadataStillValidatesFollowingTensorDirectory() = runBlocking {
        val value = largeMetadataGguf()
        val metadata = fixtureSpec(value)
        val models = store(temporary.newFolder(), metadata)
        models.importModel(metadata.id, { ByteArrayInputStream(value) })
        models.acquire(metadata.id, metadata.sha256).use { assertTrue(it.descriptor.fileDescriptor.valid()) }

        // A matching catalog digest cannot legitimize an overlapping / out-of-file tensor range.
        val malformed = largeMetadataGguf(tensorOffset = 32)
        val badSpec = fixtureSpec(malformed)
        val badModels = store(temporary.newFolder(), badSpec)
        expectFailure(LocalModelFailure.INVALID_MODEL) { badModels.importModel(badSpec.id, { ByteArrayInputStream(malformed) }) }
    }

    @Test fun acquiredDescriptorRejectsWritesWithoutMutatingReadyBytes() = runBlocking {
        val models = store(temporary.newFolder())
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        models.acquire(spec.id, spec.sha256).use { lease ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(lease.descriptor.dup()).use { it.write(0) }
                fail("Readonly lease permitted writing")
            } catch (_: IOException) { }
        }
        assertArrayEquals(bytes, ready.file.readBytes())
    }

    @Test fun failingProviderReadPreservesReadyModelAndRemovesPartial() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val ready = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("Synthetic provider failure")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Synthetic provider failure")
        }
        try { models.importModel(spec.id, { failing }); fail("Provider failure accepted") }
        catch (_: IOException) { }
        assertArrayEquals(bytes, ready.file.readBytes())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun oversizedUnknownLengthSourceIsStoppedAfterAtMostExpectedBytesPlusOne() = runBlocking {
        val models = store(temporary.newFolder())
        var consumed = 0
        val infinite = object : InputStream() {
            override fun read(): Int = error("Bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 64 * 1024)
                consumed += length
                buffer.fill(1, offset, offset + length)
                return length
            }
        }
        expectFailure(LocalModelFailure.INVALID_MODEL) { models.importModel(spec.id, { infinite }) }
        assertTrue(consumed <= bytes.size + 1)
        assertNull(models.readyModel(spec.id))
    }

    @Test fun restartedStoreReclaimsOnlyItsAbandonedTransferFiles() {
        val root = temporary.newFolder()
        val abandoned = File(root, ".${spec.id}.${UUID.randomUUID()}.part").apply { writeText("unfinished") }
        val unrelated = File(root, "unrelated.part").apply { writeText("leave alone") }
        store(root)
        assertFalse(abandoned.exists())
        assertEquals("leave alone", unrelated.readText())
    }

    @Test fun deletingDuringBlockedImportCancelsItAndCannotRepublishDeletedModel() = runBlocking {
        val root = temporary.newFolder()
        val models = store(root)
        val old = models.importModel(spec.id, { ByteArrayInputStream(bytes) })
        val input = BlockingInput()
        val operation = async(Dispatchers.IO) { models.importModel(spec.id, { input }) }
        assertTrue(withContext(Dispatchers.IO) { input.entered.await(2, TimeUnit.SECONDS) })
        models.deleteModel(spec.id)
        try { withTimeout(3000) { operation.await() }; fail("Deleted import republished") }
        catch (_: CancellationException) { }
        assertNull(models.readyModel(spec.id))
        assertFalse(old.file.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun unsafeCatalogIdentifierCannotEscapePrivateModelDirectory() = runBlocking {
        val root = temporary.newFolder()
        try {
            val bad = spec.copy(id = "../outside")
            store(root, bad).importModel(bad.id, { ByteArrayInputStream(bytes) })
            fail("Unsafe catalog identifier accepted")
        } catch (_: IllegalArgumentException) { }
        assertFalse(File(root.parentFile, "outside").exists())
    }

    private suspend fun expectFailure(reason: LocalModelFailure, operation: suspend () -> Any?) {
        try { operation(); fail("Expected model failure: $reason") }
        catch (error: LocalModelStoreException) { assertEquals(reason, error.reason) }
    }
}

private class ChunkedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 8))
}

private class BlockingInput : InputStream() {
    val entered = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    override fun read(): Int = error("Bounded bulk read required")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Input was not closed after cancellation" }
        return -1
    }
    override fun close() { closed.set(true); released.countDown() }
}

internal fun fixtureSpec(bytes: ByteArray) = LocalModelSpec(
    id = "fixture-qwen3-q8", displayName = "Synthetic GGUF fixture", sourceRepo = "Qwen/Qwen3-0.6B-GGUF",
    sourceRevision = "1".repeat(40), sourceFile = "fixture.gguf", expectedBytes = bytes.size.toLong(),
    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
)

/** Minimal GGUFv3: architecture/filetype metadata, one F32 scalar tensor and aligned data. */
internal fun tinyGguf(architecture: String = "qwen3"): ByteArray {
    val out = ByteArrayOutputStream()
    fun number(value: Long, width: Int) { repeat(width) { out.write((value ushr (it * 8)).toInt() and 255) } }
    fun string(value: String) { val encoded = value.toByteArray(); number(encoded.size.toLong(), 8); out.write(encoded) }
    out.write("GGUF".toByteArray()); number(3, 4); number(1, 8); number(2, 8)
    string("general.architecture"); number(8, 4); string(architecture)
    string("general.file_type"); number(4, 4); number(7, 4)
    string("weight"); number(1, 4); number(1, 8); number(0, 4); number(0, 8)
    while (out.size() % 32 != 0) out.write(0)
    number(0x3f800000, 4)
    return out.toByteArray()
}

private fun largeMetadataGguf(tensorOffset: Long = 0): ByteArray {
    val out = ByteArrayOutputStream()
    fun number(value: Long, width: Int) { repeat(width) { out.write((value ushr (it * 8)).toInt() and 255) } }
    fun string(value: String) { val encoded = value.toByteArray(); number(encoded.size.toLong(), 8); out.write(encoded) }
    out.write("GGUF".toByteArray()); number(3, 4); number(1, 8); number(3, 8)
    string("general.architecture"); number(8, 4); string("qwen3")
    string("general.file_type"); number(4, 4); number(7, 4)
    string("tokenizer.test.padding"); number(9, 4); number(8, 4); number(3, 8)
    string("x".repeat(65_533)); string("y".repeat(65_539)); string("last metadata")
    string("weight"); number(1, 4); number(1, 8); number(0, 4); number(tensorOffset, 8)
    while (out.size() % 32 != 0) out.write(0)
    number(0x3f800000, 4)
    return out.toByteArray()
}
