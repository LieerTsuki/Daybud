package com.example.lifemanager.data.backup

import com.example.lifemanager.data.diary.FileDiaryStorage
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.*
import java.io.*
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class V4ArchiveFilesTest {
    @Test fun completeMediaRoundTripPinsSnapshotAndPreviewUntilExplicitRelease():Unit=runBlocking {
        val root=Files.createTempDirectory("v4-archive").toFile()
        try {
            val storage=FileDiaryStorage(File(root,"first"))
            val imported=storage.importPinned(ByteArrayInputStream(byteArrayOf(1,2,3)),"image/jpeg")
            val now=Instant.parse("2026-10-19T00:00:00Z")
            val draft=DiaryDraft("draft",null,null,LocalDate.parse("2026-10-19"),"标题","真实草稿",DiaryMood.CALM,listOf(imported.media),now,now,1)
            val diary=DiaryBackupSnapshot(drafts=listOf(draft),media=listOf(imported.media))
            val doc=V2BackupDocument("1.2.0",now.toString(),V2BackupPayload(V3BackupSchema.tables.associate { it.name to emptyList() },V2BackupSettings(),diary))
            val streams=MemoryStreams()
            val archive=V4ArchiveFiles(streams,storage,VersionedBackupJsonCodec())
            val capture=archive.capture { doc.payload }
            imported.pin.close()
            storage.withGate { storage.garbageCollect(emptySet()) }
            assertTrue(storage.file(imported.media).exists())
            archive.writeVerified(BackupLocation("output"),doc)
            capture.close()
            val restored=FileDiaryStorage(File(root,"second"))
            val reader=V4ArchiveFiles(streams,restored,VersionedBackupJsonCodec())
            val preview=reader.preview(BackupLocation("output"),ZoneOffset.UTC)
            assertEquals(BackupSourceFormat.V4,preview.sourceFormat)
            assertEquals(doc.payload,preview.document.payload)
            assertNotNull(preview.document.previewToken)
            restored.withGate { restored.garbageCollect(emptySet()) }
            assertContentEquals(byteArrayOf(1,2,3),restored.open(imported.media).use { it.readBytes() })
            reader.validateDocument(preview.document)
            reader.releasePreview(preview.document)
            restored.withGate { restored.garbageCollect(emptySet()) }
            assertFalse(restored.file(imported.media).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun providerTruncationCannotBecomeVerifiedProtection():Unit=runBlocking {
        val root=Files.createTempDirectory("v4-truncate").toFile()
        try {
            val streams=MemoryStreams(true)
            val archive=V4ArchiveFiles(streams,FileDiaryStorage(root),VersionedBackupJsonCodec())
            val doc=V2BackupDocument("1.2.0","2026-10-19T00:00:00Z",V2BackupPayload(V3BackupSchema.tables.associate { it.name to emptyList() },V2BackupSettings(),DiaryBackupSnapshot()))
            assertFails { archive.writeVerified(BackupLocation("out"),doc) }
        } finally { root.deleteRecursively() }
    }

    @Test fun oldJsonRemainsHistoricalAndNeverAcquiresAnEmptyDiaryReplacement():Unit=runBlocking {
        val root=Files.createTempDirectory("v4-old").toFile()
        try {
            val codec=VersionedBackupJsonCodec()
            val old=V2BackupDocument("1.1.4","2026-10-19T00:00:00Z",V2BackupPayload(V3BackupSchema.tables.associate { it.name to emptyList() },V2BackupSettings()))
            val streams=MemoryStreams().also { it.bytes=codec.encode(old) }
            val archive=V4ArchiveFiles(streams,FileDiaryStorage(root),codec)
            val preview=archive.preview(BackupLocation("old"),ZoneOffset.UTC)
            assertEquals(BackupSourceFormat.V3,preview.sourceFormat)
            assertNull(preview.document.payload.diary)
            assertNull(preview.document.previewToken)
        } finally { root.deleteRecursively() }
    }

    @Test fun validArchiveFromShortReadingDocumentProviderStillPreviews():Unit=runBlocking {
        val root=Files.createTempDirectory("v4-short-read").toFile()
        try {
            val streams=MemoryStreams(shortReads=true)
            val archive=V4ArchiveFiles(streams,FileDiaryStorage(root),VersionedBackupJsonCodec())
            val doc=V2BackupDocument("1.2.0","2026-10-19T00:00:00Z",V2BackupPayload(V3BackupSchema.tables.associate { it.name to emptyList() },V2BackupSettings(),DiaryBackupSnapshot()))
            archive.writeVerified(BackupLocation("out"),doc)
            val preview=archive.preview(BackupLocation("out"),ZoneOffset.UTC)
            try { assertEquals(doc.payload,preview.document.payload) } finally { archive.releasePreview(preview.document) }
        } finally { root.deleteRecursively() }
    }

    private class MemoryStreams(val truncate:Boolean=false,val shortReads:Boolean=false):BackupStreamAccess {
        var bytes=byteArrayOf()
        override fun openRead(location:BackupLocation):InputStream=object:ByteArrayInputStream(bytes) {
            override fun read(target:ByteArray,offset:Int,length:Int):Int=super.read(target,offset,if(shortReads)minOf(1,length)else length)
            override fun available():Int=if(shortReads)0 else super.available()
        }
        override fun openWrite(location:BackupLocation):OutputStream=object:ByteArrayOutputStream() {
            override fun close() { bytes=toByteArray().let { if(truncate)it.copyOf(it.size-8) else it };super.close() }
        }
    }
}
