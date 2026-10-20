package com.example.lifemanager.data.diary

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.lifemanager.data.backup.*
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.LifeManagerDatabaseFactory
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.ui.diary.AndroidDiaryMediaAccess
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Real MediaStore, codecs, private files, durable Room, and format4 IO on the target device. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DiaryMediaArchiveDeviceTest {
    @Test fun deletedGallerySourcesKeepPrivateMediaAndFullV4RestoresDurableDiaryGraph(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val owner = UUID.randomUUID().toString()
        val databaseName = "diary-device-$owner.db"
        val folder = File(context.filesDir, "diary-device-tests/$owner")
        val videoSource = File(context.cacheDir, "diary-device-$owner.mp4")
        val ownedUris = mutableListOf<Uri>()
        val storage = FileDiaryStorage(folder)
        var database: LifeManagerDatabase? = null
        try {
            fun open(): LifeManagerDatabase = LifeManagerDatabaseFactory.open(context, databaseName)
                .also { database = it }
            var db = open()
            val clock = Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), ZoneId.of("Asia/Shanghai"))
            fun repo(db: LifeManagerDatabase) = RoomDiaryRepository(db,
                MaintenanceCoordinator(DataGenerationRepositoryImpl(db)), clock, storage)
            var diary = repo(db)
            val access = AndroidDiaryMediaAccess(context, storage)
            val date = LocalDate.of(2026, 10, 18)

            val photo = newMedia(context, "image/png", "diary-device-$owner.png", false).also(ownedUris::add)
            val bitmap = Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff80cbc4.toInt()) }
            try {
                resolver.openOutputStream(photo, "w")!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            publish(context, photo)
            createDiaryDeviceVideo(videoSource)
            val video = newMedia(context, "video/mp4", "diary-device-$owner.mp4", true).also(ownedUris::add)
            resolver.openOutputStream(video, "w")!!.use { output -> videoSource.inputStream().use { it.copyTo(output) } }
            publish(context, video)
            val originalHashes = listOf(photo, video).map { uri -> resolver.openInputStream(uri)!!.use(::sha256) }

            val importedPhoto = access.importSource(photo.toString(), DiaryRules.MAX_ENTRY_BYTES)
            val importedVideo = try { access.importSource(video.toString(), DiaryRules.MAX_ENTRY_BYTES - importedPhoto.media.bytes) }
                catch (error: Throwable) { importedPhoto.pin.close(); throw error }
            val media = listOf(importedPhoto.media, importedVideo.media.copy(position = 1))
            val generation = DataGenerationRepositoryImpl(db).current()
            val entry = try {
                val initial = diary.createDraft(generation, date)
                val saved = diary.saveDraft(initial.copy(title = "照片与视频的原始记录", body = "图库删除后，完整附件仍然属于这篇日记。",
                    mood = DiaryMood.HAPPY, media = media), initial.revision, generation)
                diary.commitDraft(saved.id, saved.revision, generation)
            } finally { importedPhoto.pin.close(); importedVideo.pin.close() }

            // Remove only the two source rows this test inserted. Private imports must remain identical.
            listOf(photo, video).forEach { uri ->
                assertEquals(1, resolver.delete(uri, null, null))
                ownedUris.remove(uri)
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { assertEquals(0, it.count) }
            }
            media.forEachIndexed { index, item ->
                assertTrue(access.file(item).isFile)
                assertEquals(item.bytes, access.file(item).length())
                assertEquals(originalHashes[index], storage.open(item).use(::sha256))
                assertEquals(originalHashes[index], item.sha256)
            }
            val edit = diary.createDraft(generation, date, entry.id)
            val durableDraft = diary.saveDraft(edit.copy(body = "这份修改仍是草稿，不覆盖正式日记。", mood = DiaryMood.CALM), edit.revision, generation)
            val snapshot = diary.snapshotRange(date, date, generation)
            val review = DiaryReview("review-$owner", date, date, clock.instant(),
                listOf("生活回顾", "心情变化", "重要时刻", "个人成长", "温暖寄语").map { DiaryReviewSection(it, "受控测试保存的内容：$it", listOf(entry.id)) },
                listOf(DiarySourceRevision(entry.id, entry.revision)), provider = "https://example.invalid/v1", model = "device-test", inputHash = "0".repeat(64))
            diary.saveReview(review, snapshot)
            val before = db.withTransaction { diary.backupSnapshotWithinExistingTransaction() }
            assertEquals(entry, diary.readEntry(entry.id))

            // Release the actual SQLite connection, then reconstruct repository and coordination.
            db.close(); db = open(); diary = repo(db)
            assertEquals(durableDraft, diary.readDraft(durableDraft.id))
            assertEquals(entry, diary.readEntry(entry.id))
            assertEquals(before, db.withTransaction { diary.backupSnapshotWithinExistingTransaction() })

            val generations = DataGenerationRepositoryImpl(db)
            val maintenance = MaintenanceCoordinator(generations)
            val snapshots = V4BackupRepository(db, V2BackupRepositoryImpl(db, db.backupSnapshotDao()), diary)
            val mutations = V4BackupMutationRepository(db, V2BackupMutationRepositoryImpl(db, db.backupSnapshotDao()), diary, storage)
            val archive = V4ArchiveFiles(object : BackupStreamAccess {
                override fun openRead(location: BackupLocation) = resolver.openInputStream(Uri.parse(location.value))
                override fun openWrite(location: BackupLocation) = resolver.openOutputStream(Uri.parse(location.value), "wt")
            }, storage, VersionedBackupJsonCodec())
            val output = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "diary-device-$owner.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Diary-device-tests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!.also(ownedUris::add)
            val location = BackupLocation(output.toString())
            val payload = snapshots.snapshot()
            val document = V2BackupDocument("device-test", "2026-10-19T04:30:00Z", payload)
            archive.capture { snapshots.snapshot() }.use { capture ->
                assertEquals(payload, capture.payload)
                assertEquals(document, archive.writeVerified(location, document.copy(payload = capture.payload)))
            }
            publish(context, output)

            maintenance.withSession { session -> maintenance.withMaintenance(session) {
                mutations.clearBusinessData(session.generation)
            } }
            assertTrue(snapshots.snapshot().diary!!.entries.isEmpty())
            // Committed clear removes private copies; restore must read the actual archive.
            media.forEach { assertFalse(storage.file(it).exists()) }
            val preview = archive.preview(location, ZoneId.of("Asia/Shanghai"))
            try {
                assertEquals(BackupSourceFormat.V4, preview.sourceFormat)
                assertEquals(document, preview.document.copy(previewToken = null))
                archive.validateDocument(preview.document)
                maintenance.withSession { session -> maintenance.withMaintenance(session) {
                    mutations.replace(session.generation, preview.document.payload)
                } }
                assertEquals(generation.value + 2, generations.current().value)
                assertEquals(payload, snapshots.snapshot())
                assertEquals(durableDraft, diary.readDraft(durableDraft.id))
                assertEquals(entry, diary.readEntry(entry.id))
                assertEquals(listOf(review), snapshots.snapshot().diary!!.reviews)
                media.forEachIndexed { index, item ->
                    assertEquals(item.bytes, storage.file(item).length())
                    assertEquals(originalHashes[index], storage.open(item).use(::sha256))
                }
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { archive.releasePreview(preview.document) }
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
            ownedUris.forEach { resolver.delete(it, null, null) }
            videoSource.delete()
            // The parent is fixed, the child is the fresh UUID owned exclusively by this test.
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }

    private fun newMedia(context: Context, mime: String, name: String, video: Boolean): Uri =
        requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Diary-device-tests" else "Pictures/Diary-device-tests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))

    private fun publish(context: Context, uri: Uri) {
        assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
    }
    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
