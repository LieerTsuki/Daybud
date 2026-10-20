package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ChatCompletionsClient(private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : AiCompletionGateway {
    override suspend fun complete(config: AiServiceConfig, sources: List<AiDiarySource>, partials: List<AiReviewSection>, beforeSend: suspend () -> Unit): List<AiReviewSection> {
        val response = exchange(config, ChatCompletionsProtocol.request(config.model, sources, partials), beforeSend)
        return ChatCompletionsProtocol.parse(response, sources.map { it.id }.toSet())
    }

    override suspend fun probe(config: AiServiceConfig) {
        val result = exchange(config, ChatCompletionsProtocol.probe(config.model)) {}
        try {
            val root = Json.parseToJsonElement(result).jsonObject
            check(root.getValue("choices").jsonArray.isNotEmpty())
        } catch (_: Exception) { throw AiProtocolException() }
    }

    private suspend fun exchange(config: AiServiceConfig, body: String, beforeSend: suspend () -> Unit): String = withTimeout(90_000) {
        suspendCancellableCoroutine { continuation ->
            val reference = AtomicReference<HttpsURLConnection?>()
            val job = CoroutineScope(continuation.context + Dispatchers.IO).launch {
                var sent = false
                try {
                    val connection = connectionFactory(URL(config.endpoint))
                    reference.set(connection)
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000; connection.readTimeout = 45_000
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    ensureActive()
                    beforeSend()
                    ensureActive()
                    // Some platform implementations can send headers in connect(). Treat this
                    // boundary conservatively as sent, including uncertain TLS/socket failures.
                    sent = true
                    connection.connect()
                    connection.outputStream.use { it.write(bytes); it.flush() }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        // Never follow even same-origin redirects: credentials remain at the configured origin.
                        throw AiNetworkException(true, false, code in 300..499)
                    }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive(); val count = stream.read(buffer); if (count == -1) break
                            if (output.size() + count > 256 * 1024) throw AiProtocolException()
                            output.write(buffer, 0, count)
                        }
                    }
                    val text = output.toString("UTF-8")
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    val safe = when (error) {
                        is CancellationException -> error
                        is AiStaleException, is AiProtocolException, is AiNetworkException -> error
                        else -> AiNetworkException(sent, !sent)
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                } finally { reference.getAndSet(null)?.disconnect() }
            }
            continuation.invokeOnCancellation { reference.getAndSet(null)?.disconnect(); job.cancel() }
        }
    }
}
