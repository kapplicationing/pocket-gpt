package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.RuntimeReloadReason

interface RuntimeWarmupSupport {
    fun warmupActiveModel(): WarmupResult
}

data class WarmupResult(
    val attempted: Boolean,
    val warmed: Boolean,
    val residentHit: Boolean,
    val loadDurationMs: Long? = null,
    val warmupDurationMs: Long? = null,
    val speculativePath: Boolean = false,
    val errorCode: String? = null,
) {
    companion object {
        fun skipped(reason: String): WarmupResult {
            return WarmupResult(
                attempted = false,
                warmed = false,
                residentHit = false,
                speculativePath = false,
                errorCode = reason,
            )
        }
    }
}

internal class RuntimeWarmupCoordinator(
    private val inferenceModule: InferenceModule,
    private val artifactVerifier: ArtifactVerifier,
    private val observabilityModule: ObservabilityModule,
    private val runtimeResidencyManager: RuntimeResidencyManager,
    private val runtimePlanResolver: RuntimePlanResolver,
    private val availableCpuThreads: () -> Int = { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onWarmupStage: (String, ModelLoadingStage, Float) -> Unit = { _, _, _ -> },
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
    private val operationCoordinator: RuntimeOperationCoordinator = RuntimeOperationCoordinator(),
) {
    // Warmup owns planning, load, one-token generation, residency, and metrics as one transaction.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun warmup(residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy()): WarmupResult {
        if (!residencyPolicy.warmupOnStartup) {
            return WarmupResult.skipped("warmup_disabled")
        }
        val managedRuntime = runtimeInferencePorts.managedRuntime
        val cacheAwareGeneration = runtimeInferencePorts.cacheAwareGeneration
        val residencyPort = runtimeInferencePorts.residency
        if (managedRuntime == null || cacheAwareGeneration == null || residencyPort == null) {
            return WarmupResult.skipped("warmup_requires_native_bridge")
        }
        val activeBackend = cacheAwareGeneration.activeBackendIdentity()?.trim()?.lowercase()
        val modelId = artifactVerifier.manager().getActiveModel().trim()
        if (modelId.isEmpty()) {
            return WarmupResult.skipped("warmup_model_missing")
        }
        if (!artifactVerifier.manager().setActiveModel(modelId)) {
            return WarmupResult.skipped("warmup_model_unregistered")
        }
        val modelVersion = artifactVerifier.manager().getActiveModelVersion()
        val verification = artifactVerifier.verifyArtifactForModel(modelId)
        if (!verification.passed) {
            return WarmupResult.skipped("warmup_artifact_unverified")
        }
        if (activeBackend == null || activeBackend == "cpu" || activeBackend == "adb_fallback") {
            return WarmupResult.skipped("warmup_cpu_only_runtime")
        }
        if (!inferenceModule.listAvailableModels().contains(modelId)) {
            return WarmupResult.skipped("warmup_model_unavailable")
        }

        val performanceConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = availableCpuThreads(),
            gpuEnabled = managedRuntime.supportsGpuOffload(),
        )
        val runtimePlan = runtimePlanResolver.resolve(
            sessionId = "warmup",
            modelId = modelId,
            modelVersion = modelVersion,
            taskType = "warmup",
            stopSequences = emptyList(),
            requestConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            runtimeInferencePorts = runtimeInferencePorts,
        )
        runtimePlan.loadBlockedReason?.let { blockedReason ->
            val estimatedMemory = runtimePlan.estimatedMemoryMb?.toInt()?.toString() ?: "unknown"
            return WarmupResult.skipped(
                "warmup_planning_blocked:$estimatedMemory:$blockedReason",
            )
        }
        val warmupRequestId = "warmup-$modelId-${nowMs()}"
        val generationLease = when (
            val admission = operationCoordinator.tryAcquireGeneration(
                requestId = warmupRequestId,
                sessionId = "warmup",
            )
        ) {
            is GenerationAdmissionResult.Acquired -> admission.lease
            is GenerationAdmissionResult.Rejected -> return WarmupResult.skipped(admission.code.lowercase())
        }
        try {
            managedRuntime.setRuntimeGenerationConfig(runtimePlan.generationConfig)
        val speculativePath = runtimePlan.generationConfig.speculativeEnabled &&
            !runtimePlan.generationConfig.speculativeDraftModelPath.isNullOrBlank()

        val loadStartedAtMs = nowMs()
        val loaded = runtimeResidencyManager.ensureLoaded(
            modelId = modelId,
            slotId = runtimePlan.prefixCacheSlotId,
            keepAliveMs = runtimePlan.keepAliveMs,
            sessionCacheIdentity = runtimePlan.sessionCacheIdentity,
            strictGpuOffload = runtimePlan.generationConfig.strictGpuOffload,
        )
        val loadDurationMs = (nowMs() - loadStartedAtMs).coerceAtLeast(0L)
        if (!loaded) {
            runtimeResidencyManager.unload(reason = "warmup_load_failed")
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                speculativePath = speculativePath,
                errorCode = managedRuntime.lastBridgeError()?.code ?: "warmup_load_failed",
            )
        }

        val warmupStartedAtMs = nowMs()
        onWarmupStage(modelId, ModelLoadingStage.WARMING_UP, 0.98f)
        runtimeResidencyManager.onGenerationStarted()
        val warmupResult = runCatching {
            cacheAwareGeneration.generateStreamWithCache(
                requestId = warmupRequestId,
                request = InferenceRequest(prompt = WARMUP_PROMPT, maxTokens = WARMUP_MAX_TOKENS),
                cacheKey = null,
                cachePolicy = CachePolicy.OFF,
                onToken = {},
            )
        }.getOrElse { error ->
            runtimeResidencyManager.unload(reason = "warmup_exception")
            runtimeResidencyManager.onGenerationFinished(slotId = null, keepAliveMs = null)
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                speculativePath = speculativePath,
                errorCode = error.message ?: error::class.simpleName,
            )
        }
        val warmupDurationMs = (nowMs() - warmupStartedAtMs).coerceAtLeast(0L)
        if (!warmupResult.success) {
            runtimeResidencyManager.unload(reason = "warmup_generate_failed")
            runtimeResidencyManager.onGenerationFinished(slotId = null, keepAliveMs = null)
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                warmupDurationMs = warmupDurationMs,
                speculativePath = speculativePath,
                errorCode = warmupResult.errorCode ?: "warmup_generate_failed",
            )
        }

        residencyPort.recordWarmup(warmupDurationMs)
        val residencyState = residencyPort.residencyState()
        recordResidencyMetrics(
            observabilityModule = observabilityModule,
            residencyState = residencyState,
            loadDurationMs = loadDurationMs,
            thermalThrottled = false,
        )
        observabilityModule.recordLatencyMetric("inference.warmup_ms", warmupDurationMs.toDouble())
        observabilityModule.recordLatencyMetric(
            "inference.warmup.speculative_path",
            if (speculativePath) 1.0 else 0.0,
        )
        if (!residencyPolicy.keepLoadedWhileAppForeground) {
            runtimeResidencyManager.unload(reason = "warmup_policy")
            runtimeResidencyManager.onGenerationFinished(slotId = null, keepAliveMs = null)
        } else {
            runtimeResidencyManager.onGenerationFinished(
                slotId = runtimePlan.prefixCacheSlotId,
                keepAliveMs = runtimePlan.keepAliveMs,
            )
        }
            return WarmupResult(
                attempted = true,
                warmed = true,
                residentHit = residencyState.residentHit,
                loadDurationMs = loadDurationMs,
                warmupDurationMs = warmupDurationMs,
                speculativePath = speculativePath,
            )
        } finally {
            generationLease.close()
        }
    }

    private companion object {
        // Keep warmup cheap enough that a manual model load does not feel stuck
        // behind a real chat turn, especially on CPU-only devices.
        private const val WARMUP_MAX_TOKENS = 1
        private const val WARMUP_PROMPT = "Ready?"
    }
}

internal fun recordResidencyMetrics(
    observabilityModule: ObservabilityModule,
    residencyState: com.pocketagent.nativebridge.RuntimeResidencyState,
    loadDurationMs: Long?,
    thermalThrottled: Boolean,
) {
    loadDurationMs?.let { observabilityModule.recordLatencyMetric("inference.model_load_ms", it.toDouble()) }
    observabilityModule.recordLatencyMetric(
        "inference.resident_hit",
        if (residencyState.residentHit) 1.0 else 0.0,
    )
    observabilityModule.recordLatencyMetric(
        "inference.thermal_throttled",
        if (thermalThrottled) 1.0 else 0.0,
    )
    val reloadReason = residencyState.reloadReason ?: return
    observabilityModule.recordLatencyMetric(
        "inference.reload_reason.${reloadReason.metricName()}",
        1.0,
    )
}

private fun RuntimeReloadReason.metricName(): String {
    return name.lowercase()
}
