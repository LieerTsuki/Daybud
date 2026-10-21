package com.example.lifemanager.data.diary.ai.local

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Private CPU inference only. No Hilt, business storage, workers or network clients. */
class LocalInferenceService : Service() {
    // Android may recreate a Service while the old instance still has a native Binder call.
    // Runtime ownership therefore belongs to the process, not to a Service instance.
    private val host = processHost
    private val ownedSessions = ConcurrentHashMap.newKeySet<String>()
    private val accepting = AtomicBoolean(true)
    private val destroyed = AtomicBoolean(false)
    private val watchdog = processWatchdog
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code !in OPEN..CLOSE) return super.onTransact(code, data, reply, flags)
            try {
                if (getCallingUid() != Process.myUid()) throw LocalAiException(LocalAiFailure.IPC)
                LocalIpcPolicy.checkTransaction(data.dataSize())
                data.enforceInterface(DESCRIPTOR)
                val id = data.readString()?.takeIf { it.isNotBlank() && it.length <= 64 } ?: throw LocalAiException(LocalAiFailure.IPC)
                if (code != OPEN && id !in ownedSessions) throw LocalAiException(LocalAiFailure.IPC)
                when (code) {
                    OPEN -> {
                        if (!accepting.get() || destroyed.get()) throw LocalAiException(LocalAiFailure.IPC)
                        if (!ownedSessions.add(id)) throw LocalAiException(LocalAiFailure.IPC)
                        try { ParcelFileDescriptor.CREATOR.createFromParcel(data).use { descriptor ->
                            val contextTokens = data.readInt(); val threads = data.readInt()
                            if (contextTokens !in 1024..4096 || threads !in 1..4 || data.dataAvail() != 0) throw LocalAiException(LocalAiFailure.IPC)
                            host.open(id, { accepting.get() && !destroyed.get() && id in ownedSessions }) {
                                val native = try { NativeLlama.open(descriptor.fd, contextTokens, threads) }
                                catch (_: Throwable) { throw LocalAiException(LocalAiFailure.LOAD) }
                                object : LocalNativeRuntime {
                                    override fun countTokens(system: String, user: String) = native.countTokens(system, user)
                                    override fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
                                        val output = native.generate(system, user, grammar, maxOutputTokens)
                                        return LocalInferenceOutput(output.text, output.promptTokens, output.generatedTokens, output.reachedEos)
                                    }
                                    override fun cancel() = native.cancel()
                                    override fun close() = native.close()
                                }
                            }
                        } } catch (failure: Throwable) { ownedSessions.remove(id); throw failure }
                        success(reply)
                    }
                    COUNT -> {
                        val system = text(data, 8 * 1024); val user = text(data)
                        if (data.dataAvail() != 0) throw LocalAiException(LocalAiFailure.IPC)
                        val tokens = host.countTokens(id, system, user)
                        success(reply); reply?.writeInt(tokens)
                    }
                    GENERATE -> {
                        val system = text(data, 8 * 1024); val user = text(data); val grammar = text(data, 16 * 1024)
                        val maxOutput = data.readInt()
                        if (maxOutput !in 1..1024 || data.dataAvail() != 0) throw LocalAiException(LocalAiFailure.IPC)
                        val output = host.generate(id, system, user, grammar, maxOutput)
                        LocalIpcPolicy.checkText(output.text)
                        success(reply)
                        reply?.apply { writeString(output.text); writeInt(output.promptTokens); writeInt(output.generatedTokens); writeInt(if (output.reachedEos) 1 else 0) }
                    }
                    CANCEL, CLOSE -> {
                        if (data.dataAvail() != 0) throw LocalAiException(LocalAiFailure.IPC)
                        if (code == CANCEL) host.cancel(id) else ownedSessions.remove(id)
                        // Model loading has no interruptible handle yet. If native code has not
                        // returned within the cancellation budget, terminate this private process.
                        watchdog.postDelayed({ if (host.hasPendingCancellation(id)) Process.killProcess(Process.myPid()) }, 2000)
                        if (code == CLOSE) host.close(id)
                        success(reply)
                    }
                }
                reply?.let { LocalIpcPolicy.checkTransaction(it.dataSize()) }
            } catch (failure: Throwable) {
                val reason = when (failure) {
                    is LocalAiException -> failure.code
                    is CancellationException -> LocalAiFailure.INCOMPLETE
                    else -> LocalAiFailure.INFERENCE
                }
                reply?.apply { setDataSize(0); setDataPosition(0); writeNoException(); writeInt(reason.ordinal + 1) }
            }
            return true
        }
    }

    private fun success(reply: Parcel?) { reply?.writeNoException(); reply?.writeInt(0) }
    private fun text(data: Parcel, maxBytes: Int = LocalIpcPolicy.MAX_TEXT_BYTES): String =
        (data.readString() ?: throw LocalAiException(LocalAiFailure.IPC)).also { LocalIpcPolicy.checkText(it, maxBytes) }

    override fun onBind(intent: Intent?): IBinder {
        check(!destroyed.get()); accepting.set(true)
        return binder
    }
    override fun onUnbind(intent: Intent?): Boolean { closeLostClient(); return false }
    override fun onDestroy() {
        destroyed.set(true)
        closeLostClient()
        // This process-wide queue also owns newer Services' deadlines. Each
        // callback checks its session UUID and expires after two seconds.
        super.onDestroy()
    }
    private fun closeLostClient() {
        accepting.set(false)
        ownedSessions.toList().forEach { id ->
            ownedSessions.remove(id)
            host.cancel(id)
            watchdog.postDelayed({
                if (host.hasPendingCancellation(id)) Process.killProcess(Process.myPid())
            }, 2000)
            host.close(id)
        }
    }

    companion object {
        private val processHost = LocalInferenceHost()
        private val processWatchdog = Handler(HandlerThread("diary-inference-watchdog").apply { isDaemon = true; start() }.looper)
        internal const val DESCRIPTOR = "com.example.lifemanager.private.DiaryInference"
        internal const val OPEN = IBinder.FIRST_CALL_TRANSACTION
        internal const val COUNT = OPEN + 1
        internal const val GENERATE = OPEN + 2
        internal const val CANCEL = OPEN + 3
        internal const val CLOSE = OPEN + 4
    }
}
