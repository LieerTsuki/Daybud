package com.example.lifemanager.data.diary.ai.local

import android.os.Build
import com.example.lifemanager.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/** Used only in the inference service. It owns a native duplicate of the model FD. */
class NativeLlama private constructor(handle: Long) : AutoCloseable {
    private val handle = AtomicLong(handle)

    data class Output(val utf8: ByteArray, val promptTokens: Int, val generatedTokens: Int, val reachedEos: Boolean) {
        val text: String get() = utf8.decodeToString(throwOnInvalidSequence = true)
        val finishReason: String get() = if (reachedEos) "eos" else "length"
    }

    fun countTokens(system: String, user: String): Int = NativeLlamaBindings.countTokens(
        current(), system.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8))

    fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): Output =
        NativeLlamaBindings.generate(current(), system.toByteArray(Charsets.UTF_8),
            user.toByteArray(Charsets.UTF_8), grammar.toByteArray(Charsets.UTF_8), maxOutputTokens)

    /** May be called from another Binder thread while decode is executing. */
    fun cancel() { handle.get().takeIf { it != 0L }?.let(NativeLlamaBindings::cancel) }

    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(NativeLlamaBindings::close) }
    private fun current(): Long = handle.get().also { check(it != 0L) { "Local model session is closed" } }

    companion object {
        fun open(modelFd: Int, contextTokens: Int = 4096, threads: Int = 2): NativeLlama =
            NativeLlama(NativeLlamaBindings.open(modelFd, contextTokens, threads))
    }
}

private object NativeLlamaBindings {
    init {
        EngineLibraryLoader(
            isArm64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
            candidateEnabled = BuildConfig.DIARY_DOTPROD_CANDIDATE,
            hasDotProduct = { NativeCpuFeatures.hasArmDotProduct() },
            loadLibrary = System::loadLibrary,
        ).load()
    }
    external fun open(modelFd: Int, contextTokens: Int, threads: Int): Long
    external fun countTokens(handle: Long, system: ByteArray, user: ByteArray): Int
    external fun generate(handle: Long, system: ByteArray, user: ByteArray, grammar: ByteArray, maxOutputTokens: Int): NativeLlama.Output
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

private object NativeCpuFeatures {
    init { System.loadLibrary("diary_cpu_features") }
    external fun hasArmDotProduct(): Boolean
}
