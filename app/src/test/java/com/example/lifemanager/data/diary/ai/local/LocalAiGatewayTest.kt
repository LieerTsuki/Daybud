package com.example.lifemanager.data.diary.ai.local

import android.os.ParcelFileDescriptor
import com.example.lifemanager.domain.diary.ai.*
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalAiGatewayTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun lease(): LocalModelLease {
        val file = temporary.newFile().apply { writeText("GGUF test descriptor".repeat(4)) }
        val spec = LocalModelSpec("test", "测试", "org/repo", "a".repeat(40), "model.gguf", file.length(), "b".repeat(64))
        return LocalModelLease(ReadyLocalModel(spec, file), ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {}
    }
    private class Session : LocalInferenceSession {
        var counted = 0; var generated = 0; var closed = false; var cancelled = false
        var wait = false
        override suspend fun countTokens(system: String, user: String): Int { counted++; return 400 }
        override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
            generated++
            if (wait) awaitCancellation()
            return LocalInferenceOutput("""{"items":[{"sourceId":1,"life":2,"moment":0,"reflection":0,"reminder":0}]}""", 400, 100, true)
        }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    /** Exercises the real host's asynchronous-close ownership boundary, not a fake busy code. */
    private class DelayedCloseFactory(private val releaseOnRejection: Boolean = true, private val guardValue: () -> Int = { 0 }) : LocalInferenceSessionFactory, java.io.Closeable {
        private val host = LocalInferenceHost()
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val oldThread: Thread
        var attempts = 0
        var generations = 0
        val guardsAtOpen = mutableListOf<Int>()
        init {
            host.open("old") { object : LocalNativeRuntime {
                override fun countTokens(system: String, user: String) = 400
                override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return LocalInferenceOutput("{}", 400, 1, true)
                }
                override fun cancel() = Unit // deliberate slow native abort, ownership must persist
                override fun close() = Unit
            } }
            oldThread = Thread {
                try { host.generate("old", "s", "u", "g", 1) }
                catch (_: kotlinx.coroutines.CancellationException) { }
            }.apply { start() }
            check(entered.await(5, TimeUnit.SECONDS))
            host.close("old")
        }
        override suspend fun open(model: LocalModelLease): LocalInferenceSession {
            attempts++; guardsAtOpen += guardValue()
            val id = "fresh-$attempts"
            try {
                host.open(id) { object : LocalNativeRuntime {
                    override fun countTokens(system: String, user: String) = 400
                    override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
                        generations++
                        return LocalInferenceOutput("""{"items":[{"sourceId":1,"life":2,"moment":0,"reflection":0,"reminder":0}]}""", 400, 100, true)
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                } }
            } catch (busy: LocalAiException) {
                if (releaseOnRejection) { release.countDown(); oldThread.join(2000) }
                throw busy
            }
            return object : LocalInferenceSession {
                override suspend fun countTokens(system: String, user: String) = host.countTokens(id, system, user)
                override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = host.generate(id, system, user, grammar, maxOutputTokens)
                override fun cancel() = host.cancel(id)
                override fun close() = host.close(id)
            }
        }
        override fun close() { release.countDown(); oldThread.join(2000); host.closeCurrent() }
    }

    @Test fun probeLoadsAndTokenizesWithoutGeneratingAnyDiary() = runTest {
        val session = Session()
        val gateway = LocalAiGateway(LocalInferenceSessionFactory { session })
        lease().use { gateway.probe(it) }
        assertTrue(session.counted > 0); assertEquals(0, session.generated); assertTrue(session.closed)
    }

    @Test fun generationReleasesTheInferenceSessionOnSuccess() = runTest {
        val session = Session()
        val gateway = LocalAiGateway(LocalInferenceSessionFactory { session })
        val source = AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "散步", "今天傍晚散步", null)
        val sections = lease().use { gateway.generate(it, listOf(source), {}) }
        assertEquals(5, sections.size); assertTrue(session.closed)
    }

    @Test fun userCancellationCancelsAndClosesNativeWork() = runTest {
        val session = Session().apply { wait = true }
        val gateway = LocalAiGateway(LocalInferenceSessionFactory { session })
        val source = AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "散步", "今天傍晚散步", null)
        lease().use { model ->
            val job = launch { gateway.generate(model, listOf(source), {}) }
            runCurrent(); assertEquals(1, session.generated)
            job.cancel(); runCurrent()
            assertTrue(session.cancelled); assertTrue(session.closed); assertTrue(job.isCancelled)
        }
    }

    @Test fun immediateProbeAfterSlowNativeCloseUsesProductionRecovery() = runTest {
        DelayedCloseFactory().use { factory ->
            lease().use { LocalAiGateway(factory).probe(it) }
            assertEquals(2, factory.attempts)
            assertEquals(0, factory.generations)
        }
    }

    @Test fun immediateGenerationAfterSlowCloseRechecksSourcesBeforeOpeningAgain() = runTest {
        var guards = 0
        DelayedCloseFactory { guards }.use { factory ->
            val entry = AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "散步", "今天傍晚散步", null)
            val result = lease().use { LocalAiGateway(factory).generate(it, listOf(entry), { guards++ }) }
            assertEquals(5, result.size)
            assertEquals(2, factory.attempts)
            assertTrue(factory.guardsAtOpen[1] > factory.guardsAtOpen[0])
            assertEquals(1, factory.generations)
        }
    }

    @Test fun cancellationDuringBusyOpenWaitNeverStartsNewInference() = runTest {
        DelayedCloseFactory(releaseOnRejection = false).use { factory ->
            val entry = AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "散步", "今天傍晚散步", null)
            var failure: LocalAiException? = null
            lease().use { model ->
                val job = launch { try { LocalAiGateway(factory).generate(model, listOf(entry), {}) } catch (error: LocalAiException) { failure = error } }
                runCurrent()
                assertTrue("Busy open must wait before retry", job.isActive)
                job.cancel(); runCurrent()
                assertTrue(job.isCancelled)
                assertNull(failure)
                assertEquals(1, factory.attempts)
                assertEquals(0, factory.generations)
            }
        }
    }

    @Test fun staleSourceDuringOpenWaitStopsBeforeSecondOpen() = runTest {
        DelayedCloseFactory().use { factory ->
            val entry = AiDiarySource(1, 1, LocalDate.of(2026, 10, 19), "散步", "今天傍晚散步", null)
            try {
                lease().use { LocalAiGateway(factory).generate(it, listOf(entry), { if (factory.attempts > 0) throw AiStaleException() }) }
                fail("Sources changed while waiting for resource release")
            } catch (_: AiStaleException) { }
            assertEquals(1, factory.attempts)
            assertEquals(0, factory.generations)
        }
    }

    @Test fun permanentlyBusyHostStopsOpeningAfterThreeSecondBudget() = runTest {
        DelayedCloseFactory(releaseOnRejection = false).use { factory ->
            try { lease().use { LocalAiGateway(factory).probe(it) }; fail("Busy model cannot wait indefinitely") }
            catch (error: LocalAiException) { assertEquals("BUSY", error.code.name) }
            assertTrue(factory.attempts > 1)
            assertTrue(testScheduler.currentTime in 2900..3100)
            assertEquals(0, factory.generations)
        }
    }
}
