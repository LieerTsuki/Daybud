package com.example.lifemanager.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class V4ArchiveContainerTest {
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    private val bytes = "真实照片字节".toByteArray()
    private fun record(value: ByteArray = bytes) = V4MediaRecord(id, value.size.toLong(), hash(value))
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val container = V4ArchiveContainer(maxManifestBytes=1024, maxMediaBytes=2048, maxArchiveEntries=20, maxFileBytes=1024)

    @Test fun streamsAllBytesAndReopensExactManifestAndAttachment() {
        val output = ByteArrayOutputStream()
        container.write(output, "{\"formatVersion\":4}".toByteArray(), listOf(record())) { ByteArrayInputStream(bytes) }
        var restored = byteArrayOf()
        val manifest = container.read(ByteArrayInputStream(output.toByteArray()), { listOf(record()) }) { _, input -> restored=input.readBytes() }
        assertEquals("{\"formatVersion\":4}",manifest.toString(Charsets.UTF_8))
        assertArrayEquals(bytes,restored)
    }

    @Test fun traversalOrUnexpectedMediaIsRejected() {
        val archive = zip("manifest.json" to "{}".toByteArray(), "../private" to bytes)
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(archive), { listOf(record()) }) { _,_ -> } }
    }

    @Test fun missingMediaCannotBecomeSuccessfulRestore() {
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(zip("manifest.json" to "{}".toByteArray())), { listOf(record()) }) { _,_ -> } }
    }

    @Test fun wrongDigestIsRejectedEvenWhenSizeMatches() {
        val changed=bytes.copyOf().also { it[0]=(it[0].toInt() xor 1).toByte() }
        val archive=zip("manifest.json" to "{}".toByteArray(),"media/$id" to changed)
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(archive), { listOf(record()) }) { _, input -> input.readBytes() } }
    }

    @Test fun actualUnknownLengthInputIsBounded() {
        val archive=zip("manifest.json" to "{}".toByteArray(),"media/$id" to ByteArray(1500))
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(archive), { listOf(record(ByteArray(1500))) }) { _,input -> input.readBytes() } }
    }

    @Test fun manifestMustBeFirstAndBounded() {
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(zip("media/$id" to bytes,"manifest.json" to "{}".toByteArray())), { listOf(record()) }) { _,_ -> } }
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(zip("manifest.json" to ByteArray(1025))), { emptyList() }) { _,_ -> } }
    }

    @Test fun truncatedCentralDirectoryIsRejected() {
        val archive=zip("manifest.json" to "{}".toByteArray())
        assertThrows(Exception::class.java) { container.read(ByteArrayInputStream(archive.copyOf(archive.size-10)), { emptyList() }) { _,_ -> } }
    }

    @Test fun outputStreamLiesCannotPassHashVerification() {
        assertThrows(Exception::class.java) { container.write(ByteArrayOutputStream(),"{}".toByteArray(),listOf(record())) { ByteArrayInputStream(byteArrayOf(1,2,3)) } }
    }

    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
        return output.toByteArray()
    }
}
