package com.example.lifemanager.data.diary.ai.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LocalInferenceHostTest {
    @Test fun nextModelWaitsUntilPreviousNativeCloseHasActuallyFinished() {
        val host = LocalInferenceHost(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        host.open("old") { object : LocalNativeRuntime {
            override fun countTokens(system: String, user: String) = 1
            override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("{}", 1, 1, true)
            override fun cancel() = Unit
            override fun close() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        } }
        val closing = Thread { host.close("old") }; closing.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try { host.open("new") { Runtime() }; fail("A new model loaded before native release finished") }
            catch (failure: LocalAiException) { assertEquals(LocalAiFailure.BUSY, failure.code) }
        } finally { release.countDown(); closing.join(5000); host.close("new") }
        host.open("recovered") { Runtime() }; host.close("recovered")
    }
    private class Runtime : LocalNativeRuntime {
        var cancelled = false
        var closed = false
        override fun countTokens(system: String, user: String) = 18
        override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("{}", 18, 2, true)
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    @Test fun aSecondSessionCannotReplaceAnOpenModel() {
        val host = LocalInferenceHost()
        val runtime = Runtime()
        host.open("first") { runtime }
        try { host.open("second") { Runtime() }; fail("Cannot replace another client's session") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.BUSY, error.code) }
        assertEquals(18, host.countTokens("first", "system", "user"))
        host.close("first")
        assertTrue(runtime.closed)
    }

    @Test fun oldSessionMessagesCannotCancelOrCloseANewerModel() {
        val host = LocalInferenceHost()
        val first = Runtime(); host.open("first") { first }; host.close("first")
        val second = Runtime(); host.open("second") { second }
        host.cancel("first"); host.close("first")
        assertFalse(second.cancelled); assertFalse(second.closed)
        assertEquals(18, host.countTokens("second", "s", "u"))
        host.close("second")
    }

    @Test fun cancellationDuringModelLoadingDiscardsTheLateModel() {
        val host = LocalInferenceHost()
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        val runtime = Runtime(); val error = AtomicReference<Throwable>()
        val loading = Thread {
            try { host.open("first") { started.countDown(); check(finish.await(2, TimeUnit.SECONDS)); runtime } }
            catch (failure: Throwable) { error.set(failure) }
        }
        loading.start(); assertTrue(started.await(2, TimeUnit.SECONDS))
        host.cancel("first"); assertTrue(host.hasPendingCancellation("first"))
        finish.countDown(); loading.join(2000)
        assertTrue(error.get() is CancellationException)
        assertTrue(runtime.closed)
    }

    @Test fun allTextBoundsCountUtf8RatherThanUtf16CodeUnits() {
        try { LocalIpcPolicy.checkText("中".repeat(20_000), 48 * 1024); fail("UTF8 payload must be bounded") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.IPC, error.code) }
        LocalIpcPolicy.checkText("🙂".repeat(100), 1000)
    }

    @Test fun oversizedCombinedTransactionIsRejected() {
        try { LocalIpcPolicy.checkTransaction(128 * 1024 + 1); fail("Binder transaction must be bounded") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.IPC, error.code) }
    }
}
