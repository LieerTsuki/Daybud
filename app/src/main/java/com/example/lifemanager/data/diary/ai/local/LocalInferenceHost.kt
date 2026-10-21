package com.example.lifemanager.data.diary.ai.local

import java.io.Closeable
import kotlinx.coroutines.CancellationException

interface LocalNativeRuntime : Closeable {
    fun countTokens(system: String, user: String): Int
    fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput
    fun cancel()
}

internal object LocalIpcPolicy {
    const val MAX_TRANSACTION_BYTES = 128 * 1024
    const val MAX_TEXT_BYTES = 48 * 1024
    fun checkText(value: String, maxBytes: Int = MAX_TEXT_BYTES) {
        if (value.toByteArray(Charsets.UTF_8).size > maxBytes) throw LocalAiException(LocalAiFailure.IPC)
    }
    fun checkTransaction(bytes: Int) {
        if (bytes < 0 || bytes > MAX_TRANSACTION_BYTES) throw LocalAiException(LocalAiFailure.IPC)
    }
}

/** Serializes ownership without holding a lock during slow model loading or decode. */
class LocalInferenceHost {
    private class State(val id: String, var runtime: LocalNativeRuntime? = null, var busy: Boolean = true, var cancelled: Boolean = false, var closing: Boolean = false, var releasing: Boolean = false)
    private val lock = Any()
    private var active: State? = null

    fun open(id: String, admitted: () -> Boolean = { true }, factory: () -> LocalNativeRuntime) {
        val state = synchronized(lock) {
            if (id.isBlank() || id.length > 64) throw LocalAiException(LocalAiFailure.IPC)
            if (active != null) throw LocalAiException(LocalAiFailure.BUSY)
            if (!admitted()) throw LocalAiException(LocalAiFailure.IPC)
            State(id).also { active = it }
        }
        var loaded: LocalNativeRuntime? = null
        try {
            loaded = factory()
            synchronized(lock) {
                if (active !== state || state.cancelled) throw CancellationException("Local model loading cancelled")
                state.runtime = loaded; state.busy = false; loaded = null
            }
        } catch (failure: Throwable) {
            synchronized(lock) { state.releasing = true }
            release(state, loaded)
            throw failure
        }
    }

    fun countTokens(id: String, system: String, user: String): Int = execute(id) { it.countTokens(system, user) }
    fun generate(id: String, system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput =
        execute(id) { it.generate(system, user, grammar, maxOutputTokens) }

    private fun <T> execute(id: String, action: (LocalNativeRuntime) -> T): T {
        val state = synchronized(lock) {
            val current = active
            if (current == null || current.id != id || current.busy) throw LocalAiException(LocalAiFailure.IPC)
            if (current.cancelled) throw CancellationException("Local inference cancelled")
            current.busy = true; current
        }
        try {
            val result = action(state.runtime ?: throw LocalAiException(LocalAiFailure.LOAD))
            synchronized(lock) { if (state.cancelled) throw CancellationException("Local inference cancelled") }
            return result
        } finally {
            val close = synchronized(lock) {
                state.busy = false
                if (state.closing && active === state && !state.releasing) { state.releasing = true; state.runtime } else null
            }
            if (close != null) release(state, close)
        }
    }

    fun cancel(id: String) = synchronized(lock) {
        active?.takeIf { it.id == id }?.let { state -> state.cancelled = true; state.runtime?.cancel() }
        Unit
    }

    fun close(id: String) {
        var state: State? = null
        val close = synchronized(lock) {
            val current = active?.takeIf { it.id == id } ?: return
            current.cancelled = true; current.closing = true; current.runtime?.cancel()
            state = current
            if (!current.busy && !current.releasing) { current.releasing = true; current.runtime } else null
        }
        if (close != null) release(state!!, close)
    }
    private fun release(state: State, runtime: LocalNativeRuntime?) {
        try { runtime?.close() } finally {
            synchronized(lock) { if (active === state) active = null; state.releasing = false }
        }
    }
    fun closeCurrent() { synchronized(lock) { active?.id }?.let(::close) }
    fun hasPendingCancellation(id: String): Boolean = synchronized(lock) { active?.let { it.id == id && it.cancelled && (it.busy || it.releasing) } == true }
    fun hasPendingCancellation(): Boolean = synchronized(lock) { active?.let { it.cancelled && (it.busy || it.releasing) } == true }
}
