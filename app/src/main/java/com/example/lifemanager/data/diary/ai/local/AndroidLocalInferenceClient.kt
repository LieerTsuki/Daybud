package com.example.lifemanager.data.diary.ai.local

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidLocalInferenceClient(context: Context) : LocalInferenceSessionFactory {
    private val context = context.applicationContext
    override suspend fun open(model: LocalModelLease): LocalInferenceSession {
        val session = Session(context)
        try {
            session.connect(model)
            return session
        } catch (failure: Throwable) {
            session.cancel(); session.close()
            throw failure
        }
    }

    private class Session(private val context: Context) : LocalInferenceSession {
        private val id = UUID.randomUUID().toString()
        private val connected = CompletableDeferred<IBinder>()
        private val remote = AtomicReference<IBinder?>()
        private val dead = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val bound = AtomicBoolean(false)
        private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "diary-inference-ipc").apply { isDaemon = true } }
        private val pending = ConcurrentHashMap<CancellableContinuation<*>, Unit>()
        private val death = IBinder.DeathRecipient { failDead() }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed.get()) return
                try {
                    service.linkToDeath(death, 0)
                    remote.set(service)
                    connected.complete(service)
                } catch (_: RemoteException) { failDead() }
            }
            override fun onServiceDisconnected(name: ComponentName) = failDead()
            override fun onBindingDied(name: ComponentName) = failDead()
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(LocalAiException(LocalAiFailure.LOAD)) }
        }

        suspend fun connect(model: LocalModelLease) {
            val accepted = try { context.bindService(Intent(context, LocalInferenceService::class.java), connection, Context.BIND_AUTO_CREATE) }
            catch (_: Exception) { false }
            if (!accepted) throw LocalAiException(LocalAiFailure.LOAD)
            bound.set(true)
            if (withTimeoutOrNull(15_000) { connected.await() } == null) throw LocalAiException(LocalAiFailure.LOAD)
            call(LocalInferenceService.OPEN, {
                model.descriptor.writeToParcel(it, 0)
                it.writeInt(model.contextTokens)
                it.writeInt(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
            }) { Unit }
        }

        override suspend fun countTokens(system: String, user: String): Int {
            LocalIpcPolicy.checkText(system, 8 * 1024); LocalIpcPolicy.checkText(user)
            return call(LocalInferenceService.COUNT, { it.writeString(system); it.writeString(user) }) { it.readInt() }
        }

        override suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput {
            LocalIpcPolicy.checkText(system, 8 * 1024); LocalIpcPolicy.checkText(user); LocalIpcPolicy.checkText(grammar, 16 * 1024)
            if (maxOutputTokens !in 1..1024) throw LocalAiException(LocalAiFailure.IPC)
            return call(LocalInferenceService.GENERATE, {
                it.writeString(system); it.writeString(user); it.writeString(grammar); it.writeInt(maxOutputTokens)
            }) {
                val text = it.readString() ?: throw LocalAiException(LocalAiFailure.IPC)
                LocalIpcPolicy.checkText(text)
                LocalInferenceOutput(text, it.readInt(), it.readInt(), it.readInt() == 1)
            }
        }

        private suspend fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T = suspendCancellableCoroutine { continuation ->
            if (dead.get() || closed.get()) {
                continuation.resumeWith(Result.failure(LocalAiException(if (dead.get()) LocalAiFailure.SERVICE_DIED else LocalAiFailure.IPC)))
                return@suspendCancellableCoroutine
            }
            pending[continuation] = Unit
            continuation.invokeOnCancellation { pending.remove(continuation); cancel() }
            worker.execute {
                val request = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    if (!continuation.isActive) return@execute
                    val binder = remote.get() ?: throw LocalAiException(LocalAiFailure.SERVICE_DIED)
                    request.writeInterfaceToken(LocalInferenceService.DESCRIPTOR); request.writeString(id); write(request)
                    LocalIpcPolicy.checkTransaction(request.dataSize())
                    if (!binder.transact(code, request, reply, 0)) throw LocalAiException(LocalAiFailure.IPC)
                    LocalIpcPolicy.checkTransaction(reply.dataSize())
                    reply.readException()
                    val reason = reply.readInt()
                    if (reason != 0) throw LocalAiException(LocalAiFailure.entries.getOrNull(reason - 1) ?: LocalAiFailure.IPC)
                    val result = read(reply)
                    if (reply.dataAvail() != 0) throw LocalAiException(LocalAiFailure.IPC)
                    if (pending.remove(continuation) != null && continuation.isActive) continuation.resume(result)
                } catch (_: DeadObjectException) { failDead() }
                catch (failure: Throwable) {
                    val safe = if (failure is LocalAiException) failure else LocalAiException(LocalAiFailure.IPC)
                    if (pending.remove(continuation) != null && continuation.isActive) continuation.resumeWith(Result.failure(safe))
                } finally { request.recycle(); reply.recycle() }
            }
        }

        private fun failDead() {
            dead.set(true)
            connected.completeExceptionally(LocalAiException(LocalAiFailure.SERVICE_DIED))
            pending.keys.toList().forEach { continuation ->
                if (pending.remove(continuation) != null && continuation.isActive) continuation.resumeWith(Result.failure(LocalAiException(LocalAiFailure.SERVICE_DIED)))
            }
        }

        /** One-way cancellation never waits behind a decode on the single request executor. */
        override fun cancel() = oneWay(LocalInferenceService.CANCEL)
        private fun oneWay(code: Int) {
            val binder = remote.get() ?: return
            if (dead.get()) return
            val request = Parcel.obtain()
            try {
                request.writeInterfaceToken(LocalInferenceService.DESCRIPTOR); request.writeString(id)
                binder.transact(code, request, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) { }
            finally { request.recycle() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            oneWay(LocalInferenceService.CLOSE)
            pending.keys.toList().forEach { it.cancel(CancellationException("Local inference session closed")) }
            remote.getAndSet(null)?.let { binder -> runCatching { binder.unlinkToDeath(death, 0) } }
            if (bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) }
            worker.shutdown()
        }
    }
}
