package com.example.lifemanager.data.diary.ai.local

import android.content.Context
import com.example.lifemanager.domain.diary.ai.*

class PhoneAiUnavailableException(reasons: List<LocalCapabilityReason>) : IllegalStateException(when {
    reasons.any { it == LocalCapabilityReason.PROCESS_32_BIT || it == LocalCapabilityReason.UNSUPPORTED_ABI } -> "当前设备或应用进程不支持64位离线推理，日记等原有功能仍可使用"
    LocalCapabilityReason.INSUFFICIENT_STORAGE in reasons -> "可用存储空间不足，请释放空间后重试"
    else -> "当前可用内存不足，请关闭其他应用或选择更小模型后重试"
})

class PhoneAiRunnerAdapter(
    private val context: Context,
    private val models: LocalModelStore,
    private val gateway: LocalAiGateway,
) : PhoneAiRunner {
    private suspend fun lease(config: AiServiceConfig): LocalModelLease {
        check(config.mode == AiExecutionMode.LOCAL)
        val model = models.readyModel(config.model) ?: throw LocalModelStoreException(LocalModelFailure.NOT_READY, "离线模型尚未就绪，请先在AI服务中下载或导入")
        val capability = LocalDeviceCapability.evaluate(LocalDeviceCapability.snapshot(context), model.spec)
        if (!capability.allowed) throw PhoneAiUnavailableException(capability.reasons)
        return models.acquire(config.model, config.localModelSha256 ?: throw AiStaleException())
    }
    override suspend fun generate(config: AiServiceConfig, sources: List<AiDiarySource>, beforeStep: suspend () -> Unit, progress: (String) -> Unit): List<AiReviewSection> {
        beforeStep()
        progress("正在校验离线模型")
        return lease(config).use { model ->
            gateway.generate(model, sources, beforeStep) { status ->
                progress(when (status.stage) {
                    LocalAiStage.PREPARING -> "正在加载模型并核对文字预算"
                    LocalAiStage.GENERATING -> "正在手机上生成 ${status.completedSteps}/${status.totalSteps}"
                    LocalAiStage.MERGING -> "正在汇总回顾 ${status.completedSteps}/${status.totalSteps}"
                })
            }
        }
    }
    override suspend fun probe(config: AiServiceConfig) { lease(config).use { gateway.probe(it) } }
}
