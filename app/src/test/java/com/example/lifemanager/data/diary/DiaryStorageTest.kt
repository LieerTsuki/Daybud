package com.example.lifemanager.data.diary

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Test
import kotlin.test.*

class DiaryStorageTest {
    @Test fun cancellationDuringImportedLeaseHandoffDoesNotLeaveAnUnreleasablePin(): Unit = runBlocking {
        val directory=Files.createTempDirectory("diary-handoff").toFile()
        try {
            val storage=FileDiaryStorage(directory)
            val tasks=java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val dispatcher=object:kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { tasks.add(block) }
            }
            val job=kotlinx.coroutines.CoroutineScope(coroutineContext).async(dispatcher,start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                storage.importPinned(ByteArrayInputStream(byteArrayOf(1,2,3)),"image/jpeg")
            }
            kotlinx.coroutines.withTimeout(5000) { while(tasks.isEmpty()) kotlinx.coroutines.delay(1) }
            job.cancel()
            while(true) { val task=tasks.poll() ?: break; task.run() }
            job.join()
            storage.withGate { storage.garbageCollect(emptySet()) }
            assertTrue(directory.walkTopDown().none { it.isFile },"Cancelled handoff must release imported lease")
        } finally { directory.deleteRecursively() }
    }

    @Test fun startupRemovesOnlyCrashStagingOnceAndSecondInstancePreservesActiveStaging(): Unit = runBlocking {
        val directory=Files.createTempDirectory("diary-startup").toFile()
        try {
            val orphan=java.io.File(directory,"12345678-1234-1234-1234-123456789012.part").apply { writeBytes(byteArrayOf(9)) }
            val unrelated=java.io.File(directory,"keep.part").apply { writeText("keep") }
            val final=java.io.File(directory,"12345678-1234-1234-1234-123456789013").apply { writeBytes(byteArrayOf(8)) }
            val nested=java.io.File(directory,"12345678-1234-1234-1234-123456789014.part").apply { mkdir() }
            FileDiaryStorage(directory)
            assertFalse(orphan.exists())
            assertTrue(unrelated.isFile); assertTrue(final.isFile); assertTrue(nested.isDirectory)
            val active=java.io.File(directory,"12345678-1234-1234-1234-123456789015.part").apply { writeText("active") }
            FileDiaryStorage(java.io.File(directory,"."))
            assertTrue(active.isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test fun restorePreparesVerifiedBytesThenPromotesOriginalIdentityAndRejectsCorruption(): Unit = runBlocking {
        val directory=Files.createTempDirectory("diary-restore").toFile()
        try {
            val storage=FileDiaryStorage(directory)
            val media=com.example.lifemanager.domain.diary.DiaryMedia("12345678-1234-1234-1234-123456789012","image/jpeg",3,"039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            assertFailsWith<IllegalArgumentException> { storage.prepareRestore(media,ByteArrayInputStream(byteArrayOf(1,2,4))) }
            val prepared=storage.prepareRestore(media,ByteArrayInputStream(byteArrayOf(1,2,3)))
            assertFalse(storage.file(media).exists())
            val pin=storage.withGate { storage.promotePrepared(prepared) }
            prepared.close()
            storage.withGate { storage.garbageCollect(emptySet()) }
            assertContentEquals(byteArrayOf(1,2,3),storage.open(media).use { it.readBytes() })
            pin.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun importedPinPreventsCollectionUntilReleaseAndSharedReferenceKeepsBytes(): Unit = runBlocking {
        val directory=Files.createTempDirectory("diary-media").toFile()
        try {
            val storage=FileDiaryStorage(directory)
            val imported=storage.importPinned(ByteArrayInputStream(byteArrayOf(1,2,3)),"image/jpeg")
            storage.withGate { storage.garbageCollect(emptySet()) }
            assertContentEquals(byteArrayOf(1,2,3),storage.open(imported.media).use { it.readBytes() })
            imported.pin.close()
            storage.withGate { storage.garbageCollect(setOf(imported.media.id)) }
            assertTrue(storage.file(imported.media).isFile)
            storage.withGate { storage.garbageCollect(emptySet()) }
            assertFalse(storage.file(imported.media).exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unknownLengthInputStopsAtActualBudgetAndNeverPromotesPartialFile(): Unit = runBlocking {
        val directory=Files.createTempDirectory("diary-limit").toFile()
        try {
            val storage=FileDiaryStorage(directory)
            assertFailsWith<IllegalArgumentException> { storage.importPinned(ByteArrayInputStream(ByteArray(5)),"video/mp4",4) }
            assertTrue(directory.walkTopDown().none { it.isFile })
            assertFailsWith<IllegalArgumentException> { storage.importPinned(ByteArrayInputStream(byteArrayOf(1)),"application/octet-stream") }
        } finally { directory.deleteRecursively() }
    }
}
