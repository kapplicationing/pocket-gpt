package com.pocketagent.android.runtime

import android.os.Bundle
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.GpuExecutionBackend
import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.RuntimeGenerationConfig

internal fun Bundle?.toProbeRequestOrNull(): GpuProbeRequest? {
    val source = this ?: return null
    val modelId = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_ID).orEmpty().trim()
    val modelVersion = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_VERSION).orEmpty().trim()
    val modelPath = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_PATH).orEmpty().trim()
    val ladder = source.getIntegerArrayList(LlamaRuntimeIpc.EXTRA_LAYER_LADDER)
        ?.map { it.toInt() }
        ?.filter { it > 0 }
        ?.distinct()
        .orEmpty()
    val backendProfile = source.getString(LlamaRuntimeIpc.EXTRA_BACKEND_PROFILE)?.trim()?.ifEmpty { null } ?: "auto"
    if (modelId.isBlank() || modelPath.isBlank() || ladder.isEmpty()) {
        return null
    }
    return GpuProbeRequest(
        modelId = modelId,
        modelVersion = modelVersion,
        modelPath = modelPath,
        layerLadder = ladder,
        backendProfile = backendProfile,
    )
}

internal fun RuntimeGenerationConfig.toBundle(): Bundle {
    return Bundle().apply {
        putInt(LlamaRuntimeIpc.EXTRA_THREADS, nThreads)
        putInt(LlamaRuntimeIpc.EXTRA_THREADS_BATCH, nThreadsBatch)
        putInt(LlamaRuntimeIpc.EXTRA_BATCH, nBatch)
        putInt(LlamaRuntimeIpc.EXTRA_UBATCH, nUbatch)
        putInt(LlamaRuntimeIpc.EXTRA_CTX, nCtx)
        putBoolean(LlamaRuntimeIpc.EXTRA_GPU_ENABLED, gpuEnabled)
        putInt(LlamaRuntimeIpc.EXTRA_GPU_LAYERS, gpuLayers)
        putString(LlamaRuntimeIpc.EXTRA_GPU_BACKEND, gpuBackend.name)
        putBoolean(LlamaRuntimeIpc.EXTRA_STRICT_GPU_OFFLOAD, strictGpuOffload)
        putString(LlamaRuntimeIpc.EXTRA_KV_PRESET, kvCachePreset.name)
        putFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TEMPERATURE, sampling.temperature)
        putInt(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_K, sampling.topK)
        putFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_P, sampling.topP)
        putBoolean(LlamaRuntimeIpc.EXTRA_SPECULATIVE_ENABLED, speculativeEnabled)
        putString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_ID, speculativeDraftModelId)
        putString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_PATH, speculativeDraftModelPath)
        putInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MAX, speculativeMaxDraftTokens)
        putInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MIN, speculativeMinDraftTokens)
        putInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_GPU_LAYERS, speculativeDraftGpuLayers)
        putBoolean(LlamaRuntimeIpc.EXTRA_USE_MMAP, useMmap)
        putBoolean(LlamaRuntimeIpc.EXTRA_USE_MLOCK, useMlock)
        putInt(LlamaRuntimeIpc.EXTRA_N_KEEP, nKeep)
    }
}

internal fun Bundle.toRuntimeGenerationConfig(): RuntimeGenerationConfig {
    return RuntimeGenerationConfig(
        nThreads = getInt(LlamaRuntimeIpc.EXTRA_THREADS, 0),
        nThreadsBatch = getInt(LlamaRuntimeIpc.EXTRA_THREADS_BATCH, 0),
        nBatch = getInt(LlamaRuntimeIpc.EXTRA_BATCH, 512),
        nUbatch = getInt(LlamaRuntimeIpc.EXTRA_UBATCH, 512),
        nCtx = getInt(LlamaRuntimeIpc.EXTRA_CTX, 2048),
        gpuEnabled = getBoolean(LlamaRuntimeIpc.EXTRA_GPU_ENABLED, false),
        gpuLayers = getInt(LlamaRuntimeIpc.EXTRA_GPU_LAYERS, 0),
        gpuBackend = getString(LlamaRuntimeIpc.EXTRA_GPU_BACKEND)
            ?.let { raw -> runCatching { GpuExecutionBackend.valueOf(raw) }.getOrNull() }
            ?: GpuExecutionBackend.AUTO,
        strictGpuOffload = getBoolean(LlamaRuntimeIpc.EXTRA_STRICT_GPU_OFFLOAD, true),
        kvCachePreset = getString(LlamaRuntimeIpc.EXTRA_KV_PRESET)
            ?.let { raw -> runCatching { KvCachePreset.valueOf(raw) }.getOrNull() }
            ?: KvCachePreset.SAFE,
        sampling = com.pocketagent.nativebridge.RuntimeSamplingConfig(
            temperature = getFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TEMPERATURE, 0.7f),
            topK = getInt(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_K, 40),
            topP = getFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_P, 0.95f),
        ),
        speculativeEnabled = getBoolean(LlamaRuntimeIpc.EXTRA_SPECULATIVE_ENABLED, false),
        speculativeDraftModelId = getString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_ID),
        speculativeDraftModelPath = getString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_PATH),
        speculativeMaxDraftTokens = getInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MAX, 6),
        speculativeMinDraftTokens = getInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MIN, 2),
        speculativeDraftGpuLayers = getInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_GPU_LAYERS, 0),
        useMmap = getBoolean(LlamaRuntimeIpc.EXTRA_USE_MMAP, true),
        useMlock = getBoolean(LlamaRuntimeIpc.EXTRA_USE_MLOCK, false),
        nKeep = getInt(LlamaRuntimeIpc.EXTRA_N_KEEP, 128),
    )
}

internal fun GenerationResult.toBundle(): Bundle {
    return Bundle().apply {
        putString(LlamaRuntimeIpc.EXTRA_RESULT_FINISH_REASON, finishReason.name)
        putInt(LlamaRuntimeIpc.EXTRA_RESULT_TOKEN_COUNT, tokenCount)
        putLong(LlamaRuntimeIpc.EXTRA_RESULT_FIRST_TOKEN_MS, firstTokenMs)
        putLong(LlamaRuntimeIpc.EXTRA_RESULT_TOTAL_MS, totalMs)
        putBoolean(LlamaRuntimeIpc.EXTRA_RESULT_CANCELLED, cancelled)
        prefillMs?.let { putLong(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS, it) }
        decodeMs?.let { putLong(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS, it) }
        tokensPerSec?.let { putDouble(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC, it) }
        peakRssMb?.let { putDouble(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB, it) }
        putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, errorCode)
        putString(LlamaRuntimeIpc.EXTRA_RESULT_KV_PRESET, kvCachePreset?.name)
    }
}

internal fun Bundle.withOk(): Bundle {
    putBoolean(LlamaRuntimeIpc.EXTRA_OK, true)
    return this
}

internal fun Bundle.withEpoch(epoch: Long): Bundle {
    putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, epoch)
    return this
}
