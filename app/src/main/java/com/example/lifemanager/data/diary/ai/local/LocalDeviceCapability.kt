package com.example.lifemanager.data.diary.ai.local

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import android.os.StatFs

data class LocalDeviceSnapshot(
    val supportedAbis: List<String>,
    val is64BitProcess: Boolean,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
    val availableStorageBytes: Long,
)

enum class LocalCapabilityReason { UNSUPPORTED_ABI, PROCESS_32_BIT, SYSTEM_LOW_MEMORY, INSUFFICIENT_MEMORY, INSUFFICIENT_STORAGE }

data class LocalCapabilityResult(
    val allowed: Boolean,
    val reasons: List<LocalCapabilityReason>,
    val requiredMemoryBytes: Long,
    val requiredStorageBytes: Long,
)

object LocalDeviceCapability {
    fun snapshot(context: Context): LocalDeviceSnapshot {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return LocalDeviceSnapshot(Build.SUPPORTED_ABIS.toList(), Process.is64Bit(), memory.availMem,
            memory.totalMem, memory.lowMemory, memory.threshold, StatFs(context.noBackupFilesDir.absolutePath).availableBytes)
    }
    fun evaluate(
        snapshot: LocalDeviceSnapshot,
        model: LocalModelSpec,
        forImport: Boolean = false,
        nativeAbis: Set<String> = setOf("arm64-v8a", "x86_64"),
    ): LocalCapabilityResult {
        val reserve = 64L * 1024 * 1024
        val memory = if (forImport) 0 else saturatedAdd(
            maxOf(model.estimatedWorkingBytes, saturatedAdd(model.expectedBytes, 512L * 1024 * 1024)),
            maxOf(0L, snapshot.thresholdBytes),
        )
        val storage = if (forImport) saturatedAdd(model.expectedBytes, reserve) else reserve
        val reasons = buildList {
            if (!snapshot.is64BitProcess) add(LocalCapabilityReason.PROCESS_32_BIT)
            if (snapshot.supportedAbis.none { it in nativeAbis && it in setOf("arm64-v8a", "x86_64") }) {
                add(LocalCapabilityReason.UNSUPPORTED_ABI)
            }
            if (!forImport && snapshot.lowMemory) add(LocalCapabilityReason.SYSTEM_LOW_MEMORY)
            if (!forImport && snapshot.availableMemoryBytes < memory) add(LocalCapabilityReason.INSUFFICIENT_MEMORY)
            if (snapshot.availableStorageBytes < storage) add(LocalCapabilityReason.INSUFFICIENT_STORAGE)
        }
        return LocalCapabilityResult(reasons.isEmpty(), reasons, memory, storage)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
