package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest

interface ManagedRuntimePort {
    fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean): Boolean
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig)
    fun supportsGpuOffload(): Boolean
    fun runtimeBackend(): RuntimeBackend
    fun lastBridgeError(): BridgeError?
    fun currentModelLifecycleState(): ModelLifecycleEvent
    fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable
    fun currentRssMb(): Double?
    fun isRuntimeReleased(): Boolean
    fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean = false
    fun freeMultimodal() {}
    fun isMultimodalEnabled(): Boolean = false
    fun generateWithImages(
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult = GenerationResult(
        finishReason = GenerationFinishReason.ERROR,
        tokenCount = 0,
        firstTokenMs = -1L,
        totalMs = 0L,
        cancelled = false,
        errorCode = "MULTIMODAL_NOT_SUPPORTED",
    )
}

interface CacheAwareGenerationPort {
    fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult

    fun cancelGeneration(requestId: String): Boolean
    fun actualGpuLayers(): Int?
    fun actualDraftGpuLayers(): Int?
    fun lastGpuLoadRetryCount(): Int?
    fun activeBackendIdentity(): String? = null
}

interface RuntimeModelRegistryPort {
    fun registerModelPath(modelId: String, absolutePath: String)
    fun registeredModelPath(modelId: String): String?
    fun registerModelMetadata(modelId: String, metadata: ModelRuntimeMetadata)
    fun cachedModelMetadata(modelId: String): ModelRuntimeMetadata?
    fun cachedModelLayerCount(modelId: String): Int?
    fun cachedModelSizeBytes(modelId: String): Long?
    fun cachedEstimatedMaxGpuLayers(modelId: String, nCtx: Int): Int?
}

interface RuntimeResidencyPort {
    fun updateResidencySlot(slotId: String?, expiresAtEpochMs: Long?)
    fun residencyState(): RuntimeResidencyState
    fun prefixCacheDiagnosticsLine(): String?
    fun recordWarmup(durationMs: Long)
}

interface RuntimeSessionCachePort {
    fun saveSessionCache(filePath: String): Boolean
    fun loadSessionCache(filePath: String): Boolean
}

data class RuntimeCloseResult(
    val success: Boolean,
    val runtimeReusable: Boolean,
    val code: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun closed(): RuntimeCloseResult = RuntimeCloseResult(success = true, runtimeReusable = false)

        fun rejected(code: String, detail: String? = null): RuntimeCloseResult {
            return RuntimeCloseResult(success = false, runtimeReusable = true, code = code, detail = detail)
        }

        fun terminated(code: String, detail: String? = null): RuntimeCloseResult {
            return RuntimeCloseResult(success = false, runtimeReusable = false, code = code, detail = detail)
        }
    }
}

interface RuntimeLifetimePort {
    fun closeRuntime(timeoutMs: Long = 5_000L): RuntimeCloseResult
}

data class RuntimeInferencePorts(
    val managedRuntime: ManagedRuntimePort? = null,
    val cacheAwareGeneration: CacheAwareGenerationPort? = null,
    val modelRegistry: RuntimeModelRegistryPort? = null,
    val residency: RuntimeResidencyPort? = null,
    val sessionCache: RuntimeSessionCachePort? = null,
    val lifetime: RuntimeLifetimePort? = null,
)

interface RuntimeInferencePortProvider {
    fun runtimeInferencePorts(): RuntimeInferencePorts
}

fun InferenceModule.runtimeInferencePorts(): RuntimeInferencePorts {
    return (this as? RuntimeInferencePortProvider)?.runtimeInferencePorts() ?: RuntimeInferencePorts()
}

fun createDefaultRuntimeInferenceModule(): InferenceModule = LlamaCppInferenceModule()
