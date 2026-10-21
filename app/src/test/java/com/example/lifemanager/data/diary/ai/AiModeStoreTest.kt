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
    private class Cloud : AiStateStore {
        var value: AiServiceConfig? = AiServiceConfig.checked("https://cloud.example/v1", "cloud-model", "private-key", 1)
        var paused = 0
        var consents = listOf(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.now(), 1, "https://cloud.example", true))
        override suspend fun config() = value
        override suspend fun saveConfig(address: String, model: String, key: String): AiServiceConfig =
            AiServiceConfig.checked(address, model, key, (value?.revision ?: 0) + 1).also { value = it }
        override suspend fun consents() = consents
        override suspend fun setConsent(consent: AiAutoConsent) { consents = listOf(consent) }
        override suspend fun pauseAutomatic() { paused++; consents = consents.map { it.copy(enabled = false) } }
        override suspend fun jobs() = emptyList<AiAutoJob>()
        override suspend fun putJob(job: AiAutoJob) = Unit
    }
    @Test fun localSelectionNeedsNoCloudKeyAndSurvivesRecreation() = runTest {
        val cloud = Cloud().apply { value = null }
        val folder = temporary.newFolder()
        val store = AiModeStore(cloud, folder)
        store.selectLocal("qwen-small", digest)
        val recreated = AiModeStore(cloud, folder)
        assertEquals(AiExecutionMode.LOCAL, recreated.mode())
        val config = recreated.config()!!
        assertEquals(AiExecutionMode.LOCAL, config.mode)
        assertEquals("qwen-small", config.model)
        assertEquals(digest, config.localModelSha256)
        assertTrue(config.apiKey.isEmpty())
    }
    @Test fun switchingPausesAuthorizationAndInvalidatesOldCloudPreviewWithoutErasingSecret() = runTest {
        val cloud = Cloud(); val store = AiModeStore(cloud, temporary.newFolder())
        val original = store.config()!!
        store.selectLocal("qwen-small", digest)
        assertTrue(cloud.paused > 0)
        assertTrue(cloud.consents.none { it.enabled })
        val restored = store.selectCloud()!!
        assertEquals(AiExecutionMode.CLOUD, restored.mode)
        assertEquals("private-key", restored.apiKey)
        assertEquals(original.endpoint, restored.endpoint)
        assertNotEquals(original.revision, restored.revision)
        assertTrue(cloud.consents.none { it.enabled })
    }
    @Test fun changingModelDigestInvalidatesOldSelectionAndNeverPersistsCloudKeyInModeFile() = runTest {
        val folder = temporary.newFolder(); val store = AiModeStore(Cloud(), folder)
        val first = store.selectLocal("qwen-small", digest)
        val second = store.selectLocal("qwen-small", "b".repeat(64))
        assertNotEquals(first.revision, second.revision)
        assertNotEquals(first.origin, second.origin)
        folder.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains("private-key")) }
    }
    @Test fun offlineModeRejectsAutomaticConsentEvenIfCallerTriesDirectly() = runTest {
        val cloud = Cloud(); val store = AiModeStore(cloud, temporary.newFolder())
        store.selectLocal("qwen-small", digest)
        try {
            store.setConsent(AiAutoConsent(AiPeriodKind.WEEK, LocalDate.now(), 1, "https://cloud.example", true))
            fail("cloud automatic consent accepted in offline mode")
        } catch (_: IllegalStateException) { }
        assertTrue(cloud.consents.none { it.enabled })
    }
    @Test fun unsafeLocalModelIdentityIsRejectedBeforeModeChanges() = runTest {
        val store = AiModeStore(Cloud(), temporary.newFolder())
        for ((id, hash) in listOf("../model" to digest, "qwen" to "not-a-sha")) {
            try { store.selectLocal(id, hash); fail("invalid local identity accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(AiExecutionMode.CLOUD, store.mode())
    }
    @Test fun damagedModeRecordDoesNotSilentlyFallBackToCloud() = runTest {
        val folder = temporary.newFolder(); val cloud = Cloud()
        AiModeStore(cloud, folder).selectLocal("qwen-small", digest)
        File(folder, "mode.json").writeText("broken")
        try { AiModeStore(cloud, folder).config(); fail("corrupted mode fell back to cloud") }
        catch (_: IllegalStateException) { }
    }
}
