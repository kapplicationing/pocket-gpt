package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.FlashAttnMode
import com.pocketagent.nativebridge.KvCachePreset

enum class RuntimePerformanceProfile {
    BATTERY,
    BALANCED,
    FAST,
}

internal const val GPU_SAFE_BATCH_CAP: Int = 256

data class PerformanceRuntimeConfig(
    val profile: RuntimePerformanceProfile = RuntimePerformanceProfile.BALANCED,
    val requestTimeoutMs: Long,
    val maxTokensDefault: Int,
    val nThreads: Int,
    val nThreadsBatch: Int,
    val nBatch: Int,
    val nUbatch: Int,
    val nCtx: Int,
    val gpuEnabled: Boolean,
    val gpuLayers: Int,
    val flashAttnMode: FlashAttnMode,
    val kvCachePreset: KvCachePreset,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val typicalP: Float,
    val repeatLastN: Int,
    val repeatPenalty: Float,
    val frequencyPenalty: Float,
    val presencePenalty: Float,
    val mirostat: Int,
    val mirostatTau: Float,
    val mirostatEta: Float,
    val xtcThreshold: Float,
    val xtcProbability: Float,
    val seed: Int,
    val speculativeEnabled: Boolean,
    val speculativeDraftModelId: String?,
    val speculativeMaxDraftTokens: Int,
    val speculativeMinDraftTokens: Int,
    val speculativeDraftGpuLayers: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val nKeep: Int,
) {
    companion object {
        private const val MIN_THREADS = 1
        private const val MAX_THREADS = 16
        private const val MIN_BATCH = 32
        private const val MAX_BATCH = 2048

        fun default(): PerformanceRuntimeConfig =
            forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 4,
                gpuEnabled = false,
            )

        fun forProfile(
            profile: RuntimePerformanceProfile,
            availableCpuThreads: Int,
            gpuEnabled: Boolean,
            gpuLayers: Int = DEFAULT_GPU_LAYERS,
        ): PerformanceRuntimeConfig {
            val cpu = availableCpuThreads.coerceAtLeast(1)
            val profileAllowsGpu = profile != RuntimePerformanceProfile.BATTERY
            val effectiveGpuEnabled = gpuEnabled && profileAllowsGpu
            val effectiveGpuLayers = if (effectiveGpuEnabled) gpuLayers.coerceAtLeast(0) else 0
            val batchCap = if (effectiveGpuEnabled && effectiveGpuLayers > 0) GPU_SAFE_BATCH_CAP else MAX_BATCH
            val profilePreset = when (profile) {
                RuntimePerformanceProfile.BATTERY -> Preset(
                    timeoutMs = 900_000L,
                    maxTokensDefault = 128,
                    threads = (cpu / 2).coerceAtLeast(2).coerceAtMost(4),
                    batch = 256,
                    ubatch = 256,
                    nCtx = 1024,
                )

                RuntimePerformanceProfile.BALANCED -> Preset(
                    timeoutMs = 600_000L,
                    maxTokensDefault = 256,
                    threads = cpu.coerceAtMost(6),
                    batch = 512,
                    ubatch = 512,
                    nCtx = 2048,
                )

                RuntimePerformanceProfile.FAST -> Preset(
                    timeoutMs = 600_000L,
                    maxTokensDefault = 384,
                    threads = cpu.coerceAtMost(8),
                    batch = 768,
                    ubatch = 768,
                    nCtx = 8192,
                )
            }

            return PerformanceRuntimeConfig(
                profile = profile,
                requestTimeoutMs = profilePreset.timeoutMs,
                maxTokensDefault = profilePreset.maxTokensDefault,
                nThreads = profilePreset.threads.coerceIn(MIN_THREADS, MAX_THREADS),
                // CPU fallback is often bandwidth bound on phones, so avoid
                // oversubscribing prompt-eval threads there. GPU-backed runs
                // still benefit from a slightly wider prefill worker pool.
                nThreadsBatch = if (effectiveGpuEnabled) {
                    (profilePreset.threads * 3 / 2).coerceIn(MIN_THREADS, MAX_THREADS)
                } else {
                    profilePreset.threads.coerceIn(MIN_THREADS, MAX_THREADS)
                },
                nBatch = profilePreset.batch.coerceIn(MIN_BATCH, batchCap),
                nUbatch = profilePreset.ubatch.coerceIn(MIN_BATCH, batchCap),
                nCtx = profilePreset.nCtx,
                gpuEnabled = effectiveGpuEnabled,
                gpuLayers = effectiveGpuLayers,
                flashAttnMode = if (profile == RuntimePerformanceProfile.BATTERY) {
                    FlashAttnMode.OFF
                } else {
                    FlashAttnMode.AUTO
                },
                kvCachePreset = when (profile) {
                    RuntimePerformanceProfile.BATTERY -> KvCachePreset.SAFE
                    RuntimePerformanceProfile.BALANCED -> KvCachePreset.BALANCED
                    RuntimePerformanceProfile.FAST -> KvCachePreset.AGGRESSIVE
                },
                temperature = 0.6f,
                topK = if (profile == RuntimePerformanceProfile.BATTERY) 24 else 40,
                topP = if (profile == RuntimePerformanceProfile.BATTERY) 0.92f else 0.95f,
                minP = 0.05f,
                typicalP = 1.0f,
                repeatLastN = 64,
                repeatPenalty = if (profile == RuntimePerformanceProfile.BATTERY) 1.0f else 1.05f,
                frequencyPenalty = 0.0f,
                presencePenalty = 0.0f,
                mirostat = 0,
                mirostatTau = 5.0f,
                mirostatEta = 0.1f,
                xtcThreshold = 0.1f,
                xtcProbability = 0.0f,
                seed = -1,
                speculativeEnabled = profile != RuntimePerformanceProfile.BATTERY,
                speculativeDraftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                speculativeMaxDraftTokens = if (profile == RuntimePerformanceProfile.FAST) 8 else 6,
                speculativeMinDraftTokens = 2,
                speculativeDraftGpuLayers = defaultDraftGpuLayers(
                    profile = profile,
                    gpuEnabled = effectiveGpuEnabled,
                    gpuLayers = effectiveGpuLayers,
                ),
                useMmap = effectiveGpuEnabled,
                useMlock = false,
                nKeep = if (profile == RuntimePerformanceProfile.FAST) 256 else 128,
            )
        }

        private fun defaultDraftGpuLayers(
            profile: RuntimePerformanceProfile,
            gpuEnabled: Boolean,
            gpuLayers: Int,
        ): Int {
            if (!gpuEnabled || profile == RuntimePerformanceProfile.BATTERY || gpuLayers <= 0) {
                return 0
            }
            return when (profile) {
                RuntimePerformanceProfile.BATTERY -> 0
                RuntimePerformanceProfile.BALANCED -> (gpuLayers / 16).coerceIn(0, 2)
                RuntimePerformanceProfile.FAST -> (gpuLayers / 8).coerceIn(0, 4)
            }
        }

        // Conservative starting point for GPU layers. The GPU probe will
        // determine the actual safe maximum and RuntimeTuningStore will clamp
        // to that value. 16 is a safer default for OpenCL (Adreno max allocation
        // is typically 1024 MB) while still providing meaningful acceleration.
        private const val DEFAULT_GPU_LAYERS: Int = 16
    }

    private data class Preset(
        val timeoutMs: Long,
        val maxTokensDefault: Int,
        val threads: Int,
        val batch: Int,
        val ubatch: Int,
        val nCtx: Int,
    )

    fun withThermalAdaptiveOverrides(deviceState: DeviceState): PerformanceRuntimeConfig {
        val severePressure = deviceState.thermalLevel >= 7 || deviceState.batteryPercent < 20
        val moderatePressure = deviceState.thermalLevel >= 5 || deviceState.batteryPercent < 35
        val batchCap = if (gpuEnabled && gpuLayers > 0) GPU_SAFE_BATCH_CAP else MAX_BATCH
        return when {
            severePressure -> copy(
                nThreads = (nThreads / 2).coerceAtLeast(2),
                nThreadsBatch = (nThreadsBatch / 2).coerceAtLeast(2),
                nBatch = (nBatch / 2).coerceAtLeast(256).coerceAtMost(batchCap),
                nUbatch = (nUbatch / 2).coerceAtLeast(256).coerceAtMost(batchCap),
                nCtx = nCtx.coerceAtMost(1024),
                gpuLayers = (gpuLayers / 2).coerceAtLeast(0),
                speculativeEnabled = false,
                speculativeDraftGpuLayers = 0,
            )
            moderatePressure -> copy(
                nThreads = (nThreads * 3 / 4).coerceAtLeast(2),
                nThreadsBatch = (nThreadsBatch * 3 / 4).coerceAtLeast(2),
                nBatch = (nBatch * 3 / 4).coerceAtLeast(384).coerceAtMost(batchCap),
                nUbatch = (nUbatch * 3 / 4).coerceAtLeast(384).coerceAtMost(batchCap),
                nCtx = nCtx.coerceAtMost(1536),
                gpuLayers = (gpuLayers * 3 / 4).coerceAtLeast(0),
                speculativeEnabled = speculativeEnabled && deviceState.thermalLevel <= 5,
                speculativeDraftGpuLayers = (speculativeDraftGpuLayers * 3 / 4).coerceAtLeast(0),
            )
            else -> this
        }
    }

    fun withSamplingOverrides(overrides: SamplingOverrides?): PerformanceRuntimeConfig {
        if (overrides == null) return this
        return copy(
            temperature = overrides.temperature ?: temperature,
            topP = overrides.topP ?: topP,
            topK = overrides.topK ?: topK,
            maxTokensDefault = overrides.maxTokens ?: maxTokensDefault,
            repeatPenalty = overrides.repeatPenalty ?: repeatPenalty,
            frequencyPenalty = overrides.frequencyPenalty ?: frequencyPenalty,
            presencePenalty = overrides.presencePenalty ?: presencePenalty,
        )
    }
}

const val DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS: Long = 15 * 60 * 1000L

data class ModelResidencyPolicy(
    val keepLoadedWhileAppForeground: Boolean = true,
    val idleUnloadTtlMs: Long = DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS,
    val warmupOnStartup: Boolean = true,
    val adaptiveIdleTtl: Boolean = true,
)
