package com.example.lifemanager.data.diary.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.domain.diary.ai.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic credentials only; this exercises the device's real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class AiPrivateStoreDeviceTest {
    @Test fun ciphertextAndUnknownJobSurviveReopenButConfigurationChangePausesConsent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.noBackupFilesDir, "ai-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        try {
            val store = AiPrivateStore(context)
            val synthetic = "synthetic-test-secret-${UUID.randomUUID()}"
            val config = store.saveConfig("https://test.example/v1", "synthetic-model", synthetic)
            val day = LocalDate.of(2026, 10, 19)
            store.setConsent(AiAutoConsent(AiPeriodKind.WEEK, day, config.revision, config.origin, true))
            val job = AiAutoJob(AiPeriod(AiPeriodKind.WEEK, day, day.plusDays(6)), config.revision, 3, AiJobState.SENT_UNKNOWN, 1)
            store.putJob(job)
            val reopened = AiPrivateStore(context)
            assertEquals("synthetic-model", reopened.config()!!.model)
            assertEquals(AiJobState.SENT_UNKNOWN, reopened.jobs().single().state)
            assertTrue(reopened.consents().single().enabled)
            assertFalse(config.toString().contains(synthetic))
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse("Secret appeared outside encrypted storage", file.readBytes().toString(Charsets.ISO_8859_1).contains(synthetic))
            }
            reopened.saveConfig("https://other.example/v1", "synthetic-model", synthetic)
            assertFalse(reopened.consents().single().enabled)
            assertEquals(AiJobState.SENT_UNKNOWN, reopened.jobs().single().state)
        } finally {
            check(directory.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }
}
