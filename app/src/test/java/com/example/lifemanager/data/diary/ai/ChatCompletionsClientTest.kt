package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsClientTest {
    private class Connection(url: URL, val code: Int, val response: String) : HttpsURLConnection(url) {
        val uploaded = ByteArrayOutputStream()
        var disconnected = false
        var connections = 0
        var input: InputStream = ByteArrayInputStream(response.toByteArray())
        var onDisconnect: () -> Unit = {}
        override fun connect() { connections++ }
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = uploaded
        override fun getInputStream() = input
        override fun getResponseCode() = code
    }
    private fun config() = AiServiceConfig.checked("https://first.example/v1", "model", "never-log-this", 1)

    @Test fun redirectCannotForwardAuthorizationOrFollowOtherOrigin() = runBlocking {
        val connection = Connection(URL(config().endpoint), 302, "")
        var opened = 0
        val client = ChatCompletionsClient { opened++; connection }
        try { client.probe(config()); fail("redirect accepted") } catch (error: AiNetworkException) {
            assertTrue(error.sent)
            assertFalse(error.message!!.contains("never-log-this"))
        }
        assertEquals(1, opened)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun lastMomentAuthorizationFailureWritesNoRequestBody() = runBlocking {
        val connection = Connection(URL(config().endpoint), 200, "")
        val client = ChatCompletionsClient { connection }
        try {
            client.complete(config(), listOf(AiDiarySource(1, 1, LocalDate.now(), "", "private text", null)), emptyList()) { throw AiStaleException() }
            fail("revoked authorization accepted")
        } catch (_: AiStaleException) { }
        assertEquals(0, connection.uploaded.size())
        assertEquals(0, connection.connections)
        assertTrue(connection.disconnected)
    }

    @Test fun probeUploadsNoDiaryAndUsesFiniteTimeouts() = runBlocking {
        val connection = Connection(URL(config().endpoint), 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}")
        ChatCompletionsClient { connection }.probe(config())
        val body = connection.uploaded.toString("UTF-8")
        assertFalse(body.contains("diaries"))
        assertTrue(connection.connectTimeout in 1..30_000)
        assertTrue(connection.readTimeout in 1..60_000)
    }

    @Test fun knownServiceRefusalShowsStatusWithoutSecretOrDiary() = runBlocking {
        for (status in listOf(400, 401, 402, 403, 404, 429, 500, 503)) {
            val connection = Connection(URL(config().endpoint), status, "private text never-log-this")
            try {
                ChatCompletionsClient { connection }.probe(config())
                fail("service rejection accepted")
            } catch (error: AiNetworkException) {
                assertTrue("HTTP $status was hidden by unknown-result text", error.message!!.contains(status.toString()))
                assertFalse(error.message!!.contains("结果未知"))
                assertFalse(error.message!!.contains("never-log-this"))
                assertFalse(error.message!!.contains("private text"))
                assertFalse(error.retryable)
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun cancellationDisconnectsBlockedNetworkRead() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
        val connection = Connection(URL(config().endpoint), 200, "").apply {
            input = object : InputStream() {
                override fun read(): Int { entered.complete(Unit); released.await(5, TimeUnit.SECONDS); throw IOException("closed") }
            }
            onDisconnect = { released.countDown() }
        }
        val job = launch { ChatCompletionsClient { connection }.probe(config()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(connection.disconnected)
    }
}
