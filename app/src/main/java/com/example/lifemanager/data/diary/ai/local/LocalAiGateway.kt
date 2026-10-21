package com.example.lifemanager.data.diary.ai.local

import com.example.lifemanager.domain.diary.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

fun interface LocalInferenceSessionFactory {
    suspend fun open(model: LocalModelLease): LocalInferenceSession
}

class LocalAiGateway(private val sessions: LocalInferenceSessionFactory) {
    private val mutex = Mutex()

    suspend fun generate(model: LocalModelLease, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, onProgress: (LocalAiProgress) -> Unit = {}): List<AiReviewSection> = mutex.withLock {
        currentCoroutineContext().ensureActive(); beforeStep()
        onProgress(LocalAiProgress(LocalAiStage.PREPARING, 0, 0))
        withTimeoutOrNull(180_000) {
            openReady(model, beforeStep).use { session ->
                try {
                    LocalGroundedPipeline(session, LocalAiBudget(contextTokens = model.contextTokens)).generate(sources, beforeStep, onProgress)
                } catch (cancel: CancellationException) { session.cancel(); throw cancel }
            }
        } ?: throw LocalAiException(LocalAiFailure.INFERENCE)
    }

    /** A probe loads the real model and tokenizer; it does not claim diary quality has passed. */
    suspend fun probe(model: LocalModelLease, beforeStep: suspend () -> Unit = {}): Unit = mutex.withLock {
        val succeeded = withTimeoutOrNull(30_000) {
            openReady(model, beforeStep).use { session ->
                try {
                    if (session.countTokens("你是日记助手。", "检查分词，不生成日记。") <= 0) throw LocalAiException(LocalAiFailure.LOAD)
                    true
                } catch (cancel: CancellationException) { session.cancel(); throw cancel }
            }
        }
        if (succeeded != true) throw LocalAiException(LocalAiFailure.LOAD)
    }

    /** Only a rejected OPEN is safe to repeat: no tokenizer or decode request is replayed. */
    internal suspend fun openReady(model: LocalModelLease, beforeAttempt: suspend () -> Unit = {}): LocalInferenceSession {
        val deadline = System.nanoTime() + 3_000_000_000L
        var remainingDelayMillis = 3000L
        while (true) {
            currentCoroutineContext().ensureActive(); beforeAttempt()
            try { return sessions.open(model) }
            catch (failure: LocalAiException) {
                if (failure.code != LocalAiFailure.BUSY) throw failure
                val remainingWallMillis = (deadline - System.nanoTime()) / 1_000_000L
                val remaining = minOf(remainingWallMillis, remainingDelayMillis)
                if (remaining <= 0) throw failure
                val wait = minOf(100L, remaining)
                delay(wait)
                remainingDelayMillis -= wait
            }
        }
    }
}
