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

/** Mode metadata is independent from cloud credentials and model weights. */
class AiModeStore(private val cloud: AiStateStore, private val directory: File, private val sends: CloudSendGate = CloudSendGate()) : AiStateStore by cloud {
    private val mutex = Mutex()
    private data class Route(val mode: AiExecutionMode, val revision: Long, val id: String = "", val sha: String = "")
    private fun read(): Route {
        val file = File(directory, "mode.json")
        if (!file.exists()) return Route(AiExecutionMode.CLOUD, 0)
        try {
            check(file.length() in 1..4096)
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            val route = Route(AiExecutionMode.valueOf(json.getValue("mode").jsonPrimitive.content),
                json.getValue("revision").jsonPrimitive.long, json["id"]?.jsonPrimitive?.content.orEmpty(), json["sha"]?.jsonPrimitive?.content.orEmpty())
            check(route.revision > 0)
            if (route.mode == AiExecutionMode.LOCAL) AiServiceConfig.local(route.id, route.sha, route.revision)
            return route
        } catch (_: Exception) { throw IllegalStateException("AI模式记录不可用，请重新选择模式；未启用云端服务") }
    }
    private fun write(route: Route) {
        check(directory.mkdirs() || directory.isDirectory)
        val pending = File(directory, "mode.pending")
        val bytes = buildJsonObject {
            put("mode", route.mode.name); put("revision", route.revision); put("id", route.id); put("sha", route.sha)
        }.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        Files.move(pending.toPath(), File(directory, "mode.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun revision(old: Route) = maxOf(System.currentTimeMillis(), old.revision + 1)
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private suspend fun config(route: Route): AiServiceConfig? = if (route.mode == AiExecutionMode.LOCAL) {
        AiServiceConfig.local(route.id, route.sha, route.revision)
    } else cloud.config()?.let { AiServiceConfig.checked(it.endpoint, it.model, it.apiKey, maxOf(it.revision, route.revision)) }
    override suspend fun config(): AiServiceConfig? = locked { config(read()) }
    suspend fun mode(): AiExecutionMode = locked { read().mode }
    suspend fun selectLocal(modelId: String, sha256: String): AiServiceConfig = withContext(Dispatchers.IO) { sends.transition { locked {
        AiServiceConfig.local(modelId, sha256, 1)
        val previous = try { read() } catch (_: IllegalStateException) { Route(AiExecutionMode.LOCAL, System.currentTimeMillis()) }
        cloud.pauseAutomatic()
        val route = Route(AiExecutionMode.LOCAL, revision(previous), modelId, sha256)
        write(route)
        config(route)!!
    } } }
    suspend fun selectCloud(): AiServiceConfig? = withContext(Dispatchers.IO) { sends.transition { locked {
        val previous = try { read() } catch (_: IllegalStateException) { Route(AiExecutionMode.LOCAL, System.currentTimeMillis()) }
        cloud.pauseAutomatic()
        val route = Route(AiExecutionMode.CLOUD, maxOf(revision(previous), (cloud.config()?.revision ?: 0) + 1))
        write(route)
        config(route)
    } } }
    override suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig = withContext(Dispatchers.IO) { sends.transition { locked {
        val previous = try { read() } catch (_: IllegalStateException) { Route(AiExecutionMode.LOCAL, System.currentTimeMillis()) }
        val saved = cloud.saveConfig(address, model, key)
        cloud.pauseAutomatic()
        val route = Route(AiExecutionMode.CLOUD, maxOf(revision(previous), saved.revision + 1))
        write(route)
        config(route)!!
    } } }
    override suspend fun consents(): List<AiAutoConsent> = locked {
        val local = read().mode == AiExecutionMode.LOCAL
        cloud.consents().map { if (local) it.copy(enabled = false) else it }
    }
    override suspend fun setConsent(consent: AiAutoConsent) = locked {
        check(!consent.enabled || read().mode == AiExecutionMode.CLOUD) { "手机离线模式不启用云端自动回顾" }
        cloud.setConsent(consent)
    }
}
