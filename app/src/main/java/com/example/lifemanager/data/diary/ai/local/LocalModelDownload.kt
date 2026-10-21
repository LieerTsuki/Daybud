package com.example.lifemanager.data.diary.ai.local

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixed catalog URLs only; the store verifies the entire artifact before accepting it. */
object LocalModelDownload {
    fun open(spec: LocalModelSpec, connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }): InputStream {
        require(spec.availableForDownload && spec in LocalModelCatalog.models) { "该模型尚未开放下载，请导入已校验来源的文件" }
        return object : InputStream() {
            private val closed = AtomicBoolean(false)
            private val active = AtomicReference<HttpsURLConnection?>()
            private val stream = AtomicReference<InputStream?>()
            private fun ensureOpen() { if (closed.get()) throw IOException("模型下载已取消") }
            private fun input(): InputStream {
                ensureOpen()
                stream.get()?.let { return it }
                var target = URI(spec.downloadUrl)
                // Fixed revision and SHA remain unchanged; avoid reusing expired signed CDN redirects.
                if (target.host == "huggingface.co") target = URI("${spec.downloadUrl}?download=true&daybud=${java.util.UUID.randomUUID()}")
                repeat(6) {
                    ensureOpen()
                    require(target.scheme == "https" && target.userInfo == null && target.host != null) { "模型下载地址无效" }
                    val connection = connectionFactory(target.toURL())
                    active.set(connection)
                    try {
                        ensureOpen()
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        val status = connection.responseCode
                        ensureOpen()
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            val next = connection.getHeaderField("Location") ?: error("模型下载重定向无效")
                            target = target.resolve(next)
                            active.compareAndSet(connection, null)
                            connection.disconnect()
                        } else {
                            check(status == 200) { "模型下载返回HTTP $status，请稍后重试或导入文件" }
                            val length = connection.contentLengthLong
                            check(length == -1L || length == spec.expectedBytes) { "模型下载文件大小不匹配" }
                            val opened = connection.inputStream
                            stream.set(opened)
                            if (closed.get()) { opened.close(); throw IOException("模型下载已取消") }
                            return opened
                        }
                    } catch (error: Exception) { connection.disconnect(); throw error }
                }
                error("模型下载重定向次数过多，请导入已下载文件")
            }
            override fun read(): Int = input().read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input().read(bytes, offset, length)
            override fun close() {
                closed.set(true)
                active.getAndSet(null)?.disconnect()
                stream.getAndSet(null)?.close()
            }
        }
    }
}
