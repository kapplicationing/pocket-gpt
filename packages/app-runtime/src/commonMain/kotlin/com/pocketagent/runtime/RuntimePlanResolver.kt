package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.RuntimeModelRegistryPort
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.io.File
import java.security.MessageDigest

data class ResolvedRuntimePlan(
    val modelId: String,
    val baseDefaults: PerformanceRuntimeConfig,
    val requestConfig: PerformanceRuntimeConfig,
    val effectiveConfig: PerformanceRuntimeConfig,
    val generationConfig: RuntimeGenerationConfig,
    val prefixCacheSlotId: String,
    val sessionCacheKey: String,
    val sessionCacheIdentity: SessionCacheIdentity,
    val keepAliveMs: Long,
    val diagnostics: List<String>,
    val estimatedMemoryMb: Double? = null,
    val loadBlockedReason: String? = null,
)

internal class RuntimePlanResolver(
    private val availableCpuThreads: () -> Int = { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) },
    private val memoryBudgetTracker: MemoryBudgetTracker? = null,
    private val recommendedGpuLayers: (String, PerformanceRuntimeConfig) -> Int? = { _, _ -> null },
    private val modelFileSizeBytes: (String?) -> Long = { path ->
        path?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
    },
) {
    fun resolve(
        sessionId: String,
        modelId: String,
        modelVersion: String? = null,
        taskType: String,
        stopSequences: List<String>,
        requestConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
        deviceState: DeviceState,
        runtimeInferencePorts: RuntimeInferencePorts = RuntimeInferencePorts(),
    ): ResolvedRuntimePlan {
        val modelRegistry = runtimeInferencePorts.modelRegistry
        val diagnostics = mutableListOf<String>()
        val baseDefaults = PerformanceRuntimeConfig.forProfile(
            profile = requestConfig.profile,
            availableCpuThreads = availableCpuThreads(),
            gpuEnabled = requestConfig.gpuEnabled,
            gpuLayers = requestConfig.gpuLayers,
        )
        if (baseDefaults != requestConfig) {
            diagnostics += "layer=request_override"
        }

        val modelSizeBytes = resolveModelSizeBytes(modelId = modelId, modelRegistry = modelRegistry)
        val modelMetadata = modelRegistry?.cachedModelMetadata(modelId)
        val modelLayerCount = modelRegistry?.cachedModelLayerCount(modelId)

        var effectiveConfig = requestConfig.applyContextBucket(taskType = taskType, deviceState = deviceState)
        if (effectiveConfig != requestConfig) {
            diagnostics += "layer=context_bucket"
        }

        val thermalAdjustedConfig = effectiveConfig.withThermalAdaptiveOverrides(deviceState)
        if (thermalAdjustedConfig != effectiveConfig) {
            diagnostics += "layer=thermal_override"
        }
        effectiveConfig = thermalAdjustedConfig

        val estimatedGpuLayers = modelRegistry?.cachedEstimatedMaxGpuLayers(modelId, effectiveConfig.nCtx)
        val gpuEstimatedConfig = effectiveConfig.applyGpuLayerEstimate(estimatedGpuLayers)
        if (gpuEstimatedConfig != effectiveConfig) {
            diagnostics += "layer=gpu_layer_estimate"
        }
        effectiveConfig = gpuEstimatedConfig

        val gpuClampedConfig = effectiveConfig.applyGpuLayerCeiling(modelLayerCount)
        if (gpuClampedConfig != effectiveConfig) {
            diagnostics += "layer=gpu_layer_ceiling"
        }
        effectiveConfig = gpuClampedConfig

        val memoryAdjustment = effectiveConfig.applyMemoryEstimate(
            modelId = modelId,
            modelSizeBytes = modelSizeBytes,
            modelMetadata = modelMetadata,
            memoryBudgetTracker = memoryBudgetTracker,
            recommendedGpuLayers = { candidateConfig ->
                listOfNotNull(
                    recommendedGpuLayers(modelId, candidateConfig)?.takeIf { it >= 0 },
                    modelRegistry?.cachedEstimatedMaxGpuLayers(modelId, candidateConfig.nCtx)?.takeIf { it >= 0 },
                ).minOrNull()
            },
        )
        diagnostics += memoryAdjustment.diagnostics
        effectiveConfig = memoryAdjustment.config

        val speculativeGated = effectiveConfig.gateSpeculative(
            modelId = modelId,
            deviceState = deviceState,
            modelRegistry = modelRegistry,
        )
        if (speculativeGated != effectiveConfig) {
            diagnostics += "layer=speculative_gate"
        }
        effectiveConfig = speculativeGated

        val mmapAdjustedConfig = effectiveConfig.applyMmapStrategy(
            deviceState = deviceState,
            modelSizeBytes = modelSizeBytes,
        )
        if (mmapAdjustedConfig != effectiveConfig) {
            diagnostics += "layer=mmap_strategy"
        }
        effectiveConfig = mmapAdjustedConfig

        val generationConfig = effectiveConfig.toRuntimeGenerationConfig().copy(
            useMlock = shouldUseMlock(deviceState = deviceState, modelSizeBytes = modelSizeBytes),
            nKeep = resolveNKeep(taskType = taskType, nCtx = effectiveConfig.nCtx),
        )
        val loadFingerprint = sha256Hex(
            listOf(
                modelId,
                modelVersion.orEmpty(),
                generationConfig.toLoadConfig().toString(),
            ).joinToString("|"),
        )
        val resolvedModelPathHash = sha256Hex(modelRegistry?.registeredModelPath(modelId).orEmpty())
        val templateFingerprint = sha256Hex(
            listOf(modelId, taskType, stopSequences.joinToString(separator = "\\u001f")).joinToString("|"),
        )
        val prefixCacheSlotId = sha256Hex(
            listOf(
                "slot",
                sessionId,
                modelId,
                templateFingerprint,
            ).joinToString("|"),
        )
        val sessionCacheKey = sha256Hex(
            listOf("session", modelId, modelVersion.orEmpty(), resolvedModelPathHash, loadFingerprint).joinToString("|"),
        )
        val sessionCacheIdentity = SessionCacheIdentity(
            cacheKey = sessionCacheKey,
            modelId = modelId,
            modelVersion = modelVersion,
            modelPathHash = resolvedModelPathHash,
            loadFingerprint = loadFingerprint,
        )
        val keepAliveMs = residencyPolicy.resolveKeepAliveMs(
            deviceState = deviceState,
            modelSizeBytes = modelSizeBytes,
        )
        return ResolvedRuntimePlan(
            modelId = modelId,
            baseDefaults = baseDefaults,
            requestConfig = requestConfig,
            effectiveConfig = effectiveConfig,
            generationConfig = generationConfig,
            prefixCacheSlotId = prefixCacheSlotId,
            sessionCacheKey = sessionCacheKey,
            sessionCacheIdentity = sessionCacheIdentity,
            keepAliveMs = keepAliveMs,
            diagnostics = diagnostics,
            estimatedMemoryMb = memoryAdjustment.estimate?.estimatedMb,
            loadBlockedReason = memoryAdjustment.loadBlockedReason,
        )
    }

    private fun resolveModelSizeBytes(
        modelId: String,
        modelRegistry: RuntimeModelRegistryPort?,
    ): Long {
        return modelRegistry?.cachedModelMetadata(modelId)?.sizeBytes
            ?: modelRegistry?.cachedModelSizeBytes(modelId)
            ?: modelRegistry?.registeredModelPath(modelId)?.let(modelFileSizeBytes)
            ?: 0L
    }
}

