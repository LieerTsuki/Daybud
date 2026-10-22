package com.example.lifemanager.data.diary.ai.local

import java.io.Closeable

data class LocalInferenceOutput(val text: String, val promptTokens: Int, val generatedTokens: Int, val reachedEos: Boolean)

interface LocalInferenceSession : Closeable {
    suspend fun countTokens(system: String, user: String): Int
    suspend fun generate(system: String, user: String, grammar: String, maxOutputTokens: Int): LocalInferenceOutput
    fun cancel()
}

// Append only: these ordinals are exchanged by the private Binder protocol.
enum class LocalAiFailure { BUDGET, LOAD, INFERENCE, INCOMPLETE, SERVICE_DIED, IPC, BUSY, TIMEOUT, LOAD_TIMEOUT }
class LocalAiException(val code: LocalAiFailure) : IllegalStateException(when (code) {
    LocalAiFailure.BUDGET -> "所选文字超出手机离线处理预算，请缩小日期范围"
    LocalAiFailure.LOAD -> "离线模型加载失败，请检查模型和可用内存"
    LocalAiFailure.INFERENCE -> "手机离线生成失败，请稍后重试"
    LocalAiFailure.INCOMPLETE -> "离线模型未生成完整回顾，请缩小日期范围后重试"
    LocalAiFailure.SERVICE_DIED -> "离线推理进程已退出，本次未保存回顾"
    LocalAiFailure.IPC -> "离线推理通信失败，本次未保存回顾"
    LocalAiFailure.BUSY -> "离线模型仍在释放资源，请稍后重试"
    LocalAiFailure.TIMEOUT -> "离线回顾生成超时，本次未保存；请缩小日期范围后重试"
    LocalAiFailure.LOAD_TIMEOUT -> "离线模型加载超时，请检查可用内存后重试"
})

enum class LocalAiStage { PREPARING, GENERATING, MERGING }
data class LocalAiProgress(val stage: LocalAiStage, val completedSteps: Int, val totalSteps: Int)

data class LocalAiBudget(
    val contextTokens: Int = 4096,
    val maxOutputTokens: Int = 768,
    val maxLeafChunks: Int = 8,
    val maxTotalTokenWork: Int = 15 * 4096,
)
