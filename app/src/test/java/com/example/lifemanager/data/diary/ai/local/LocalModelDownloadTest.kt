package com.example.lifemanager.data.diary.ai.local

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class LocalModelDownloadTest {
    private val spec get() = LocalModelCatalog.models.first { it.availableForDownload }

    @Test fun openingSourceDoesNotConnectUntilFirstRead() {
        val connection = ModelConnection(URL(spec.downloadUrl), spec.expectedBytes)
        var factories = 0
        LocalModelDownload.open(spec) { factories++; connection }.use { source ->
            assertEquals(0, factories)
            assertEquals(0, connection.headers.get())
            assertEquals(1, source.read())
            assertEquals(1, factories)
        }
        assertTrue(connection.disconnected)
    }

    @Test fun closingDuringBlockedResponseHeadersDisconnectsAndReleasesReadPromptly() {
        val connection = ModelConnection(URL(spec.downloadUrl), spec.expectedBytes, blockHeaders = true)
        val executor = Executors.newSingleThreadExecutor()
        var source: InputStream? = null
        try {
            source = LocalModelDownload.open(spec) { connection }
            val stream = source
            val pending = executor.submit<Int> { stream.read() }
            assertTrue(connection.entered.await(2, TimeUnit.SECONDS))
            source.close()
            try { pending.get(2, TimeUnit.SECONDS) }
            catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
            assertTrue(connection.disconnected)
        } finally {
            source?.close()
            connection.disconnect()
            executor.shutdownNow()
        }
    }

    @Test fun disabledArtifactCannotOpenDownloadConnection() {
        val unpublished = spec.copy(availableForDownload = false)
        var opened = false
        expectRejected {
            LocalModelDownload.open(unpublished) { opened = true; ModelConnection(it, unpublished.expectedBytes) }.use { it.read() }
        }
        assertFalse(opened)
    }

    @Test fun alteredCatalogDigestCannotOpenDownloadConnection() {
        val changed = spec.copy(sha256 = "0".repeat(64))
        var opened = false
        expectRejected {
            LocalModelDownload.open(changed) { opened = true; ModelConnection(it, changed.expectedBytes) }.use { it.read() }
        }
        assertFalse(opened)
    }

    @Test fun redirectCannotDowngradeToHttp() {
        val connections = mutableListOf<ModelConnection>()
        expectRejected {
            LocalModelDownload.open(spec) { url ->
                ModelConnection(url, spec.expectedBytes, status = 302, location = "http://example.invalid/model.gguf")
                    .also(connections::add)
            }.use { it.read() }
        }
        assertEquals(1, connections.size)
        assertTrue(connections.single().disconnected)
    }

    @Test fun endlessHttpsRedirectsAreBoundedAndAllConnectionsClosed() {
        val connections = mutableListOf<ModelConnection>()
        expectRejected {
            LocalModelDownload.open(spec) { url ->
                ModelConnection(url, spec.expectedBytes, status = 302, location = "https://example.invalid/model.gguf")
                    .also(connections::add)
            }.use { it.read() }
        }
        assertTrue(connections.isNotEmpty() && connections.size <= 6)
        assertTrue(connections.all { it.disconnected })
    }

    @Test fun advertisedWrongFileLengthIsRejectedAndDisconnected() {
        val connection = ModelConnection(URL(spec.downloadUrl), spec.expectedBytes - 1)
        expectRejected { LocalModelDownload.open(spec) { connection }.use { it.read() } }
        assertTrue(connection.disconnected)
    }

    private fun expectRejected(block: () -> Unit) {
        try { block(); fail("Unsafe or unavailable model download accepted") }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
}

private class ModelConnection(
    url: URL,
    private val bytes: Long,
    private val status: Int = 200,
    private val location: String? = null,
    private val blockHeaders: Boolean = false,
) : HttpsURLConnection(url) {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val headers = AtomicInteger()
    @Volatile var disconnected = false
    override fun connect() = Unit
    override fun usingProxy(): Boolean = false
    override fun disconnect() { disconnected = true; released.countDown() }
    override fun getResponseCode(): Int {
        headers.incrementAndGet()
        entered.countDown()
        if (blockHeaders && !released.await(3, TimeUnit.SECONDS)) throw IOException("Synthetic blocked headers")
        if (disconnected) throw IOException("Disconnected")
        return status
    }
    override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
    override fun getContentLengthLong(): Long = bytes
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    override fun getCipherSuite(): String = "TLS_TEST_ONLY"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