private data class MemoryAdjustmentResult(
    val config: PerformanceRuntimeConfig,
    val estimate: RuntimeMemoryEstimate?,
    val diagnostics: List<String>,
    val loadBlockedReason: String? = null,
)

private fun PerformanceRuntimeConfig.applyMemoryEstimate(
    modelId: String,
    modelSizeBytes: Long,
    modelMetadata: ModelRuntimeMetadata?,
    memoryBudgetTracker: MemoryBudgetTracker?,
    recommendedGpuLayers: (PerformanceRuntimeConfig) -> Int?,
): MemoryAdjustmentResult {
    if (modelSizeBytes <= 0L) {
        return MemoryAdjustmentResult(
            config = this,
            estimate = null,
            diagnostics = emptyList(),
        )
    }
    val availableMemoryMb = memoryBudgetTracker
        ?.availableMemoryCeilingMb
        ?.takeIf { it > 0.0 }
    val contextCandidates = buildList {
        add(nCtx)
        listOf(4096, 2048, 1536, 1024)
            .filter { it < nCtx }
            .forEach(::add)
    }.distinct()
    val diagnostics = mutableListOf<String>()
    var currentConfig = this
    var currentEstimate = estimateMemoryForConfig(
        config = currentConfig,
        modelFileSizeBytes = modelSizeBytes,
        modelMetadata = modelMetadata,
        availableMemoryMb = availableMemoryMb,
    )
    if (currentEstimate.fitsInMemory != false) {
        return MemoryAdjustmentResult(
            config = currentConfig,
            estimate = currentEstimate,
            diagnostics = diagnostics,
        )
    }
    contextCandidates.drop(1).forEach { candidateCtx ->
        val candidateConfig = currentConfig.copy(nCtx = candidateCtx)
        val candidateEstimate = estimateMemoryForConfig(
            config = candidateConfig,
            modelFileSizeBytes = modelSizeBytes,
            modelMetadata = modelMetadata,
            availableMemoryMb = availableMemoryMb,
        )
        if (!diagnostics.contains("layer=memory_estimate")) {
            diagnostics += "layer=memory_estimate"
        }
        currentConfig = candidateConfig
        currentEstimate = candidateEstimate
        if (candidateEstimate.fitsInMemory != false) {
            return MemoryAdjustmentResult(
                config = currentConfig,
                estimate = currentEstimate,
                diagnostics = diagnostics,
            )
        }
    }

    val recommendedLayers = recommendedGpuLayers(currentConfig)
    if (recommendedLayers != null && currentConfig.gpuEnabled) {
        val clampedGpuLayers = when {
            currentConfig.gpuLayers < 0 -> recommendedLayers
            else -> minOf(currentConfig.gpuLayers, recommendedLayers)
        }.coerceAtLeast(0)
        if (clampedGpuLayers != currentConfig.gpuLayers) {
            currentConfig = currentConfig.copy(
                gpuLayers = clampedGpuLayers,
                speculativeDraftGpuLayers = minOf(currentConfig.speculativeDraftGpuLayers, clampedGpuLayers),
            )
            if (!diagnostics.contains("layer=memory_gpu_recommendation")) {
                diagnostics += "layer=memory_gpu_recommendation"
            }
            currentEstimate = estimateMemoryForConfig(
                config = currentConfig,
                modelFileSizeBytes = modelSizeBytes,
                modelMetadata = modelMetadata,
                availableMemoryMb = availableMemoryMb,
            )
            if (currentEstimate.fitsInMemory != false) {
                return MemoryAdjustmentResult(
                    config = currentConfig,
                    estimate = currentEstimate,
                    diagnostics = diagnostics,
                )
            }
        }
    }

    if (currentConfig.speculativeEnabled || currentConfig.speculativeDraftGpuLayers > 0) {
        val speculativeDisabledConfig = currentConfig.copy(
            speculativeEnabled = false,
            speculativeDraftModelId = null,
            speculativeDraftGpuLayers = 0,
        )
        if (speculativeDisabledConfig != currentConfig) {
            if (!diagnostics.contains("layer=memory_speculative_rescue")) {
                diagnostics += "layer=memory_speculative_rescue"
            }
            currentConfig = speculativeDisabledConfig
            currentEstimate = estimateMemoryForConfig(
                config = currentConfig,
                modelFileSizeBytes = modelSizeBytes,
                modelMetadata = modelMetadata,
                availableMemoryMb = availableMemoryMb,
            )
            if (currentEstimate.fitsInMemory != false) {
                return MemoryAdjustmentResult(
                    config = currentConfig,
                    estimate = currentEstimate,
                    diagnostics = diagnostics,
                )
            }
        }
    }

    buildMemoryBatchCandidates(currentConfig.nUbatch).forEach { candidateUbatch ->
        if (candidateUbatch >= currentConfig.nUbatch) {
            return@forEach
        }
        val candidateBatch = minOf(currentConfig.nBatch, candidateUbatch).coerceAtLeast(candidateUbatch)
        val candidateConfig = currentConfig.copy(
            nBatch = candidateBatch,
            nUbatch = candidateUbatch,
        )
        if (!diagnostics.contains("layer=memory_batch_rescue")) {
            diagnostics += "layer=memory_batch_rescue"
        }
        val candidateEstimate = estimateMemoryForConfig(
            config = candidateConfig,
            modelFileSizeBytes = modelSizeBytes,
            modelMetadata = modelMetadata,
            availableMemoryMb = availableMemoryMb,
        )
        currentConfig = candidateConfig
        currentEstimate = candidateEstimate
        if (candidateEstimate.fitsInMemory != false) {
            return MemoryAdjustmentResult(
                config = currentConfig,
                estimate = currentEstimate,
                diagnostics = diagnostics,
            )
        }
    }

    val blockedReason = availableMemoryMb?.let { ceilingMb ->
        val estimateMb = currentEstimate.estimatedMb
        val source = if (currentEstimate.usedModelMetadata) "metadata" else "fallback"
        "Model $modelId needs about ${"%.0f".format(estimateMb)} MB but the tracked safe memory ceiling is " +
            "${"%.0f".format(ceilingMb)} MB after reducing context to ${currentConfig.nCtx}. " +
            "Load blocked to avoid an unsafe OOM (${source} estimate)."
    }
    if (blockedReason != null && !diagnostics.contains("layer=memory_plan_blocked")) {
        diagnostics += "layer=memory_plan_blocked"
    }
    return MemoryAdjustmentResult(
        config = currentConfig,
        estimate = currentEstimate,
        diagnostics = diagnostics,
        loadBlockedReason = blockedReason,
    )
}

