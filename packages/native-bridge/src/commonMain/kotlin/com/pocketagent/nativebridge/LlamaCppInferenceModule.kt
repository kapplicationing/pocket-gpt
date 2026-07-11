package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest

class LlamaCppInferenceModule(
    private val runtimeBridge: LlamaCppRuntimeBridge = NativeJniLlamaCppBridge(),
) : InferenceModule,
    RuntimeInferencePortProvider,
    ManagedRuntimePort,
    CacheAwareGenerationPort,
    RuntimeModelRegistryPort,
    RuntimeResidencyPort,
    RuntimeSessionCachePort,
    RuntimeLifetimePort {
    private var activeModelId: String? = null
    private val modelPathById: MutableMap<String, String> = mutableMapOf()
    private val modelMetadataById: MutableMap<String, ModelRuntimeMetadata> = mutableMapOf()
    private val estimatedGpuLayersByModelAndContext: MutableMap<Pair<String, Int>, Int> = mutableMapOf()
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private var requiresReloadForConfigChange: Boolean = false
    private var activeRuntimeKey: LoadedRuntimeKey? = null
    @Volatile
    private var runtimeResidencyState: RuntimeResidencyState = RuntimeResidencyState()

    override fun listAvailableModels(): List<String> {
        val bridgeModels = runtimeBridge.listAvailableModels()
        if (modelPathById.isEmpty()) {
            return bridgeModels
        }
        return bridgeModels.filter { modelPathById[it]?.isNotBlank() == true }
    }

    override fun runtimeInferencePorts(): RuntimeInferencePorts {
        return RuntimeInferencePorts(
            managedRuntime = this,
            cacheAwareGeneration = this,
            modelRegistry = this,
            residency = this,
            sessionCache = this,
            lifetime = this,
        )
    }

    override fun loadModel(modelId: String): Boolean {
        return loadModel(
            modelId = modelId,
            modelVersion = null,
            strictGpuOffload = runtimeGenerationConfig.strictGpuOffload,
        )
    }

    override fun loadModel(
        modelId: String,
        modelVersion: String?,
        strictGpuOffload: Boolean,
    ): Boolean {
        if (!runtimeBridge.listAvailableModels().contains(modelId)) {
            return false
        }
        val resolvedConfig = resolveRuntimeGenerationConfig(runtimeGenerationConfig)
        val loadedRuntimeKey = buildLoadedRuntimeKey(
            modelId = modelId,
            modelPath = modelPathById[modelId],
            config = resolvedConfig,
        )
        if (activeModelId == modelId && !requiresReloadForConfigChange && activeRuntimeKey == loadedRuntimeKey) {
            runtimeResidencyState = runtimeResidencyState.copy(
                key = loadedRuntimeKey,
                resident = true,
                residentHit = true,
                residentHitCount = runtimeResidencyState.residentHitCount + 1L,
                lastAccessAtEpochMs = System.currentTimeMillis(),
            )
            return true
        }
        runtimeBridge.setRuntimeGenerationConfig(resolvedConfig)
        val reloadReason = resolveReloadReason(loadedRuntimeKey)
        val startedAtMs = System.currentTimeMillis()
        val loaded = runtimeBridge.loadModel(
            modelId = modelId,
            modelPath = modelPathById[modelId],
            options = ModelLoadOptions(
                modelVersion = modelVersion,
                strictGpuOffload = strictGpuOffload,
            ),
        )
        val completedAtMs = System.currentTimeMillis()
        if (loaded) {
            val existingMetadata = modelMetadataById[modelId] ?: ModelRuntimeMetadata()
            modelMetadataById[modelId] = existingMetadata.copy(
                layerCount = runtimeBridge.modelLayerCount()?.takeIf { it > 0 } ?: existingMetadata.layerCount,
                sizeBytes = runtimeBridge.modelSizeBytes()?.takeIf { it > 0L } ?: existingMetadata.sizeBytes,
            )
            runtimeBridge.estimateMaxGpuLayers(resolvedConfig.nCtx)
                ?.takeIf { it >= 0 }
                ?.let { estimate ->
                    estimatedGpuLayersByModelAndContext[modelId to resolvedConfig.nCtx] = estimate
                }
        }
        activeModelId = if (loaded) modelId else null
        activeRuntimeKey = if (loaded) loadedRuntimeKey else null
        runtimeResidencyState = runtimeResidencyState.copy(
            key = activeRuntimeKey,
            resident = loaded,
            residentHit = false,
            reloadReason = reloadReason,
            lastLoadDurationMs = (completedAtMs - startedAtMs).coerceAtLeast(0L),
            lastLoadAtEpochMs = completedAtMs,
            lastAccessAtEpochMs = completedAtMs,
        )
        requiresReloadForConfigChange = false
        return loaded
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        val result = generateStreamWithCache(
            requestId = "legacy",
            request = request,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = onToken,
        )
        check(result.success) {
            "llama.cpp runtime generation failed. " +
                "finishReason=${result.finishReason} errorCode=${result.errorCode.orEmpty()}"
        }
    }

    override fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        check(activeModelId != null) { "Model must be loaded before generation." }
        val result = runtimeBridge.generate(
            requestId = requestId,
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
            onToken = onToken,
        )
        runtimeResidencyState = runtimeResidencyState.copy(
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
        return result
    }

    override fun unloadModel() {
        runtimeBridge.offloadModel(reason = "explicit_unload")
        activeModelId = null
        activeRuntimeKey = null
        runtimeResidencyState = runtimeResidencyState.copy(
            key = null,
            resident = false,
            residentHit = false,
            reloadReason = RuntimeReloadReason.EXPLICIT_UNLOAD,
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
        requiresReloadForConfigChange = false
    }

    fun offloadModel(reason: String): Boolean {
        val unloaded = runtimeBridge.offloadModel(reason)
        if (unloaded) {
            activeModelId = null
            activeRuntimeKey = null
            runtimeResidencyState = runtimeResidencyState.copy(
                key = null,
                resident = false,
                residentHit = false,
                reloadReason = RuntimeReloadReason.EXPLICIT_UNLOAD,
                lastAccessAtEpochMs = System.currentTimeMillis(),
            )
            requiresReloadForConfigChange = false
        }
        return unloaded
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        val resolvedConfig = resolveRuntimeGenerationConfig(config)
        runtimeGenerationConfig = config
        runtimeBridge.setRuntimeGenerationConfig(resolvedConfig)
        if (activeRuntimeKey == null) {
            requiresReloadForConfigChange = false
            return
        }
        requiresReloadForConfigChange = activeRuntimeKey != buildLoadedRuntimeKey(
            modelId = activeRuntimeKey?.modelId.orEmpty(),
            modelPath = activeRuntimeKey?.modelPath,
            config = resolvedConfig,
        )
    }

    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeBridge.getRuntimeGenerationConfig()

    override fun supportsGpuOffload(): Boolean = runtimeBridge.supportsGpuOffload()

    fun cancelGeneration(): Boolean {
        return runtimeBridge.cancelGeneration()
    }

    override fun cancelGeneration(requestId: String): Boolean {
        return runtimeBridge.cancelGeneration(requestId)
    }

    override fun runtimeBackend(): RuntimeBackend = runtimeBridge.runtimeBackend()

    override fun lastBridgeError(): BridgeError? = runtimeBridge.lastError()

    fun loadedModel(): LoadedModelInfo? = runtimeBridge.getLoadedModel()

    override fun currentModelLifecycleState(): ModelLifecycleEvent = runtimeBridge.currentModelLifecycleState()

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return runtimeBridge.observeModelLifecycleState(listener)
    }

    override fun registerModelPath(modelId: String, absolutePath: String) {
        val normalizedPath = absolutePath.trim()
        if (normalizedPath.isBlank()) {
            return
        }
        modelPathById[modelId] = normalizedPath
    }

    override fun registeredModelPath(modelId: String): String? {
        return modelPathById[modelId]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun registerModelMetadata(modelId: String, metadata: ModelRuntimeMetadata) {
        modelMetadataById[modelId] = metadata
    }

    override fun cachedModelMetadata(modelId: String): ModelRuntimeMetadata? = modelMetadataById[modelId]

    override fun cachedModelLayerCount(modelId: String): Int? = modelMetadataById[modelId]?.layerCount

    override fun cachedModelSizeBytes(modelId: String): Long? = modelMetadataById[modelId]?.sizeBytes

    fun activeModelLayerCount(): Int? = activeModelId?.let(::cachedModelLayerCount)

    fun activeModelSizeBytes(): Long? = activeModelId?.let(::cachedModelSizeBytes)

    override fun actualGpuLayers(): Int? = runtimeBridge.actualGpuLayers()

    override fun actualDraftGpuLayers(): Int? = runtimeBridge.actualDraftGpuLayers()

    override fun lastGpuLoadRetryCount(): Int? = runtimeBridge.lastGpuLoadRetryCount()

    override fun activeBackendIdentity(): String? = runtimeBridge.activeBackendIdentity()

    override fun currentRssMb(): Double? = runtimeBridge.currentRssMb()

    override fun isRuntimeReleased(): Boolean = runtimeBridge.isRuntimeReleased()

    override fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean =
        runtimeBridge.initMultimodal(mmProjPath, useGpu, imageMaxTokens)

    override fun freeMultimodal() = runtimeBridge.freeMultimodal()

    override fun isMultimodalEnabled(): Boolean = runtimeBridge.isMultimodalEnabled()

    override fun generateWithImages(
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult {
        check(activeModelId != null) { "Model must be loaded before multimodal generation." }
        val result = runtimeBridge.generateWithImages(requestId, prompt, imagePaths, maxTokens, onToken)
        runtimeResidencyState = runtimeResidencyState.copy(
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
        return result
    }

    override fun cachedEstimatedMaxGpuLayers(modelId: String, nCtx: Int): Int? {
        val resolvedCtx = nCtx.coerceAtLeast(1)
        val cacheKey = modelId to resolvedCtx
        estimatedGpuLayersByModelAndContext[cacheKey]?.let { return it }
        if (activeModelId != modelId) {
            return null
        }
        val estimate = runtimeBridge.estimateMaxGpuLayers(resolvedCtx)?.takeIf { it >= 0 } ?: return null
        estimatedGpuLayersByModelAndContext[cacheKey] = estimate
        return estimate
    }

    override fun updateResidencySlot(slotId: String?, expiresAtEpochMs: Long?) {
        runtimeResidencyState = runtimeResidencyState.copy(
            slotId = slotId,
            expiresAtEpochMs = expiresAtEpochMs,
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
    }

    override fun residencyState(): RuntimeResidencyState = runtimeResidencyState

    override fun prefixCacheDiagnosticsLine(): String? {
        return runtimeBridge.prefixCacheDiagnosticsLine()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun saveSessionCache(filePath: String): Boolean {
        if (activeModelId == null) return false
        return runtimeBridge.saveSessionCache(filePath)
    }

    override fun loadSessionCache(filePath: String): Boolean {
        if (activeModelId == null) return false
        return runtimeBridge.loadSessionCache(filePath)
    }

    override fun recordWarmup(durationMs: Long) {
        runtimeResidencyState = runtimeResidencyState.copy(
            lastWarmupDurationMs = durationMs.coerceAtLeast(0L),
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        val result = runtimeBridge.closeRuntime(timeoutMs)
        if (result.success) {
            activeModelId = null
            activeRuntimeKey = null
            runtimeResidencyState = RuntimeResidencyState()
        }
        return result
    }

    private fun resolveRuntimeGenerationConfig(config: RuntimeGenerationConfig): RuntimeGenerationConfig {
        if (!config.speculativeEnabled) {
            return config.copy(speculativeDraftModelPath = null, speculativeDraftGpuLayers = 0)
        }
        val resolvedDraftPath = config.speculativeDraftModelPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: config.speculativeDraftModelId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { modelPathById[it] }
        return config.copy(speculativeDraftModelPath = resolvedDraftPath)
    }

    private fun buildLoadedRuntimeKey(
        modelId: String,
        modelPath: String?,
        config: RuntimeGenerationConfig,
    ): LoadedRuntimeKey {
        return LoadedRuntimeKey(
            modelId = modelId,
            modelPath = modelPath?.trim()?.takeIf { it.isNotEmpty() },
            backend = runtimeBridge.runtimeBackend(),
            loadConfig = config.toLoadConfig(),
        )
    }

    private fun resolveReloadReason(nextKey: LoadedRuntimeKey): RuntimeReloadReason {
        val currentKey = activeRuntimeKey ?: return RuntimeReloadReason.INITIAL_LOAD
        return when {
            currentKey.modelId != nextKey.modelId -> RuntimeReloadReason.MODEL_CHANGED
            currentKey.modelPath != nextKey.modelPath -> RuntimeReloadReason.MODEL_PATH_CHANGED
            currentKey.backend != nextKey.backend -> RuntimeReloadReason.BACKEND_CHANGED
            else -> RuntimeReloadReason.GENERATION_CONFIG_CHANGED
        }
    }
}
