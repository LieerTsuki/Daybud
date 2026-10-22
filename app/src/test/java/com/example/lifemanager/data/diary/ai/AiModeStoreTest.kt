package com.example.lifemanager.data.diary.ai

import com.example.lifemanager.domain.diary.ai.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiModeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val digest = "a".repeat(64)
    @Test fun freshInstallationIsOfflineWithoutReadingLegacySecrets() = runTest {
        val store = AiModeStore(temporary.newFolder())
        assertEquals(AiExecutionMode.LOCAL, store.mode())
        assertNull(store.config())
        assertTrue(store.consents().isEmpty())
        assertTrue(store.jobs().isEmpty())
    }
    @Test fun localSelectionAndRecreationWorkDespiteDamagedLegacyCredentials() = runTest {
        val folder = temporary.newFolder()
        val legacy = File(folder.parentFile, "diary-ai").apply { mkdirs() }
        File(legacy, "service.aesgcm").writeText("broken cipher")
        File(legacy, "consent-jobs.json").writeText("broken queue")
        val selected = AiModeStore(folder).selectLocal("qwen-small", digest)
        val recreated = AiModeStore(folder)
        assertEquals(AiExecutionMode.LOCAL, recreated.mode())
        assertEquals(digest, recreated.config()!!.localModelSha256)
        assertEquals(selected.revision, recreated.config()!!.revision)
        assertEquals("broken cipher", File(legacy, "service.aesgcm").readText())
        assertEquals("broken queue", File(legacy, "consent-jobs.json").readText())
    }
    @Test fun oldCloudModeDoesNotRestoreCloudOrReadCredentials() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"CLOUD","revision":22,"id":"","sha":""}""")
        val store = AiModeStore(folder)
        assertEquals(AiExecutionMode.LOCAL, store.mode())
        assertNull(store.config())
        store.selectLocal("qwen-small", digest)
        assertTrue(store.config()!!.revision > 22)
    }
    @Test fun previousLocalSelectionAndDigestAreRetained() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"LOCAL","revision":22,"id":"qwen-small","sha":"$digest"}""")
        val store = AiModeStore(folder)
        assertEquals("qwen-small", store.config()!!.model)
        assertEquals(digest, store.config()!!.localModelSha256)
        assertEquals(22L, store.config()!!.revision)
    }
    @Test fun damagedModeCanBeRecoveredWithoutAnyCloudFallback() = runTest {
        val folder = temporary.newFolder(); File(folder, "mode.json").writeText("broken")
        val store = AiModeStore(folder)
        assertEquals(AiExecutionMode.LOCAL, store.mode())
        assertNull(store.config())
        store.selectLocal("qwen-small", digest)
        assertEquals(digest, store.config()!!.localModelSha256)
    }
    @Test fun automaticConsentCannotBeEnabledAndCloudConfigurationCannotBeWritten() = runTest {
        val store = AiModeStore(temporary.newFolder())
        try { store.setConsent(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.now(), 1, "https://cloud.example", true)); fail("automatic work accepted") }
        catch (_: IllegalStateException) { }
        try { store.saveConfig("https://cloud.example", "model", "secret"); fail("cloud configuration accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun maintenanceInvalidatesCapturedRevisionWithoutRemovingSelectedModel() = runTest {
        val store = AiModeStore(temporary.newFolder())
        val first = store.selectLocal("qwen-small", digest)
        store.pauseAutomatic()
        val current = store.config()!!
        assertTrue(current.revision > first.revision)
        assertEquals(first.model, current.model)
        assertEquals(first.localModelSha256, current.localModelSha256)
    }
    @Test fun invalidLocalSelectionCannotReplaceTheExistingValidModel() = runTest {
        val store = AiModeStore(temporary.newFolder())
        val first = store.selectLocal("qwen-small", digest)
        for ((id, sha) in listOf("../model" to digest, "qwen-small" to "bad-sha")) {
            try { store.selectLocal(id, sha); fail("invalid local selection accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(first.model, store.config()!!.model)
        assertEquals(first.revision, store.config()!!.revision)
    }
    @Test fun oldInvalidModelIdentityIsNotMigratedAndCanBeSelectedAgain() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"LOCAL","revision":22,"id":"../model","sha":"$digest"}""")
        val store = AiModeStore(folder)
        assertNull(store.config())
        store.selectLocal("qwen-small", digest)
        assertEquals("qwen-small", store.config()!!.model)
    }

    @Test fun exhaustedLocalRevisionCannotPermanentlyBlockSelectionOrMaintenance() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"LOCAL","revision":9223372036854775807,"id":"qwen-small","sha":"$digest"}""")
        val store = AiModeStore(folder)
        assertNull("An exhausted persisted counter must be treated as damaged", store.config())
        store.invalidateRequests()
        store.selectLocal("qwen-small", digest)
        assertEquals("qwen-small", store.config()!!.model)
        assertTrue(store.config()!!.revision in 1 until Long.MAX_VALUE)
    }

    @Test fun exhaustedLegacyCloudRevisionCannotBlockOfflineSelection() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"CLOUD","revision":9223372036854775807,"id":"","sha":""}""")
        val store = AiModeStore(folder)
        assertNull(store.config())
        val selected = store.selectLocal("qwen-small", digest)
        store.invalidateRequests()
        assertEquals(selected.model, store.config()!!.model)
        assertTrue(store.config()!!.revision in 1 until Long.MAX_VALUE)
    }

    @Test fun nearExhaustedCounterNeverPublishesAnUnusableMaxValue() = runTest {
        val folder = temporary.newFolder()
        File(folder, "mode.json").writeText("""{"mode":"LOCAL","revision":9223372036854775806,"id":"qwen-small","sha":"$digest"}""")
        val store = AiModeStore(folder)
        store.selectLocal("other", "b".repeat(64))
        assertEquals("other", store.config()!!.model)
        assertTrue(store.config()!!.revision in 1 until Long.MAX_VALUE)
        store.invalidateRequests()
        assertEquals("other", store.config()!!.model)
    }
}