private fun estimateMemoryForConfig(
    config: PerformanceRuntimeConfig,
    modelFileSizeBytes: Long,
    modelMetadata: ModelRuntimeMetadata?,
    availableMemoryMb: Double?,
): RuntimeMemoryEstimate {
    return RuntimeModelMemoryEstimator.estimate(
        modelFileSizeBytes = modelFileSizeBytes,
        metadata = modelMetadata,
        nCtx = config.nCtx,
        kvCachePreset = config.kvCachePreset,
        nUbatch = config.nUbatch,
        availableMemoryMb = availableMemoryMb,
    )
}

private fun buildMemoryBatchCandidates(currentUbatch: Int): List<Int> {
    return buildList {
        add((currentUbatch / 2).coerceAtLeast(32))
        add((currentUbatch / 4).coerceAtLeast(32))
        add(64)
        add(32)
    }.distinct()
        .filter { candidate -> candidate > 0 }
        .sortedDescending()
}

private fun PerformanceRuntimeConfig.applyGpuLayerEstimate(maxGpuLayers: Int?): PerformanceRuntimeConfig {
    val estimate = maxGpuLayers?.takeIf { it >= 0 } ?: return this
    if (!gpuEnabled) {
        return copy(gpuLayers = 0, speculativeDraftGpuLayers = 0)
    }
    val resolvedGpuLayers = when {
        gpuLayers < 0 -> estimate
        else -> minOf(gpuLayers, estimate)
    }
    return copy(
        gpuLayers = resolvedGpuLayers.coerceAtLeast(0),
        speculativeDraftGpuLayers = minOf(speculativeDraftGpuLayers, resolvedGpuLayers.coerceAtLeast(0)),
    )
}

