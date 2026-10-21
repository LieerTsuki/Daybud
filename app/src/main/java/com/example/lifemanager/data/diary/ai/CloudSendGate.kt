package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.AiStaleException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes a completed mode change against the actual request send boundary. */
class CloudSendGate {
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val active = AtomicReference<(() -> Unit)?>()
    suspend fun <T> send(cancel: () -> Unit, block: suspend () -> T): T {
        val admitted = epoch.get()
        return mutex.withLock {
            if (admitted != epoch.get()) throw AiStaleException()
            active.set(cancel)
            try {
                if (admitted != epoch.get()) throw AiStaleException()
                block()
            } finally { active.compareAndSet(cancel, null) }
        }
    }
    suspend fun <T> transition(block: suspend () -> T): T {
        epoch.incrementAndGet()
        runCatching { active.get()?.invoke() }
        return mutex.withLock { block() }
    }
}
