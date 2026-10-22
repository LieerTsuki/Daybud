package com.example.lifemanager.data.diary.ai

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCloudRetirementTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun removesOnlyDedicatedCloudFilesAndAliasWithoutParsingDamagedCiphertext() {
        val root = temporary.newFolder()
        val cloud = File(root, "diary-ai").apply { mkdirs() }
        val targets = listOf("service.aesgcm", "service.aesgcm.bak", "service.aesgcm.new", "consent-jobs.json", "consent-jobs.json.bak", "consent-jobs.json.new")
        targets.forEach { File(cloud, it).writeBytes(byteArrayOf(-1, 0, -3)) }
        val retained = listOf(File(cloud, "unrelated"), File(root, "diary-ai-mode/mode.json"), File(root, "diary-models/model.gguf"), File(root, "diary.db"))
        retained.forEach { it.parentFile!!.mkdirs(); it.writeText("preserve") }
        val aliases = mutableListOf<String>()
        assertTrue(LegacyCloudRetirement(cloud) { aliases += it }.retire())
        assertEquals(listOf("lifemanager.diary.ai.v1"), aliases)
        targets.forEach { assertFalse(File(cloud, it).exists()) }
        retained.forEach { assertEquals("preserve", it.readText()) }
    }
    @Test fun unavailableKeyStoreDoesNotPreventFileRemovalAndCanRetry() {
        val cloud = temporary.newFolder()
        File(cloud, "service.aesgcm").writeText("damaged")
        File(cloud, "consent-jobs.json").writeText("damaged")
        assertFalse(LegacyCloudRetirement(cloud) { error("key store unavailable") }.retire())
        assertFalse(File(cloud, "service.aesgcm").exists())
        assertFalse(File(cloud, "consent-jobs.json").exists())
        assertTrue(LegacyCloudRetirement(cloud) { }.retire())
    }
}