private fun PerformanceRuntimeConfig.applyContextBucket(
    taskType: String,
    deviceState: DeviceState,
): PerformanceRuntimeConfig {
    val ramClassGb = deviceState.ramClassGb.coerceAtLeast(1)
    val cappedCtx = when {
        ramClassGb <= 4 -> 1024
        ramClassGb <= 6 -> if (taskType == "long_text") 1536 else 1024
        ramClassGb <= 8 -> if (taskType == "long_text") 2048 else 1536
        else -> nCtx
    }
    return copy(nCtx = minOf(nCtx, cappedCtx))
}

private fun PerformanceRuntimeConfig.applyGpuLayerCeiling(modelLayerCount: Int?): PerformanceRuntimeConfig {
    val ceiling = modelLayerCount?.takeIf { it > 0 } ?: return this
    return copy(
        gpuLayers = if (gpuLayers == -1) -1 else minOf(gpuLayers, ceiling),
        speculativeDraftGpuLayers = minOf(speculativeDraftGpuLayers, ceiling),
    )
}

private fun PerformanceRuntimeConfig.applyMmapStrategy(
    deviceState: DeviceState,
    modelSizeBytes: Long,
): PerformanceRuntimeConfig {
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    val shouldUseMmap = useMmap && (deviceState.ramClassGb > 4 || modelSizeMb > 2048.0)
    return copy(useMmap = shouldUseMmap)
}

