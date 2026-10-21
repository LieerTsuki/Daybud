package com.example.lifemanager.data.diary.ai.local

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val sourceRepo: String,
    val sourceRevision: String,
    val sourceFile: String,
    val expectedBytes: Long,
    val sha256: String,
    val quantization: String = "Q8_0",
    val architecture: String = "qwen3",
    val license: String = "Apache-2.0",
    val contextTokens: Int = 4096,
    val estimatedWorkingBytes: Long = 1536L * 1024 * 1024,
    val artifactUrl: String? = null,
    val availableForDownload: Boolean = true,
    val provenance: String = "官方GGUF",
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceRevision.matches(Regex("[0-9a-f]{40}")))
        require(sourceRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(sourceFile.matches(Regex("[A-Za-z0-9_.-]+\\.gguf")))
        require(expectedBytes in 32..8L * 1024 * 1024 * 1024)
        require(contextTokens in 256..4096 && estimatedWorkingBytes > 0)
        require(architecture == "qwen3" && quantization in setOf("Q8_0", "Q4_K_M", "Q4_0"))
        require(artifactUrl == null || artifactUrl.startsWith("https://github.com/LieerTsuki/Daybud/releases/download/"))
    }
    val downloadUrl: String
        get() = artifactUrl ?: "https://huggingface.co/$sourceRepo/resolve/$sourceRevision/$sourceFile"
}

object LocalModelCatalog {
    /** Pinned release artifacts; APK and model assets must be published together. */
    val models: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "qwen3-0.6b-q4km-b5501", displayName = "Qwen3 0.6B · Q4_K_M（二次量化）",
            sourceRepo = "Qwen/Qwen3-0.6B-GGUF", sourceRevision = "23749fefcc72300e3a2ad315e1317431b06b590a",
            sourceFile = "Qwen3-0.6B-Q8_0.gguf", expectedBytes = 396704416,
            sha256 = "15e434d8ddf0af38fe3f7003af6ce6eac4edada6df34324aa45979f9cc79f02e", quantization = "Q4_K_M",
            artifactUrl = "https://github.com/LieerTsuki/Daybud/releases/download/v1.3.0/qwen3-0.6b-q4_k_m-b5501.gguf",
            availableForDownload = true,
            provenance = "日子芽使用llama.cpp b5501 --allow-requantize，将官方Q8_0二次量化为Q4_K_M；非Qwen官方Q4产物",
        ),
        LocalModelSpec(
            id = "qwen3-1.7b-q4km-b5501", displayName = "Qwen3 1.7B · Q4_K_M（二次量化）",
            sourceRepo = "Qwen/Qwen3-1.7B-GGUF", sourceRevision = "90862c4b9d2787eaed51d12237eafdfe7c5f6077",
            sourceFile = "Qwen3-1.7B-Q8_0.gguf", expectedBytes = 1107408544,
            sha256 = "54c0f1203a724e9f33e76916beab3bdfaffef56cf7b42a93b1bc21319fc0bf97", quantization = "Q4_K_M",
            estimatedWorkingBytes = 2L * 1024 * 1024 * 1024,
            artifactUrl = "https://github.com/LieerTsuki/Daybud/releases/download/v1.3.0/qwen3-1.7b-q4_k_m-b5501.gguf",
            availableForDownload = true,
            provenance = "日子芽使用llama.cpp b5501 --allow-requantize，将官方Q8_0二次量化为Q4_K_M；非Qwen官方Q4产物",
        ),
    )
}
