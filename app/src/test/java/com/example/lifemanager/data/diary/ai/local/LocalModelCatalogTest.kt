package com.example.lifemanager.data.diary.ai.local

import org.junit.Assert.*
import org.junit.Test

class LocalModelCatalogTest {
    @Test fun downloadableArtifactsUsePinnedReleaseNamesAndContentDigests() {
        val models = LocalModelCatalog.models.filter { it.availableForDownload }
        assertEquals(2, models.size)
        models.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://github.com/LieerTsuki/Daybud/releases/download/v1.3.0/"))
            assertTrue(model.sourceRevision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertFalse(model.downloadUrl.contains("/main/"))
            assertEquals("Q4_K_M", model.quantization)
            assertTrue(model.expectedBytes >= 396704416)
            assertEquals(4096, model.contextTokens)
        }
    }

    @Test fun smallestDerivedArtifactHasExactDigestAndExplicitProvenance() {
        val small = LocalModelCatalog.models.first()
        assertEquals("qwen3-0.6b-q4km-b5501", small.id)
        assertEquals("Q4_K_M", small.quantization)
        assertEquals(396704416L, small.expectedBytes)
        assertEquals("15e434d8ddf0af38fe3f7003af6ce6eac4edada6df34324aa45979f9cc79f02e", small.sha256)
        assertTrue(small.availableForDownload)
        assertNotEquals("官方GGUF", small.provenance)
    }

    @Test fun largerDerivedArtifactKeepsTheSmallModelAsTheDefault() {
        val larger = LocalModelCatalog.models.single { it.id == "qwen3-1.7b-q4km-b5501" }
        assertEquals(1107408544L, larger.expectedBytes)
        assertEquals("54c0f1203a724e9f33e76916beab3bdfaffef56cf7b42a93b1bc21319fc0bf97", larger.sha256)
        assertEquals("Q4_K_M", larger.quantization)
        assertEquals(2L * 1024 * 1024 * 1024, larger.estimatedWorkingBytes)
        assertEquals("https://github.com/LieerTsuki/Daybud/releases/download/v1.3.0/qwen3-1.7b-q4_k_m-b5501.gguf", larger.downloadUrl)
        assertTrue(larger.availableForDownload)
        assertEquals("qwen3-0.6b-q4km-b5501", LocalModelCatalog.models.first().id)
        assertNotEquals("官方GGUF", larger.provenance)
    }
}
