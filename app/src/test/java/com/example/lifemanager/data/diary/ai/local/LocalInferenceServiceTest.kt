package com.example.lifemanager.data.diary.ai.local

import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.Looper
import android.os.Process
import android.os.Handler
import android.os.Message
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class LocalInferenceServiceTest {
    @Test fun oldServiceDestructionBetweenNewCloseWatchdogAndCancellationCannotRemoveTheNewDeadline() {
        val oldService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val newService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val hostField = LocalInferenceService::class.java.getDeclaredField("host").apply { isAccessible = true }
        val host = hostField.get(newService) as LocalInferenceHost
        val watchdogField = LocalInferenceService::class.java.getDeclaredField("watchdog").apply { isAccessible = true }
        val original = watchdogField.get(newService) as Handler
        val interleaveOnce = AtomicBoolean(true)
        // Deterministically schedule the valid cross-thread interleaving: CLOSE
        // posts its deadline, old onDestroy runs, then CLOSE marks the load cancelled.
        val sharedWatchdog = object : Handler(original.looper) {
            override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean {
                val accepted = super.sendMessageAtTime(message, uptimeMillis)
                if (interleaveOnce.compareAndSet(true, false)) oldService.onDestroy()
                return accepted
            }
        }
        watchdogField.set(oldService, sharedWatchdog)
        watchdogField.set(newService, sharedWatchdog)
        val id = "new-loading-owner"
        own(newService, id)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val loading = Thread {
            try { host.open(id) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                object : LocalNativeRuntime {
                    override fun countTokens(system: String, user: String) = 1
                    override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("", 1, 0, false)
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            } } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        ShadowProcess.clearKilledProcesses()
        try {
            loading.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val binder = newService.onBind(Intent())
            request.writeInterfaceToken(LocalInferenceService.DESCRIPTOR); request.writeString(id)
            assertTrue(binder.transact(LocalInferenceService.CLOSE, request, reply, 0))
            reply.readException(); assertEquals(0, reply.readInt())
            assertTrue(host.hasPendingCancellation(id))
            shadowOf(sharedWatchdog.looper).idleFor(Duration.ofSeconds(3))
            assertTrue("Old Service destruction removed the new owner's cancellation deadline", ShadowProcess.wasKilled(Process.myPid()))
        } finally {
            release.countDown(); loading.join(5000)
            request.recycle(); reply.recycle(); newService.onDestroy()
        }
    }

    @Test fun destroyedServiceRejectsLateOpenBeforeNativeLoading() {
        val service = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val binder = service.onBind(Intent())
        service.onDestroy()
        val file = java.io.File.createTempFile("late-model", ".gguf").apply { writeBytes(ByteArray(32)) }
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                request.writeInterfaceToken(LocalInferenceService.DESCRIPTOR); request.writeString("late")
                fd.writeToParcel(request, 0); request.writeInt(4096); request.writeInt(2)
                assertTrue(binder.transact(LocalInferenceService.OPEN, request, reply, 0))
                reply.readException()
                assertEquals(LocalAiFailure.IPC.ordinal + 1, reply.readInt())
            }
        } finally { request.recycle(); reply.recycle(); file.delete() }
    }
    @Test fun serviceRecreationCannotStartASecondModelWhileOldDecodeIsStillReleasing() {
        val oldService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val field = LocalInferenceService::class.java.getDeclaredField("host").apply { isAccessible = true }
        val oldHost = field.get(oldService) as LocalInferenceHost
        own(oldService, "old-service")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        oldHost.open("old-service") { object : LocalNativeRuntime {
            override fun countTokens(system: String, user: String) = 1
            override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                return LocalInferenceOutput("{}", 1, 1, true)
            }
            override fun cancel() = Unit
            override fun close() = Unit
        } }
        val decoding = Thread { try { oldHost.generate("old-service", "s", "u", "g", 1) } catch (_: kotlinx.coroutines.CancellationException) { } }
        val newService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val newHost = field.get(newService) as LocalInferenceHost
        var secondModelStarted = false
        try {
            decoding.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            oldService.onUnbind(Intent()); oldService.onDestroy()
            try {
                newHost.open("new-service") {
                    secondModelStarted = true
                    object : LocalNativeRuntime {
                        override fun countTokens(system: String, user: String) = 1
                        override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("{}", 1, 1, true)
                        override fun cancel() = Unit
                        override fun close() = Unit
                    }
                }
                fail("A recreated Service must not load beside the old hung decode")
            } catch (busy: LocalAiException) { assertEquals("BUSY", busy.code.name) }
            assertFalse(secondModelStarted)
        } finally {
            release.countDown(); decoding.join(5000)
            oldHost.close("old-service"); newHost.close("new-service")
            newService.onDestroy()
        }
    }

    @Test fun malformedMessageIsRejectedWithoutLoadingNativeLibrary() {
        val service = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val binder = service.onBind(Intent())
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(LocalInferenceService.DESCRIPTOR)
            request.writeString("session")
            request.writeString("中".repeat(20_000))
            request.writeString("user")
            assertTrue(binder.transact(LocalInferenceService.COUNT, request, reply, 0))
            reply.readException()
            assertEquals(LocalAiFailure.IPC.ordinal + 1, reply.readInt())
        } finally { request.recycle(); reply.recycle(); service.onDestroy() }
    }

    @Test fun lostBindingStillTerminatesANativeLoadThatDoesNotReturn() {
        val service = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val field = LocalInferenceService::class.java.getDeclaredField("host").apply { isAccessible = true }
        val host = field.get(service) as LocalInferenceHost
        own(service, "loading")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val loading = Thread {
            try { host.open("loading") {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                object : LocalNativeRuntime {
                    override fun countTokens(system: String, user: String) = 1
                    override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("", 1, 0, false)
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            } } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        ShadowProcess.clearKilledProcesses()
        try {
            loading.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.onUnbind(Intent()); service.onDestroy()
            val handler = LocalInferenceService::class.java.getDeclaredField("watchdog").apply { isAccessible = true }.get(service) as android.os.Handler
            shadowOf(handler.looper).idleFor(Duration.ofSeconds(3))
            assertTrue("Lost clients must not leave an uncancellable model load alive", ShadowProcess.wasKilled(Process.myPid()))
        } finally { release.countDown(); loading.join(5000) }
    }

    @Test fun destroyingAnOldServiceCannotCloseANewerServiceSession() {
        val oldService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val newService = Robolectric.buildService(LocalInferenceService::class.java).create().get()
        val field = LocalInferenceService::class.java.getDeclaredField("host").apply { isAccessible = true }
        val host = field.get(newService) as LocalInferenceHost
        var closed = false
        own(newService, "new-owner")
        host.open("new-owner") { object : LocalNativeRuntime {
            override fun countTokens(system: String, user: String) = 1
            override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int) = LocalInferenceOutput("{}", 1, 1, true)
            override fun cancel() = Unit
            override fun close() { closed = true }
        } }
        try {
            oldService.onDestroy()
            assertEquals(1, host.countTokens("new-owner", "s", "u"))
            assertFalse(closed)
        } finally { newService.onDestroy() }
        assertTrue(closed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun own(service: LocalInferenceService, id: String) {
        val field = LocalInferenceService::class.java.getDeclaredField("ownedSessions").apply { isAccessible = true }
        (field.get(service) as MutableSet<String>).add(id)
    }
}
