package com.example.lifemanager.data.diary.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.lifemanager.domain.diary.ai.*
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** No SharedPreferences, Room, saved-state or backup paths contain the secret or queue. */
class AiPrivateStore(context: Context) : AiStateStore {
    private val directory = File(context.noBackupFilesDir, "diary-ai").apply { check(mkdirs() || isDirectory) }
    private val mutex = Mutex()
    private val secretFile = AtomicFile(File(directory, "service.aesgcm"))
    private val stateFile = AtomicFile(File(directory, "consent-jobs.json"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun readConfig(): AiServiceConfig? {
        if (!secretFile.baseFile.exists()) return null
        try {
            val bytes = secretFile.readFully(); require(bytes.size in 30..20_000)
            val input = ByteBuffer.wrap(bytes); val ivSize = input.get().toInt(); require(ivSize == 12)
            val iv = ByteArray(ivSize).also { input.get(it) }; val encrypted = ByteArray(input.remaining()).also { input.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(AAD) }
            val plain = cipher.doFinal(encrypted)
            try {
                val json = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
                return AiServiceConfig.checked(json.getValue("endpoint").jsonPrimitive.content, json.getValue("model").jsonPrimitive.content, json.getValue("key").jsonPrimitive.content, json.getValue("revision").jsonPrimitive.long)
            } finally { plain.fill(0) }
        } catch (_: Exception) { throw IllegalStateException("AI密钥不可用，请重新填写服务配置") }
    }
    private fun state(): JsonObject = if (!stateFile.baseFile.exists()) buildJsonObject {} else try {
        require(stateFile.baseFile.length() <= 1024 * 1024)
        Json.parseToJsonElement(stateFile.readFully().toString(Charsets.UTF_8)).jsonObject
    } catch (_: Exception) { throw IllegalStateException("AI自动任务状态不可用，请重新配置自动授权") }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun saveState(value: JsonObject) = write(stateFile, value.toString().toByteArray())
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    override suspend fun config() = locked { readConfig() }
    override suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig = locked {
        val old = try { readConfig() } catch (_: Exception) { null }
        val config = AiServiceConfig.checked(address, model, key, maxOf(System.currentTimeMillis(), (old?.revision ?: 0) + 1))
        val plain = buildJsonObject { put("endpoint", config.endpoint); put("model", config.model); put("key", config.apiKey); put("revision", config.revision) }.toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, this@AiPrivateStore.key()); updateAAD(AAD) }
            val encrypted = cipher.doFinal(plain)
            write(secretFile, byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
            pauseInternal()
            config
        } finally { plain.fill(0) }
    }
    private fun readConsents() = state()["consents"]?.jsonArray.orEmpty().map { element ->
        val value = element.jsonObject
        AiAutoConsent(AiPeriodKind.valueOf(value.getValue("kind").jsonPrimitive.content), LocalDate.parse(value.getValue("date").jsonPrimitive.content), value.getValue("revision").jsonPrimitive.long, value.getValue("origin").jsonPrimitive.content, value.getValue("enabled").jsonPrimitive.boolean, value["generation"]?.jsonPrimitive?.long ?: -1L)
    }
    override suspend fun consents() = locked { readConsents() }
    override suspend fun setConsent(consent: AiAutoConsent) = locked {
        val items = readConsents().filterNot { it.kind == consent.kind } + consent
        saveState(JsonObject(state() + ("consents" to consentJson(items))))
    }
    private fun consentJson(items: List<AiAutoConsent>) = buildJsonArray { items.forEach { item -> add(buildJsonObject {
        put("kind", item.kind.name); put("date", item.enabledOn.toString()); put("revision", item.configRevision); put("origin", item.origin); put("enabled", item.enabled);put("generation",item.generation)
    }) } }
    private fun pauseInternal() { val current = state(); saveState(JsonObject(current + ("consents" to consentJson(readConsents().map { it.copy(enabled = false) })))) }
    override suspend fun pauseAutomatic() = locked { pauseInternal() }
    private fun readJobs() = state()["jobs"]?.jsonArray.orEmpty().map { element ->
        val value = element.jsonObject
        AiAutoJob(AiPeriod(AiPeriodKind.valueOf(value.getValue("kind").jsonPrimitive.content), LocalDate.parse(value.getValue("from").jsonPrimitive.content), LocalDate.parse(value.getValue("through").jsonPrimitive.content)), value.getValue("revision").jsonPrimitive.long, value.getValue("generation").jsonPrimitive.long, AiJobState.valueOf(value.getValue("state").jsonPrimitive.content), value.getValue("attempts").jsonPrimitive.int, value["after"]?.jsonPrimitive?.long ?: 0)
    }
    override suspend fun jobs() = locked { readJobs() }
    override suspend fun putJob(job: AiAutoJob) = locked {
        val jobs = (readJobs().filterNot { it.period.key == job.period.key } + job).sortedByDescending { it.period.from }.take(200)
        saveState(JsonObject(state() + ("jobs" to buildJsonArray { jobs.forEach { item -> add(buildJsonObject {
            put("kind", item.period.kind.name); put("from", item.period.from.toString()); put("through", item.period.through.toString()); put("revision", item.configRevision); put("generation", item.generation); put("state", item.state.name); put("attempts", item.attempts); put("after", item.notBeforeMillis)
        }) } })))
    }
    companion object { private const val ALIAS = "lifemanager.diary.ai.v1"; private val AAD = "lifemanager.diary.ai.config.v1".toByteArray() }
}