private fun shouldUseMlock(deviceState: DeviceState, modelSizeBytes: Long): Boolean {
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    return deviceState.ramClassGb >= 12 && modelSizeMb in 1.0..2048.0
}

private fun resolveNKeep(taskType: String, nCtx: Int): Int {
    val baseline = if (taskType == "long_text") 256 else 128
    return baseline.coerceAtLeast(64).coerceAtMost((nCtx / 8).coerceAtLeast(64))
}

private fun PerformanceRuntimeConfig.gateSpeculative(
    modelId: String,
    deviceState: DeviceState,
    modelRegistry: RuntimeModelRegistryPort?,
): PerformanceRuntimeConfig {
    if (!speculativeEnabled) {
        return this
    }
    if (deviceState.ramClassGb < 8) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    val draftModelId = speculativeDraftModelId?.trim().orEmpty()
    if (draftModelId.isEmpty()) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    val draftPath = modelRegistry?.registeredModelPath(draftModelId)
    val targetPath = modelRegistry?.registeredModelPath(modelId)
    if (draftPath.isNullOrBlank() || draftPath == targetPath) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    if (!ModelCatalog.isSpeculativeDraftCompatible(targetModelId = modelId, draftModelId = draftModelId)) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    return this
}

private fun ModelResidencyPolicy.resolveKeepAliveMs(
    deviceState: DeviceState,
    modelSizeBytes: Long,
): Long {
    if (!keepLoadedWhileAppForeground) {
        return 1L
    }
    if (!adaptiveIdleTtl) {
        return idleUnloadTtlMs.coerceAtLeast(1L)
    }
    val deviceRamMb = deviceState.ramClassGb.coerceAtLeast(1) * 1024.0
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    val pressureRatio = if (modelSizeMb > 0.0) modelSizeMb / deviceRamMb else 0.0
    val baseTtl = when {
        pressureRatio > 0.5 -> 2 * 60_000L
        pressureRatio > 0.3 -> 5 * 60_000L
        else -> 15 * 60_000L
    }
    val thermalAdjusted = if (deviceState.thermalLevel >= 5) {
        baseTtl / 2L
    } else {
        baseTtl
    }
    val batteryAdjusted = when {
        deviceState.batteryPercent < 20 -> minOf(thermalAdjusted, 60_000L)
        deviceState.batteryPercent < 35 -> minOf(thermalAdjusted, 180_000L)
        else -> thermalAdjusted
    }
    return minOf(idleUnloadTtlMs, batteryAdjusted).coerceAtLeast(1L)
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
