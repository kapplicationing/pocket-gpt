package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.DeviceState
import com.pocketagent.nativebridge.FlashAttnMode
import com.pocketagent.nativebridge.KvCachePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceProfilesTest {
    @Test
    fun `battery profile always resolves to cpu-only even when gpu is requested`() {
        val battery = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BATTERY,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 24,
        )

        assertEquals(false, battery.gpuEnabled)
        assertEquals(0, battery.gpuLayers)
        assertEquals(0, battery.speculativeDraftGpuLayers)
    }

    @Test
    fun `gpu presets use gpu-safe batch cap while cpu-only behavior remains unchanged`() {
        val balancedGpu = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )
        val fastGpu = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )
        val balancedCpu = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = false,
        )
        val fastCpu = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = false,
        )

        assertEquals(256, balancedGpu.nBatch)
        assertEquals(256, balancedGpu.nUbatch)
        assertEquals(1, balancedGpu.speculativeDraftGpuLayers)
        assertEquals(0.05f, balancedGpu.minP)
        assertEquals(64, balancedGpu.repeatLastN)
        assertEquals(1.05f, balancedGpu.repeatPenalty)
        assertEquals(KvCachePreset.BALANCED, balancedGpu.kvCachePreset)
        assertEquals(true, balancedGpu.useMmap)
        assertEquals(false, balancedGpu.useMlock)
        assertEquals(128, balancedGpu.nKeep)
        assertEquals(9, balancedGpu.nThreadsBatch)
        assertEquals(FlashAttnMode.AUTO, balancedGpu.flashAttnMode)
        assertEquals(256, fastGpu.nBatch)
        assertEquals(256, fastGpu.nUbatch)
        assertEquals(2, fastGpu.speculativeDraftGpuLayers)
        assertEquals(0.05f, fastGpu.minP)
        assertEquals(64, fastGpu.repeatLastN)
        assertEquals(1.05f, fastGpu.repeatPenalty)
        assertEquals(KvCachePreset.AGGRESSIVE, fastGpu.kvCachePreset)
        assertEquals(true, fastGpu.useMmap)
        assertEquals(false, fastGpu.useMlock)
        assertEquals(256, fastGpu.nKeep)
        assertEquals(8192, fastGpu.nCtx)
        assertEquals(12, fastGpu.nThreadsBatch)
        assertEquals(FlashAttnMode.AUTO, fastGpu.flashAttnMode)
        assertEquals(512, balancedCpu.nBatch)
        assertEquals(512, balancedCpu.nUbatch)
        assertEquals(6, balancedCpu.nThreadsBatch)
        assertFalse(balancedCpu.useMmap)
        assertEquals(FlashAttnMode.AUTO, balancedCpu.flashAttnMode)
        assertEquals(768, fastCpu.nBatch)
        assertEquals(768, fastCpu.nUbatch)
        assertEquals(8, fastCpu.nThreadsBatch)
        assertFalse(fastCpu.useMmap)
        assertEquals(FlashAttnMode.AUTO, fastCpu.flashAttnMode)
    }

    @Test
    fun `thermal overrides keep gpu batches within safe cap`() {
        val fastGpu = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 24,
        )
        val moderate = fastGpu.withThermalAdaptiveOverrides(
            DeviceState(batteryPercent = 30, thermalLevel = 5, ramClassGb = 8),
        )
        val severe = fastGpu.withThermalAdaptiveOverrides(
            DeviceState(batteryPercent = 15, thermalLevel = 7, ramClassGb = 8),
        )

        assertTrue(moderate.nBatch <= GPU_SAFE_BATCH_CAP)
        assertTrue(moderate.nUbatch <= GPU_SAFE_BATCH_CAP)
        assertTrue(severe.nBatch <= GPU_SAFE_BATCH_CAP)
        assertTrue(severe.nUbatch <= GPU_SAFE_BATCH_CAP)
    }

    @Test
    fun `fast profile uses qwen3 0_6b as speculative draft model`() {
        val fast = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        assertEquals(ModelCatalog.QWEN3_0_6B_Q4_K_M, fast.speculativeDraftModelId)
        assertEquals(true, fast.speculativeEnabled)
    }

    @Test
    fun `battery profile disables speculative decoding`() {
        val battery = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BATTERY,
            availableCpuThreads = 4,
            gpuEnabled = false,
        )

        assertFalse(battery.speculativeEnabled)
    }

    @Test
    fun `fast profile context window is 8192`() {
        val fast = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        assertEquals(8192, fast.nCtx)
    }

    @Test
    fun `default residency policy allows low pressure devices to keep models resident for fifteen minutes`() {
        assertEquals(DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS, ModelResidencyPolicy().idleUnloadTtlMs)
    }

    @Test
    fun `profiles use only retained kv cache presets`() {
        val profiles = RuntimePerformanceProfile.entries
        profiles.forEach { profile ->
            val config = PerformanceRuntimeConfig.forProfile(
                profile = profile,
                availableCpuThreads = 4,
                gpuEnabled = false,
            )
            assertTrue(
                config.kvCachePreset in KvCachePreset.entries,
                "Profile ${profile.name} should use a retained KV cache preset",
            )
        }
    }
}
