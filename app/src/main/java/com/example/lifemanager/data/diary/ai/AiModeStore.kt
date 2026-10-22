package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Offline configuration never opens the retired encrypted credentials or automatic queue. */
class AiModeStore(private val directory: File) : AiStateStore {
    private val mutex = Mutex()
    private data class Route(val revision: Long = 0, val id: String = "", val sha: String = "")
    private fun read(): Route {
        val file = File(directory, "mode.json")
        if (!file.exists()) return Route()
        return try {
            check(file.length() in 1..4096)
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            val revision = json.getValue("revision").jsonPrimitive.long
            check(revision in 1 until Long.MAX_VALUE)
            // Migrate only a valid local selection. An old CLOUD record is never a route.
            if (json.getValue("mode").jsonPrimitive.content != "LOCAL") Route(revision)
            else {
                val route = Route(revision, json.getValue("id").jsonPrimitive.content, json.getValue("sha").jsonPrimitive.content)
                AiServiceConfig.local(route.id, route.sha, route.revision)
                route
            }
        } catch (_: Exception) { Route() }
    }
    private fun write(route: Route) {
        check(directory.mkdirs() || directory.isDirectory)
        val pending = File(directory, "mode.pending")
        val bytes = buildJsonObject {
            put("mode", "LOCAL"); put("revision", route.revision); put("id", route.id); put("sha", route.sha)
        }.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        Files.move(pending.toPath(), File(directory, "mode.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun revision(old: Route): Long {
        // Never publish an exhausted counter; a damaged near-limit route can be selected again.
        val next = if (old.revision >= Long.MAX_VALUE - 1) 1 else old.revision + 1
        return maxOf(System.currentTimeMillis(), next)
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun configuration(route: Route): AiServiceConfig? =
        if (route.id.isEmpty()) null else AiServiceConfig.local(route.id, route.sha, route.revision)
    override suspend fun config(): AiServiceConfig? = locked {
        configuration(read())
    }
    override suspend fun <T> withCurrentConfiguration(expected: AiServiceConfig, operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!expected.isSameLocalConfiguration(configuration(read()))) throw AiStaleException()
            // Do not call config()/guard() here: the selected-model mutex is deliberately held
            // across the source commit, but never across loading or model inference.
            operation()
        }
    }
    suspend fun mode(): AiExecutionMode = AiExecutionMode.LOCAL
    suspend fun selectLocal(modelId: String, sha256: String): AiServiceConfig = locked {
        AiServiceConfig.local(modelId, sha256, 1)
        val route = Route(revision(read()), modelId, sha256)
        write(route)
        AiServiceConfig.local(route.id, route.sha, route.revision)
    }
    /** Backup maintenance invalidates any captured request while preserving the selected model. */
    suspend fun invalidateRequests() = locked {
        val old = read()
        write(old.copy(revision = revision(old)))
    }
    override suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig = error("仅支持手机离线模型")
    override suspend fun consents(): List<AiAutoConsent> = emptyList()
    override suspend fun jobs(): List<AiAutoJob> = emptyList()
    override suspend fun setConsent(consent: AiAutoConsent) { check(!consent.enabled) { "手机离线回顾由你手动生成" } }
    override suspend fun putJob(job: AiAutoJob): Unit = error("自动云端回顾已停用")
    override suspend fun pauseAutomatic() = invalidateRequests()
}
