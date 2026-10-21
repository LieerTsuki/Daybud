package com.example.lifemanager.data.diary.ai.local

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class AndroidLocalInferenceClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun lease(): LocalModelLease {
        val file = temporary.newFile().apply { writeText("model".repeat(10)) }
        val spec = LocalModelSpec("test", "测试", "org/repo", "a".repeat(40), "model.gguf", file.length(), "b".repeat(64))
        return LocalModelLease(ReadyLocalModel(spec, file), ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {}
    }
    private class Remote : IBinder by Binder() {
        val deaths = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val ids = mutableListOf<String>()
        var dieOnCount = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { deaths += recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = deaths.remove(recipient)
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.setDataPosition(0); data.enforceInterface(LocalInferenceService.DESCRIPTOR)
            ids += data.readString()!!
            if (code == LocalInferenceService.COUNT && dieOnCount) {
                deaths.forEach { it.binderDied() }; throw DeadObjectException()
            }
            reply?.apply {
                writeNoException(); writeInt(0)
                if (code == LocalInferenceService.COUNT) writeInt(18)
                setDataPosition(0)
            }
            return true
        }
    }
    private class BoundContext(val remote: Remote, val accepted: Boolean = true, val responds: Boolean = true) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (accepted && responds) connection.onServiceConnected(ComponentName(this, LocalInferenceService::class.java), remote)
            return accepted
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    @Test fun deathDuringRequestFailsCurrentWorkAndReleasesBinding() = runBlocking {
        val remote = Remote(); val context = BoundContext(remote)
        val session = lease().use { AndroidLocalInferenceClient(context).open(it) }
        remote.dieOnCount = true
        try { session.countTokens("system", "user"); fail("Dead process must terminate the current request") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.SERVICE_DIED, error.code) }
        finally { session.close() }
        assertEquals(1, context.unbinds)
    }

    @Test fun failedBindDoesNotWaitForAResultThatCannotArrive() = runBlocking {
        val context = BoundContext(Remote(), accepted = false)
        try { lease().use { AndroidLocalInferenceClient(context).open(it) }; fail("Rejected binding cannot produce a session") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.LOAD, error.code) }
    }

    @Test fun acceptedBindingWithoutConnectionReportsLoadFailureAfterBoundedWait() = runTest {
        val context = BoundContext(Remote(), responds = false)
        try { lease().use { AndroidLocalInferenceClient(context).open(it) }; fail("Binding cannot wait indefinitely") }
        catch (error: LocalAiException) { assertEquals(LocalAiFailure.LOAD, error.code) }
        assertEquals(1, context.unbinds)
    }

    @Test fun separateSessionsUseDistinctIdsAndCloseTheirOwnBinding() = runBlocking {
        val remote = Remote(); val context = BoundContext(remote)
        lease().use { model ->
            AndroidLocalInferenceClient(context).open(model).use { assertEquals(18, it.countTokens("s", "u")) }
            AndroidLocalInferenceClient(context).open(model).use { assertEquals(18, it.countTokens("s", "u")) }
        }
        assertEquals(2, remote.ids.distinct().size)
        assertEquals(2, context.unbinds)
    }
}
