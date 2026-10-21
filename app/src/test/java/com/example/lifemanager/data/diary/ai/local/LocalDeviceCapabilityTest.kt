package com.example.lifemanager.data.diary.ai.local

import org.junit.Assert.*
import org.junit.Test

class LocalDeviceCapabilityTest {
    private val gib = 1024L * 1024 * 1024
    private val model = fixtureSpec(tinyGguf()).copy(expectedBytes = gib / 2, estimatedWorkingBytes = gib)
    private fun device(available: Long = 2 * gib, total: Long = 4 * gib, low: Boolean = false,
                       storage: Long = 2 * gib, abis: List<String> = listOf("arm64-v8a"), process64: Boolean = true) =
        LocalDeviceSnapshot(abis, process64, available, total, low, gib / 4, storage)

    @Test fun highTotalRamCannotOverrideLowAvailableMemory() {
        val result = LocalDeviceCapability.evaluate(device(available = gib / 2, total = 12 * gib), model)
        assertFalse(result.allowed)
        assertTrue(LocalCapabilityReason.INSUFFICIENT_MEMORY in result.reasons)
    }

    @Test fun lowMemorySignalBlocksEvenWhenAvailableEstimateLooksLarge() {
        assertTrue(LocalCapabilityReason.SYSTEM_LOW_MEMORY in LocalDeviceCapability.evaluate(device(low = true), model).reasons)
    }

    @Test fun onlyCompiled64BitAbiCanRunTheModel() {
        assertTrue(LocalCapabilityReason.PROCESS_32_BIT in LocalDeviceCapability.evaluate(device(process64 = false), model).reasons)
        assertTrue(LocalCapabilityReason.UNSUPPORTED_ABI in LocalDeviceCapability.evaluate(device(abis = listOf("armeabi-v7a")), model).reasons)
        assertTrue(LocalCapabilityReason.UNSUPPORTED_ABI in LocalDeviceCapability.evaluate(device(), model, nativeAbis = setOf("x86_64")).reasons)
    }

    @Test fun importingRequiresFullNewFilePlusReserveWithoutCountingExistingModelAsFree() {
        val result = LocalDeviceCapability.evaluate(device(storage = model.expectedBytes), model, forImport = true)
        assertFalse(result.allowed)
        assertTrue(LocalCapabilityReason.INSUFFICIENT_STORAGE in result.reasons)
        assertEquals(model.expectedBytes + 64 * 1024 * 1024, result.requiredStorageBytes)
    }

    @Test fun importDoesNotRequireInferenceMemoryAndEnoughLiveResourcesPermitExecution() {
        assertTrue(LocalDeviceCapability.evaluate(device(available = 1), model, forImport = true).allowed)
        assertTrue(LocalDeviceCapability.evaluate(device(total = 3 * gib), model).allowed)
    }

    @Test fun systemThresholdIsReservedAlongsideModelWorkingMemory() {
        val result = LocalDeviceCapability.evaluate(device(available = gib), model)
        assertFalse(result.allowed)
        assertEquals(gib + gib / 4, result.requiredMemoryBytes)
    }
}
