package com.pocketagent.runtime

import com.pocketagent.nativebridge.RuntimeGenerationConfig
import com.pocketagent.nativebridge.RuntimeSamplingConfig

internal fun PerformanceRuntimeConfig.toRuntimeGenerationConfig(): RuntimeGenerationConfig {
    return RuntimeGenerationConfig(
        nThreads = nThreads,
        nThreadsBatch = nThreadsBatch,
        nBatch = nBatch,
        nUbatch = nUbatch,
        nCtx = nCtx,
        gpuEnabled = gpuEnabled,
        gpuLayers = gpuLayers,
        flashAttnMode = flashAttnMode,
        kvCachePreset = kvCachePreset,
        sampling = RuntimeSamplingConfig(
            temperature = temperature,
            topK = topK,
            topP = topP,
            minP = minP,
            typicalP = typicalP,
            repeatLastN = repeatLastN,
            repeatPenalty = repeatPenalty,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            mirostat = mirostat,
            mirostatTau = mirostatTau,
            mirostatEta = mirostatEta,
            xtcThreshold = xtcThreshold,
            xtcProbability = xtcProbability,
            seed = seed,
        ),
        speculativeEnabled = speculativeEnabled,
        speculativeDraftModelId = speculativeDraftModelId,
        speculativeMaxDraftTokens = speculativeMaxDraftTokens,
        speculativeMinDraftTokens = speculativeMinDraftTokens,
        speculativeDraftGpuLayers = speculativeDraftGpuLayers,
        useMmap = useMmap,
        useMlock = useMlock,
        nKeep = nKeep,
    )
}
