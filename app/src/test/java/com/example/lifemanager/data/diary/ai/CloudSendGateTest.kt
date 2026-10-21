package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.AiStaleException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CloudSendGateTest {
    @Test fun modeChangeCancelsAdmittedConnectionAndWaitsForItsSendBoundary() = runTest {
        val gate = CloudSendGate(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val sending = launch {
            gate.send({ events += "disconnect"; release.complete(Unit) }) {
                started.complete(Unit); release.await(); events += "send ended"
            }
        }
        started.await()
        val changing = launch { gate.transition { events += "local selected" } }
        yield()
        if (!release.isCompleted) release.complete(Unit) // lets the old stub terminate, without hiding ordering failure
        sending.join(); changing.join()
        assertEquals(listOf("disconnect", "send ended", "local selected"), events)
    }
    @Test fun queuedOldRequestCannotSendAfterModeTransition() = runTest {
        val gate = CloudSendGate(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = launch { gate.send({ release.complete(Unit) }) { started.complete(Unit); release.await() } }
        started.await()
        var sent = false; var stale = false
        val second = launch { try { gate.send({}) { sent = true } } catch (_: AiStaleException) { stale = true } }
        yield()
        val changing = launch { gate.transition { } }
        yield(); release.complete(Unit)
        first.join(); second.join(); changing.join()
        assertFalse(sent); assertTrue(stale)
    }
}
