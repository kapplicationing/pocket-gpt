package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class BridgeError(
    val code: String,
    val detail: String? = null,
)

class NativeJniLlamaCppBridge(
    private val libraryName: String = "pocket_llama",
    private val nativeApi: NativeApi = JniNativeApi(),
    private val libraryLoader: (String) -> Unit = System::loadLibrary,
    private val supportedModels: Set<String> = ModelCatalog.bridgeSupportedModels().toSet(),
    private val fallbackBridge: LlamaCppRuntimeBridge = AdbDeviceLlamaCppBridge(),
    private val fallbackEnabled: Boolean = defaultFallbackEnabled(),
    private val gpuOffloadAllowed: Boolean = defaultGpuOffloadEnabled(),
    private val optimizedNativeLibrariesEnabled: Boolean = defaultOptimizedNativeLibrariesEnabled(),
    private val openClQualificationProvider: (() -> OpenClQualificationSnapshot?)? = null,
) : LlamaCppRuntimeBridge {
    private val lifecycleLock = Any()
    private val lifecycleObserverLock = Any()
    private val lifecycleDispatchLock = Object()
    private val lifecycleDispatchQueue: ArrayDeque<ModelLifecycleEvent> = ArrayDeque()
    private var initialized = false
    private var runtimeReady = false
    private var usingFallback = false
    @Volatile
    private var activeLoadToken: Long = 0L
    private var nextObserverId: Int = 1
    @Volatile
    private var lifecycleDispatcherStarted = false
    private val lifecycleObservers: MutableMap<Int, (ModelLifecycleEvent) -> Unit> = mutableMapOf()
    @Volatile
    private var lastBridgeError: BridgeError? = null
    @Volatile
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var activeRequestId: String? = null
    @Volatile
    private var loadedModel: LoadedModelInfo? = null
    @Volatile
    private var currentLifecycleEvent: ModelLifecycleEvent = ModelLifecycleEvent(
        state = ModelLifecycleState.UNLOADED,
    )
    @Volatile
    private var lastAppliedGpuLayers: Int? = null
    @Volatile
    private var lastAppliedDraftGpuLayers: Int? = null
    @Volatile
    private var lastGpuLoadRetryCount: Int? = null
    @Volatile
    private var lastSuccessfulLoadDetail: String? = null
    @Volatile
    private var loadedNativeLibraryName: String? = null
    private val kvCacheMethodState: AtomicReference<KvCacheMethodResolution> = AtomicReference(
        KvCacheMethodResolution(
            requestedMethod = RuntimeGenerationConfig.default().kvCacheMethod,
            effectiveMethod = RuntimeGenerationConfig.default().kvCacheMethod,
            preset = RuntimeGenerationConfig.default().kvCacheMethodPreset,
        ),
    )

    init {
        ensureLifecycleDispatcherStarted()
    }

    override fun isReady(): Boolean {
        ensureRuntimeInitialized()
        return runtimeReady
    }

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
        updateKvCacheMethodState(resolveKvCacheMethod(config.kvCacheMethod, config.kvCacheMethodPreset))
        if (usingFallback) {
            fallbackBridge.setRuntimeGenerationConfig(config)
            return
        }
        if (!runtimeReady) {
            return
        }
        runCatching {
            nativeApi.setSamplingConfig(
                temperature = config.sampling.temperature,
                topK = config.sampling.topK,
                topP = config.sampling.topP,
                minP = config.sampling.minP,
                typicalP = config.sampling.typicalP,
                repeatLastN = config.sampling.repeatLastN,
                repeatPenalty = config.sampling.repeatPenalty,
                frequencyPenalty = config.sampling.frequencyPenalty,
                presencePenalty = config.sampling.presencePenalty,
                mirostat = config.sampling.mirostat,
                mirostatTau = config.sampling.mirostatTau,
                mirostatEta = config.sampling.mirostatEta,
                xtcThreshold = config.sampling.xtcThreshold,
                xtcProbability = config.sampling.xtcProbability,
                seed = config.sampling.seed,
                nKeep = config.nKeep,
            )
        }.onSuccess {
            clearBridgeError()
        }.onFailure { error ->
            recordBridgeError("JNI_SET_SAMPLING_CONFIG_EXCEPTION", error)
        }
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun modelLayerCount(): Int? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.modelLayerCount()
        }
        if (!runtimeReady) {
            return null
        }
        return runCatching { nativeApi.modelLayerCount() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_MODEL_LAYER_COUNT_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    override fun modelSizeBytes(): Long? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.modelSizeBytes()
        }
        if (!runtimeReady) {
            return null
        }
        return runCatching { nativeApi.modelSizeBytes() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_MODEL_SIZE_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    override fun estimateMaxGpuLayers(nCtx: Int): Int? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.estimateMaxGpuLayers(nCtx)
        }
        if (!runtimeReady || nCtx <= 0) {
            return null
        }
        return runCatching { nativeApi.estimateMaxGpuLayers(nCtx) }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_ESTIMATE_GPU_LAYERS_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it >= 0 }
    }

    override fun actualGpuLayers(): Int? {
        return if (usingFallback) {
            fallbackBridge.actualGpuLayers()
        } else {
            lastAppliedGpuLayers
        }
    }

    override fun actualDraftGpuLayers(): Int? {
        return if (usingFallback) {
            fallbackBridge.actualDraftGpuLayers()
        } else {
            lastAppliedDraftGpuLayers
        }
    }

    override fun lastGpuLoadRetryCount(): Int? {
        return if (usingFallback) {
            fallbackBridge.lastGpuLoadRetryCount()
        } else {
            lastGpuLoadRetryCount
        }
    }

    override fun currentRssMb(): Double? {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) {
            return null
        }
        return runCatching { nativeApi.currentRssMb() }
            .onFailure { error -> recordBridgeError("JNI_CURRENT_RSS_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { !it.isNaN() && it >= 0.0 }
    }

    override fun isRuntimeReleased(): Boolean {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) {
            return true
        }
        return runCatching { nativeApi.isRuntimeReleased() }
            .onFailure { error -> recordBridgeError("JNI_RUNTIME_RELEASE_STATUS_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun supportsGpuOffload(): Boolean {
        if (!gpuOffloadAllowed) {
            return false
        }
        ensureRuntimeInitialized()
        return if (usingFallback) {
            fallbackBridge.supportsGpuOffload()
        } else {
            runCatching { nativeApi.supportsGpuOffload() }
                .onSuccess { clearBridgeError() }
                .onFailure { error -> recordBridgeError("JNI_GPU_SUPPORT_EXCEPTION", error) }
                .getOrElse { false }
        }
    }

    override fun setBackendProfile(profile: String) {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) {
            return
        }
        runCatching { nativeApi.setBackendProfile(profile) }
    }

    override fun backendDiagnosticsJson(): String? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return null
        }
        return runCatching { nativeApi.backendDiagnosticsJson() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_GPU_DIAGNOSTICS_EXCEPTION", error) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun prefixCacheDiagnosticsLine(): String? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return null
        }
        return runCatching { nativeApi.prefixCacheDiagnosticsLine() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_PREFIX_CACHE_DIAGNOSTICS_EXCEPTION", error) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun saveSessionCache(filePath: String): Boolean {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) return false
        return runCatching { nativeApi.saveSessionCache(filePath) }
            .onFailure { error -> recordBridgeError("JNI_SESSION_CACHE_SAVE_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun loadSessionCache(filePath: String): Boolean {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) return false
        return runCatching { nativeApi.loadSessionCache(filePath) }
            .onFailure { error -> recordBridgeError("JNI_SESSION_CACHE_LOAD_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) return false
        return runCatching { nativeApi.initMultimodal(mmProjPath, useGpu, imageMaxTokens) }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_INIT_MULTIMODAL_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun freeMultimodal() {
        if (usingFallback || !runtimeReady) return
        runCatching { nativeApi.freeMultimodal() }
    }

    override fun isMultimodalEnabled(): Boolean {
        if (usingFallback || !runtimeReady) return false
        return runCatching { nativeApi.isMultimodalEnabled() }
            .getOrElse { false }
    }

    override fun generateWithImages(
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val startedMs = System.currentTimeMillis()
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return currentKvMethodGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "RUNTIME_UNAVAILABLE",
            )
        }
        if (usingFallback) {
            return fallbackBridge.generateWithImages(requestId, prompt, imagePaths, maxTokens, onToken)
        }
        var tokenCount = 0
        var firstTokenMs = -1L
        activeRequestId = requestId
        return try {
            val status = runCatching {
                nativeApi.generateStreamWithImages(
                    requestId = requestId,
                    prompt = prompt,
                    imagePaths = imagePaths.toTypedArray(),
                    maxTokens = maxTokens,
                    onToken = NativeApi.TokenCallback { token ->
                        if (firstTokenMs < 0L) {
                            firstTokenMs = System.currentTimeMillis() - startedMs
                        }
                        tokenCount += 1
                        onToken(token)
                    },
                )
            }.onFailure { error ->
                recordBridgeError("JNI_GENERATE_MULTIMODAL_EXCEPTION", error)
            }.getOrElse {
                return currentKvMethodGenerationResult(
                    finishReason = GenerationFinishReason.ERROR,
                    tokenCount = tokenCount,
                    firstTokenMs = firstTokenMs,
                    totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                    cancelled = false,
                    errorCode = "JNI_GENERATE_MULTIMODAL_EXCEPTION",
                )
            }
            val finishReason = status.finishReason
            val statusErrorCode = status.errorCode?.trim()?.takeIf { it.isNotEmpty() }
            if (
                finishReason == GenerationFinishReason.ERROR ||
                finishReason == GenerationFinishReason.CALLBACK_ERROR
            ) {
                recordBridgeError(statusErrorCode ?: "JNI_MULTIMODAL_STREAM_ERROR", "requestId=$requestId")
            } else if (finishReason != GenerationFinishReason.CANCELLED) {
                clearBridgeError()
            }
            currentKvMethodGenerationResult(
                finishReason = finishReason,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = finishReason == GenerationFinishReason.CANCELLED,
                prefillMs = if (firstTokenMs >= 0) firstTokenMs else null,
                decodeMs = if (firstTokenMs >= 0) {
                    ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(0L)
                } else {
                    null
                },
                tokensPerSec = if (tokenCount > 0 && firstTokenMs >= 0) {
                    val decodeMs = ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(1L)
                    tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
                } else {
                    null
                },
                errorCode = statusErrorCode,
            )
        } finally {
            activeRequestId = null
        }
    }

    fun generateSyncProbe(
        prompt: String,
        maxTokens: Int,
        cachePolicy: CachePolicy = CachePolicy.OFF,
    ): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady || usingFallback) {
            return false
        }
        return runCatching {
            nativeApi.generate(
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = null,
                cachePolicyCode = cachePolicy.code,
            )
            true
        }.onSuccess {
            clearBridgeError()
        }.onFailure { error ->
            recordBridgeError("JNI_GENERATE_SYNC_EXCEPTION", error)
        }.getOrElse { false }
    }

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        return loadModel(modelId, modelPath, ModelLoadOptions())
    }

    override fun loadModel(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean {
        val token = synchronized(lifecycleLock) {
            activeLoadToken += 1L
            activeLoadToken
        }
        lastSuccessfulLoadDetail = null
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADING,
                modelId = modelId,
                modelVersion = options.modelVersion,
                loadingDetail = defaultLoadingDetail(ModelLoadingStage.INITIALIZING_RUNTIME),
                loadingStage = ModelLoadingStage.INITIALIZING_RUNTIME,
                loadingProgress = 0.0f,
            ),
        )
        val loaded = synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                recordBridgeError("LOAD_CANCELLED_NEWER_REQUEST", "modelId=$modelId")
                false
            } else {
                loadModelUnchecked(modelId = modelId, modelPath = modelPath, options = options, loadToken = token)
            }
        }
        synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                if (loaded) {
                    unloadModelUnchecked()
                }
                emitLifecycleFailure(
                    modelId = modelId,
                    modelVersion = options.modelVersion,
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "modelId=$modelId",
                )
                return false
            }
            if (loaded) {
                loadedModel = LoadedModelInfo(
                    modelId = modelId,
                    modelPath = modelPath?.trim()?.takeIf { it.isNotEmpty() },
                    modelVersion = options.modelVersion,
                )
                emitLifecycleEvent(
                    ModelLifecycleEvent(
                        state = ModelLifecycleState.LOADED,
                        modelId = modelId,
                        modelVersion = options.modelVersion,
                        loadingDetail = lastSuccessfulLoadDetail ?: defaultLoadingDetail(ModelLoadingStage.COMPLETED),
                        loadingStage = ModelLoadingStage.COMPLETED,
                        loadingProgress = 1.0f,
                    ),
                )
            } else {
                loadedModel = null
                emitLifecycleFailure(
                    modelId = modelId,
                    modelVersion = options.modelVersion,
                    code = mapBridgeErrorToLifecycleCode(lastBridgeError?.code),
                    detail = lastBridgeError?.detail,
                )
            }
        }
        return loaded
    }

    private fun loadModelUnchecked(
        modelId: String,
        modelPath: String?,
        options: ModelLoadOptions,
        loadToken: Long,
    ): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            recordBridgeError("RUNTIME_NOT_READY", "Runtime is not initialized.")
            return false
        }
        resetAppliedGpuStats()
        val validation = ModelCatalog.validateBridgeLoad(
            modelId = modelId,
            modelPath = modelPath,
            supportedModels = supportedModels,
        )
        if (!validation.accepted) {
            recordBridgeError(validation.code ?: "MODEL_INVALID", validation.detail)
            return false
        }
        val normalizedModelPath = validation.normalizedModelPath.orEmpty()
        validateRuntimeFormatSupport(
            modelId = modelId,
            modelPath = normalizedModelPath,
            modelVersion = options.modelVersion,
        )?.let { incompatibility ->
            recordBridgeError(incompatibility.code, incompatibility.detail)
            return false
        }
        if (usingFallback) {
            fallbackBridge.setRuntimeGenerationConfig(runtimeGenerationConfig)
            val loaded = fallbackBridge.loadModel(
                modelId = modelId,
                modelPath = validation.normalizedModelPath,
                options = options,
            )
            if (loaded) {
                updateAppliedGpuStats(
                    gpuLayers = fallbackBridge.actualGpuLayers(),
                    draftGpuLayers = fallbackBridge.actualDraftGpuLayers(),
                    retryCount = fallbackBridge.lastGpuLoadRetryCount(),
                )
            }
            return loaded
        }
        val baseConfig = runtimeGenerationConfig
        val openClQualification = resolveOpenClQualificationSnapshot()
        validateSpecializedFormatDeviceSupport(
            modelId = modelId,
            modelPath = normalizedModelPath,
            modelVersion = options.modelVersion,
            qualification = openClQualification,
        )?.let { incompatibility ->
            recordBridgeError(incompatibility.code, incompatibility.detail)
            return false
        }
        var preferredBackend = resolvePreferredBackend(baseConfig)
        val openclQuantCompatibility = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = normalizedModelPath,
            modelId = modelId,
            modelVersion = options.modelVersion,
            qualification = openClQualification,
        )
        val openclQuantUnsupported = preferredBackend == GpuExecutionBackend.OPENCL &&
            openclQuantCompatibility == OpenClQuantCompatibility.UNSUPPORTED
        val openclQuantExperimental = preferredBackend == GpuExecutionBackend.OPENCL &&
            openclQuantCompatibility == OpenClQuantCompatibility.EXPERIMENTAL &&
            !baseConfig.speculativeEnabled
        if (openclQuantUnsupported) {
            preferredBackend = GpuExecutionBackend.CPU
        }
        applyPreferredBackend(preferredBackend)
        val backendSanitizedConfig = sanitizeRuntimeConfigForBackend(baseConfig, preferredBackend)
        val cpuOnlyBackend = backendSanitizedConfig.gpuBackend == GpuExecutionBackend.CPU
        val gpuEnabledRequested = backendSanitizedConfig.gpuEnabled && !cpuOnlyBackend
        val preferredBackendAvailable = preferredBackendAvailable(preferredBackend)
        val runtimeGpuSupported = gpuOffloadAllowed && gpuEnabledRequested && supportsGpuOffload()
        val gpuEnabledAndSupported = runtimeGpuSupported && preferredBackendAvailable
        val config = sanitizeRuntimeConfigForLoad(
            config = backendSanitizedConfig,
            modelId = modelId,
            modelPath = normalizedModelPath,
            modelVersion = options.modelVersion,
            cpuExecution = !gpuEnabledAndSupported,
        )
        val kvMethodResolution = resolveKvCacheMethod(
            requestedMethod = config.kvCacheMethod,
            preset = config.kvCacheMethodPreset,
        )
        updateKvCacheMethodState(kvMethodResolution)
        kvMethodResolution.demotionReason?.let { reason ->
            logBridge(
                "KV_METHOD_DEMOTE",
                "model=$normalizedModelPath|model_id=$modelId|requested=${kvMethodResolution.requestedMethod.name.lowercase()}|effective=${kvMethodResolution.effectiveMethod.name.lowercase()}|preset=${kvMethodResolution.preset.name.lowercase()}|reason=$reason",
            )
        }
        val requestedGpuLayers = if (gpuEnabledAndSupported) config.gpuLayers else 0
        val requestedDraftGpuLayers = if (gpuEnabledAndSupported && config.speculativeEnabled && config.speculativeDraftGpuLayers > 0) {
            config.speculativeDraftGpuLayers.coerceAtLeast(0)
        } else {
            0
        }
        val effectiveGpuLayers = if (openclQuantUnsupported || openclQuantExperimental) 0 else requestedGpuLayers
        val effectiveDraftGpuLayers = if (openclQuantUnsupported || openclQuantExperimental) 0 else requestedDraftGpuLayers
        if (preferredBackend == GpuExecutionBackend.OPENCL) {
            logBridge(
                "OPENCL_QUANT_POLICY",
                "model=$normalizedModelPath|model_id=$modelId|demoted_to_cpu=$openclQuantUnsupported|effective_gpu_layers=$effectiveGpuLayers|backend=${preferredBackend.name.lowercase()}|compatibility=${openclQuantCompatibility.name.lowercase()}|probe_status=${openClQualification.probeStatus.name.lowercase()}",
            )
        }

        val strictGpuOffload = options.strictGpuOffload && isStrictBackendSelection(config)
        val gpuRequested = gpuEnabledRequested && (
            config.gpuLayers > 0 ||
                (config.speculativeEnabled && config.speculativeDraftGpuLayers > 0)
            )
        if (gpuRequested && strictGpuOffload && !gpuEnabledAndSupported) {
            val detail = buildString {
                append("backend=${config.gpuBackend.name.lowercase()}")
                append("|runtime_support=$runtimeGpuSupported")
                append("|preferred_backend_available=$preferredBackendAvailable")
                append("|requested_layers=${config.gpuLayers}")
                append("|requested_draft_layers=${if (config.speculativeEnabled) config.speculativeDraftGpuLayers else 0}")
            }
            recordBridgeError("GPU_BACKEND_UNAVAILABLE", detail)
            return false
        }
        val allowGpuBackoff = gpuEnabledAndSupported &&
            !strictGpuOffload &&
            effectiveGpuLayers > 0
        var lastProgress = -1.0f
        var lastProgressAtMs = 0L
        val progressCallback = NativeApi.ProgressCallback { rawProgress ->
            val clamped = rawProgress.coerceIn(0.0f, 1.0f)
            val now = System.currentTimeMillis()
            val shouldEmit = lastProgress < 0.0f ||
                clamped >= 1.0f ||
                (clamped - lastProgress) >= LOAD_PROGRESS_EMIT_STEP ||
                (now - lastProgressAtMs) >= LOAD_PROGRESS_EMIT_INTERVAL_MS
            if (shouldEmit) {
                lastProgress = clamped
                lastProgressAtMs = now
                emitLifecycleEvent(
                    ModelLifecycleEvent(
                        state = ModelLifecycleState.LOADING,
                        modelId = modelId,
                        modelVersion = options.modelVersion,
                        loadingDetail = defaultLoadingDetail(ModelLoadingStage.LOADING_MODEL),
                        loadingStage = ModelLoadingStage.LOADING_MODEL,
                        loadingProgress = clamped,
                    ),
                )
            }
            activeLoadToken == loadToken
        }
        val loadAttempts = buildGpuLoadAttempts(
            targetGpuLayers = effectiveGpuLayers,
            draftGpuLayers = effectiveDraftGpuLayers,
        )
        var finalBridgeError: BridgeError? = null
        var finalThrowable: Throwable? = null

        loadAttempts.forEachIndexed { attemptIndex, attempt ->
            val loadAttempt = runCatching {
                nativeApi.loadModel(
                    modelId = modelId,
                    modelPath = normalizedModelPath,
                    nThreads = config.nThreads,
                    nThreadsBatch = config.nThreadsBatch,
                    nBatch = config.nBatch,
                    nUbatch = config.nUbatch,
                    nCtx = config.nCtx,
                    nGpuLayers = attempt.targetGpuLayers,
                    flashAttnCode = config.flashAttnMode.code,
                    kvCacheMethodCode = kvMethodResolution.effectiveMethod.code,
                    kvCacheMethodPresetCode = kvMethodResolution.preset.code,
                    temperature = config.sampling.temperature,
                    topK = config.sampling.topK,
                    topP = config.sampling.topP,
                    minP = config.sampling.minP,
                    typicalP = config.sampling.typicalP,
                    repeatLastN = config.sampling.repeatLastN,
                    repeatPenalty = config.sampling.repeatPenalty,
                    frequencyPenalty = config.sampling.frequencyPenalty,
                    presencePenalty = config.sampling.presencePenalty,
                    mirostat = config.sampling.mirostat,
                    mirostatTau = config.sampling.mirostatTau,
                    mirostatEta = config.sampling.mirostatEta,
                    xtcThreshold = config.sampling.xtcThreshold,
                    xtcProbability = config.sampling.xtcProbability,
                    seed = config.sampling.seed,
                    speculativeEnabled = config.speculativeEnabled,
                    speculativeDraftModelPath = config.speculativeDraftModelPath,
                    speculativeMaxDraftTokens = config.speculativeMaxDraftTokens,
                    speculativeMinDraftTokens = config.speculativeMinDraftTokens,
                    speculativeDraftGpuLayers = attempt.draftGpuLayers,
                    useMmap = config.useMmap,
                    useMlock = config.useMlock,
                    nKeep = config.nKeep,
                    progressCallback = progressCallback,
                )
            }
            if (loadAttempt.getOrNull() == true) {
                clearBridgeError()
                updateAppliedGpuStats(
                    gpuLayers = attempt.targetGpuLayers,
                    draftGpuLayers = attempt.draftGpuLayers,
                    retryCount = attemptIndex,
                )
                lastSuccessfulLoadDetail = when {
                    openclQuantUnsupported && gpuRequested ->
                        "Model loaded on CPU because this quantization is not release-qualified for GPU."
                    attemptIndex > 0 ->
                        "Model loaded with reduced GPU acceleration."
                    else -> null
                }
                return true
            }

            finalThrowable = loadAttempt.exceptionOrNull()
            finalBridgeError = resolveLoadAttemptError(
                throwable = finalThrowable,
                modelId = modelId,
                backend = preferredBackend,
                gpuLayers = attempt.targetGpuLayers,
                draftGpuLayers = attempt.draftGpuLayers,
            )
            val canRetry = allowGpuBackoff &&
                attemptIndex < loadAttempts.lastIndex &&
                isRetryableGpuLoadFailure(finalBridgeError)
            if (canRetry) {
                logBridge(
                    "GPU_LOAD_BACKOFF",
                    "model=$modelId|attempt=${attemptIndex + 1}|gpu_layers=${attempt.targetGpuLayers}|draft_gpu_layers=${attempt.draftGpuLayers}|error=${finalBridgeError?.code.orEmpty()}",
                )
                return@forEachIndexed
            }
            if (finalThrowable != null) {
                recordBridgeError("JNI_LOAD_EXCEPTION", finalThrowable!!)
            } else if (finalBridgeError != null) {
                recordBridgeError(finalBridgeError!!.code, finalBridgeError!!.detail)
            } else {
                recordBridgeError("JNI_LOAD_FAILED", "modelId=$modelId")
            }
            return false
        }
        if (finalThrowable != null) {
            recordBridgeError("JNI_LOAD_EXCEPTION", finalThrowable!!)
        } else if (finalBridgeError != null) {
            recordBridgeError(finalBridgeError!!.code, finalBridgeError!!.detail)
        } else {
            recordBridgeError("JNI_LOAD_FAILED", "modelId=$modelId")
        }
        return false
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val startedMs = System.currentTimeMillis()
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return currentKvMethodGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "RUNTIME_UNAVAILABLE",
            )
        }
        if (usingFallback) {
            return fallbackBridge.generate(
                requestId = requestId,
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = cacheKey,
                cachePolicy = cachePolicy,
                onToken = onToken,
            )
        }
        var tokenCount = 0
        var firstTokenMs = -1L
        var peakRssMb: Double? = null
        activeRequestId = requestId
        return try {
            logBridge(
                "PREFIX_CACHE_REQUEST",
                "requestId=$requestId|cacheKeyLen=${cacheKey?.length ?: 0}|cacheKeyPrefix=${summarizeCacheKey(cacheKey)}|policy=${cachePolicy.name.lowercase()}",
            )
            val status = runCatching {
                nativeApi.generateStream(
                    requestId = requestId,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    cacheKey = cacheKey,
                    cachePolicyCode = cachePolicy.code,
                    onToken = NativeApi.TokenCallback { token ->
                        if (firstTokenMs < 0L) {
                            firstTokenMs = System.currentTimeMillis() - startedMs
                        }
                        tokenCount += 1
                        onToken(token)
                    },
                )
            }.onFailure { error ->
                recordBridgeError("JNI_GENERATE_EXCEPTION", error)
            }.getOrElse {
                return currentKvMethodGenerationResult(
                    finishReason = GenerationFinishReason.ERROR,
                    tokenCount = tokenCount,
                    firstTokenMs = firstTokenMs,
                    totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                    cancelled = false,
                    errorCode = "JNI_GENERATE_EXCEPTION",
                )
            }
            peakRssMb = nativeApi.peakRssMb()
            val finishReason = status.finishReason
            val statusErrorCode = status.errorCode?.trim()?.takeIf { it.isNotEmpty() }
            if (
                finishReason == GenerationFinishReason.ERROR ||
                finishReason == GenerationFinishReason.CALLBACK_ERROR ||
                finishReason == GenerationFinishReason.UTF8_STREAM_ERROR
            ) {
                recordBridgeError(
                    code = statusErrorCode ?: "JNI_STREAM_ERROR",
                    detail = "requestId=$requestId",
                )
            } else if (finishReason != GenerationFinishReason.CANCELLED) {
                clearBridgeError()
            }
            currentKvMethodGenerationResult(
                finishReason = finishReason,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = finishReason == GenerationFinishReason.CANCELLED,
                prefillMs = if (firstTokenMs >= 0) firstTokenMs else null,
                decodeMs = if (firstTokenMs >= 0) {
                    ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(0L)
                } else {
                    null
                },
                tokensPerSec = if (tokenCount > 0 && firstTokenMs >= 0) {
                    val decodeMs = ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(1L)
                    tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
                } else {
                    null
                },
                peakRssMb = peakRssMb,
                errorCode = statusErrorCode,
            )
        } finally {
            activeRequestId = null
        }
    }

    override fun unloadModel() {
        offloadModel(reason = "legacy_unload")
    }

    override fun offloadModel(reason: String): Boolean {
        val token = synchronized(lifecycleLock) {
            activeLoadToken += 1L
            activeLoadToken
        }
        val model = loadedModel
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.OFFLOADING,
                modelId = model?.modelId,
                modelVersion = model?.modelVersion,
            ),
        )
        synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                emitLifecycleFailure(
                    modelId = model?.modelId,
                    modelVersion = model?.modelVersion,
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "reason=$reason",
                )
                return false
            }
            unloadModelUnchecked()
            loadedModel = null
            emitLifecycleEvent(
                ModelLifecycleEvent(
                    state = ModelLifecycleState.UNLOADED,
                    modelId = model?.modelId,
                    modelVersion = model?.modelVersion,
                ),
            )
        }
        return true
    }

    private fun unloadModelUnchecked() {
        resetAppliedGpuStats()
        if (!runtimeReady) {
            return
        }
        if (usingFallback) {
            fallbackBridge.unloadModel()
            return
        }
        runCatching { nativeApi.unloadModel() }
    }

    override fun cancelGeneration(): Boolean {
        if (!runtimeReady) {
            recordBridgeError("RUNTIME_NOT_READY", "Cancel requested while runtime not ready.")
            return false
        }
        if (usingFallback) {
            return fallbackBridge.cancelGeneration()
        }
        return runCatching { nativeApi.cancelGeneration() }
            .onSuccess { cancelled ->
                if (cancelled) {
                    clearBridgeError()
                } else {
                    recordBridgeError("JNI_CANCEL_RETURNED_FALSE", "native cancel returned false")
                }
            }
            .onFailure { error -> recordBridgeError("JNI_CANCEL_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun cancelGeneration(requestId: String): Boolean {
        if (usingFallback) {
            return fallbackBridge.cancelGeneration(requestId)
        }
        return if (activeRequestId == requestId) {
            cancelGeneration()
        } else {
            false
        }
    }

    override fun runtimeBackend(): RuntimeBackend {
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return RuntimeBackend.UNAVAILABLE
        }
        return if (usingFallback) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.NATIVE_JNI
    }

    override fun lastError(): BridgeError? = lastBridgeError

    override fun getLoadedModel(): LoadedModelInfo? = loadedModel

    override fun currentModelLifecycleState(): ModelLifecycleEvent = currentLifecycleEvent

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        ensureLifecycleDispatcherStarted()
        val id = synchronized(lifecycleObserverLock) {
            val observerId = nextObserverId++
            lifecycleObservers[observerId] = listener
            observerId
        }
        listener(currentLifecycleEvent)
        return AutoCloseable {
            synchronized(lifecycleObserverLock) {
                lifecycleObservers.remove(id)
            }
        }
    }

    private fun ensureRuntimeInitialized() {
        if (initialized) {
            return
        }
        initialized = true
        val nativeReady = initializeNativeRuntime()
        if (nativeReady) {
            runtimeReady = true
            usingFallback = false
            clearBridgeError()
            return
        }
        if (lastBridgeError == null) {
            recordBridgeError("JNI_INIT_FAILED", "native initialize returned false")
        }

        if (fallbackEnabled && fallbackBridge.isReady()) {
            runtimeReady = true
            usingFallback = true
            recordBridgeError("ADB_FALLBACK_ACTIVE", "Native runtime unavailable; using adb fallback.")
            return
        }

        runtimeReady = false
        usingFallback = false
        if (lastBridgeError == null) {
            recordBridgeError("RUNTIME_UNAVAILABLE", "Native and fallback runtimes unavailable.")
        }
    }

    private fun initializeNativeRuntime(): Boolean {
        val candidates = resolveNativeLibraryCandidates()
        val candidateSummary = candidates.joinToString(separator = ",")
        for (candidate in candidates) {
            val loaded = runCatching {
                libraryLoader(candidate)
                true
            }.onFailure { error ->
                logBridge("JNI_LIBRARY_LOAD_FAILED", "library=$candidate|reason=${error.message ?: error::class.simpleName}")
            }.getOrElse { false }
            if (!loaded) {
                continue
            }

            val initializedRuntime = runCatching { nativeApi.initialize() }
                .onFailure { error ->
                    recordBridgeError("JNI_LIBRARY_OR_INIT_EXCEPTION", error)
                }
                .getOrElse { false }
            if (initializedRuntime) {
                loadedNativeLibraryName = candidate
                logBridge("JNI_LIBRARY_SELECTED", "library=$candidate|candidates=$candidateSummary")
                return true
            }
            recordBridgeError("JNI_INIT_FAILED", "native initialize returned false|library=$candidate")
            return false
        }
        if (lastBridgeError == null) {
            recordBridgeError("JNI_LIBRARY_OR_INIT_EXCEPTION", "Unable to load native library from: $candidateSummary")
        }
        return false
    }

    private fun resolveNativeLibraryCandidates(): List<String> {
        if (libraryName != DEFAULT_NATIVE_LIBRARY_NAME) {
            return listOf(libraryName)
        }
        if (!optimizedNativeLibrariesEnabled) {
            return listOf(DEFAULT_NATIVE_LIBRARY_NAME, LIBRARY_V8)
        }
        val features = detectCpuFeatures()
        val candidates = linkedSetOf<String>()
        if (features.hasDotProd && features.hasI8mm) {
            candidates += LIBRARY_V8_2_DOTPROD_I8MM
        }
        if (features.hasDotProd) {
            candidates += LIBRARY_V8_2_DOTPROD
        }
        if (features.hasI8mm) {
            candidates += LIBRARY_V8_2_I8MM
        }
        if (features.hasFp16) {
            candidates += LIBRARY_V8_2
        }
        candidates += DEFAULT_NATIVE_LIBRARY_NAME
        candidates += LIBRARY_V8
        return candidates.toList()
    }

    private fun clearBridgeError() {
        lastBridgeError = null
    }

    private fun resetAppliedGpuStats() {
        lastAppliedGpuLayers = null
        lastAppliedDraftGpuLayers = null
        lastGpuLoadRetryCount = null
    }

    private fun updateAppliedGpuStats(gpuLayers: Int?, draftGpuLayers: Int?, retryCount: Int?) {
        lastAppliedGpuLayers = gpuLayers
        lastAppliedDraftGpuLayers = draftGpuLayers
        lastGpuLoadRetryCount = retryCount
    }

    private fun recordBridgeError(code: String, error: Throwable) {
        lastBridgeError = BridgeError(
            code = code,
            detail = error.message ?: error::class.simpleName,
        )
    }

    private fun recordBridgeError(code: String, detail: String?) {
        lastBridgeError = BridgeError(code = code, detail = detail)
    }

    private fun emitLifecycleFailure(
        modelId: String?,
        modelVersion: String?,
        code: ModelLifecycleErrorCode,
        detail: String?,
    ) {
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.FAILED,
                modelId = modelId,
                modelVersion = modelVersion,
                error = ModelLifecycleError(code = code, detail = detail),
            ),
        )
    }

    private fun emitLifecycleEvent(event: ModelLifecycleEvent) {
        currentLifecycleEvent = event
        ensureLifecycleDispatcherStarted()
        synchronized(lifecycleDispatchLock) {
            lifecycleDispatchQueue.addLast(event)
            lifecycleDispatchLock.notifyAll()
        }
    }

    private fun ensureLifecycleDispatcherStarted() {
        if (lifecycleDispatcherStarted) {
            return
        }
        lifecycleDispatcherStarted = true
        thread(
            start = true,
            isDaemon = true,
            name = "pocketgpt-lifecycle-dispatcher",
        ) {
            while (true) {
                val nextEvent = synchronized(lifecycleDispatchLock) {
                    while (lifecycleDispatchQueue.isEmpty()) {
                        lifecycleDispatchLock.wait()
                    }
                    lifecycleDispatchQueue.removeFirst()
                }
                val listeners = synchronized(lifecycleObserverLock) {
                    lifecycleObservers.values.toList()
                }
                listeners.forEach { listener ->
                    runCatching { listener(nextEvent) }
                }
            }
        }
    }

    private fun mapBridgeErrorToLifecycleCode(errorCode: String?): ModelLifecycleErrorCode {
        val normalized = errorCode?.trim()?.uppercase().orEmpty()
        return when {
            normalized.contains("MODEL_PATH") ||
                normalized.contains("MODEL_INVALID") ||
                normalized.contains("MODEL_UNAVAILABLE") ||
                normalized.contains("PROVISIONING_IMPORT") ||
                normalized.contains("MODEL_FILE")
            -> ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE

            normalized.contains("RUNTIME_INCOMPATIBLE") ||
                normalized.contains("COMPATIBILITY") ||
                normalized.contains("PROVENANCE") ||
                normalized.contains("CHECKSUM")
            -> ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE

            normalized.contains("OUT_OF_MEMORY") ||
                normalized.contains("OOM") ||
                normalized.contains("ENOMEM")
            -> ModelLifecycleErrorCode.OUT_OF_MEMORY

            normalized.contains("RUNTIME_NOT_READY") ||
                normalized.contains("JNI_INIT") ||
                normalized.contains("RUNTIME_UNAVAILABLE") ||
                normalized.contains("JNI_LIBRARY") ||
                normalized.contains("BACKEND")
            -> ModelLifecycleErrorCode.BACKEND_INIT_FAILED

            normalized.contains("BUSY")
            -> ModelLifecycleErrorCode.BUSY_GENERATION

            normalized.contains("CANCELLED_NEWER_REQUEST")
            -> ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST

            else -> ModelLifecycleErrorCode.UNKNOWN
        }
    }

    private fun applyPreferredBackend(backend: GpuExecutionBackend) {
        if (usingFallback || !runtimeReady) {
            return
        }
        val profile = when (backend) {
            GpuExecutionBackend.AUTO -> "auto"
            GpuExecutionBackend.HEXAGON -> "hexagon"
            GpuExecutionBackend.OPENCL -> "opencl"
            GpuExecutionBackend.CPU -> "cpu"
        }
        runCatching { nativeApi.setBackendProfile(profile) }
    }

    private fun resolvePreferredBackend(config: RuntimeGenerationConfig): GpuExecutionBackend {
        return when {
            config.gpuEnabled && config.gpuBackend == GpuExecutionBackend.AUTO -> GpuExecutionBackend.OPENCL
            else -> config.gpuBackend
        }
    }

    private fun sanitizeRuntimeConfigForBackend(
        config: RuntimeGenerationConfig,
        preferredBackend: GpuExecutionBackend,
    ): RuntimeGenerationConfig {
        if (!config.gpuEnabled || preferredBackend != GpuExecutionBackend.OPENCL) {
            return config
        }
        return config.copy(
            flashAttnMode = FlashAttnMode.OFF,
            kvCacheMethodPreset = KvCacheMethodPreset.SAFE,
        )
    }

    private fun sanitizeRuntimeConfigForLoad(
        config: RuntimeGenerationConfig,
        modelId: String,
        modelPath: String,
        modelVersion: String?,
        cpuExecution: Boolean,
    ): RuntimeGenerationConfig {
        if (!cpuExecution || requiredRuntimeFormat(modelId, modelPath, modelVersion) != "q1_0_g128") {
            return config
        }
        val sanitized = config.copy(
            flashAttnMode = FlashAttnMode.OFF,
            kvCacheMethodPreset = KvCacheMethodPreset.SAFE,
            nBatch = minOf(config.nBatch, BONSAI_CPU_SAFE_BATCH),
            nUbatch = minOf(config.nUbatch, BONSAI_CPU_SAFE_BATCH),
        )
        if (sanitized != config) {
            logBridge(
                "BONSAI_CPU_SAFE_CONFIG",
                "model_id=$modelId|flash_attn=${sanitized.flashAttnMode.name.lowercase()}|kv_preset=${sanitized.kvCacheMethodPreset.name.lowercase()}|n_batch=${sanitized.nBatch}|n_ubatch=${sanitized.nUbatch}",
            )
        }
        return sanitized
    }

    private fun preferredBackendAvailable(backend: GpuExecutionBackend): Boolean {
        if (backend == GpuExecutionBackend.AUTO || backend == GpuExecutionBackend.CPU) {
            return true
        }
        val diagnostics = backendDiagnosticsJson().orEmpty()
        if (diagnostics.isBlank()) {
            // If diagnostics are unavailable, avoid false negatives and rely on runtime probing.
            return true
        }
        val openclDeviceCount = parseBackendDeviceCount(diagnostics, OPENCL_DEVICE_COUNT_REGEX)
        val hexagonDeviceCount = parseBackendDeviceCount(diagnostics, HEXAGON_DEVICE_COUNT_REGEX)
        val compiledBackends = parseCompiledBackends(diagnostics)
        return when (backend) {
            GpuExecutionBackend.HEXAGON -> {
                if (hexagonDeviceCount != null) {
                    return hexagonDeviceCount > 0
                }
                if (compiledBackends.isNotEmpty()) {
                    compiledBackends.contains("hexagon")
                } else {
                    diagnostics.lowercase().contains("hexagon")
                }
            }
            GpuExecutionBackend.OPENCL -> {
                if (openclDeviceCount != null) {
                    return openclDeviceCount > 0
                }
                if (compiledBackends.isNotEmpty()) {
                    compiledBackends.contains("opencl")
                } else {
                    diagnostics.lowercase().contains("opencl")
                }
            }
            else -> true
        }
    }

    private fun parseCompiledBackends(diagnosticsJson: String): Set<String> {
        val match = COMPILED_BACKEND_REGEX.find(diagnosticsJson) ?: return emptySet()
        val raw = match.groupValues.getOrNull(1).orEmpty()
        if (raw.isBlank()) {
            return emptySet()
        }
        return raw.lowercase()
            .split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun parseBackendDeviceCount(
        diagnosticsJson: String,
        regex: Regex,
    ): Int? {
        val raw = regex.find(diagnosticsJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null
        return raw.toIntOrNull()
    }

    private fun parseNativeBackendError(diagnosticsJson: String): Pair<String, String?>? {
        if (diagnosticsJson.isBlank()) {
            return null
        }
        val code = LAST_ERROR_CODE_REGEX.find(diagnosticsJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val detail = LAST_ERROR_DETAIL_REGEX.find(diagnosticsJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return code to detail
    }

    private fun parseRuntimeSupportFlag(
        diagnosticsJson: String,
        regex: Regex,
    ): Boolean? {
        if (diagnosticsJson.isBlank()) {
            return null
        }
        return regex.find(diagnosticsJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.lowercase()
            ?.let { value ->
                when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
    }

    private fun validateRuntimeFormatSupport(
        modelId: String,
        modelPath: String,
        modelVersion: String?,
    ): BridgeError? {
        val requiredFormat = requiredRuntimeFormat(
            modelId = modelId,
            modelPath = modelPath,
            modelVersion = modelVersion,
        ) ?: return null
        val diagnostics = backendDiagnosticsJson().orEmpty()
        val supported = when (requiredFormat) {
            "q1_0" -> parseRuntimeSupportFlag(diagnostics, SUPPORTS_Q1_0_REGEX)
            "q1_0_g128" -> parseRuntimeSupportFlag(diagnostics, SUPPORTS_Q1_0_G128_REGEX)
            else -> null
        }
        return if (supported == true) {
            null
        } else {
            BridgeError(
                code = "RUNTIME_INCOMPATIBLE_MODEL_FORMAT",
                detail = buildString {
                    append("modelId=")
                    append(modelId)
                    append("|required_format=")
                    append(requiredFormat)
                    append("|runtime_support=")
                    append(supported?.toString() ?: "unknown")
                    append("|compiled_backends=")
                    append(parseCompiledBackends(diagnostics).joinToString(separator = ","))
                },
            )
        }
    }

    private fun resolveOpenClQualificationSnapshot(): OpenClQualificationSnapshot {
        val provided = runCatching { openClQualificationProvider?.invoke() }.getOrNull()
        return OpenClQualificationSnapshot(
            runtimeSupportsGpuOffload = supportsGpuOffload(),
            automaticOpenClEligible = provided?.automaticOpenClEligible,
            probeStatus = provided?.probeStatus ?: OpenClProbeQualificationStatus.UNKNOWN,
        )
    }

    private fun validateSpecializedFormatDeviceSupport(
        modelId: String,
        modelPath: String,
        modelVersion: String?,
        qualification: OpenClQualificationSnapshot,
    ): BridgeError? {
        val formatHint = ModelRuntimeFormats.infer(
            modelId = modelId,
            modelVersion = modelVersion,
            modelPath = modelPath,
        )
        if (!formatHint.requiresQualifiedGpu) {
            return null
        }
        if (supportsGpuOffload()) {
            if (qualification.automaticOpenClEligible != false) {
                return null
            }
            val diagnostics = backendDiagnosticsJson().orEmpty()
            return BridgeError(
                code = "RUNTIME_INCOMPATIBLE_MODEL_FORMAT",
                detail = buildString {
                    append("modelId=").append(modelId)
                    append("|required_format=").append(formatHint.normalizedToken ?: "specialized")
                    append("|required_backend=gpu")
                    append("|qualified_gpu_required=true")
                    append("|device_gpu_path_supported=false")
                    append("|compiled_backends=").append(parseCompiledBackends(diagnostics).joinToString(separator = ","))
                },
            )
        }
        val diagnostics = backendDiagnosticsJson().orEmpty()
        return BridgeError(
            code = "RUNTIME_INCOMPATIBLE_MODEL_FORMAT",
            detail = buildString {
                append("modelId=").append(modelId)
                append("|required_format=").append(formatHint.normalizedToken ?: "specialized")
                append("|required_backend=gpu")
                append("|qualified_gpu_required=true")
                append("|gpu_runtime_supported=false")
                append("|compiled_backends=").append(parseCompiledBackends(diagnostics).joinToString(separator = ","))
            },
        )
    }

    private fun requiredRuntimeFormat(
        modelId: String,
        modelPath: String,
        modelVersion: String?,
    ): String? {
        val formatHint = ModelRuntimeFormats.infer(
            modelId = modelId,
            modelVersion = modelVersion,
            modelPath = modelPath,
        )
        return when (formatHint.family) {
            ModelRuntimeFormatFamily.Q1_0_G128,
            ModelRuntimeFormatFamily.Q1_0,
            ModelRuntimeFormatFamily.UNKNOWN_SPECIALIZED,
            -> formatHint.normalizedToken
            ModelRuntimeFormatFamily.STANDARD -> null
        }
    }

    private fun resolveLoadAttemptError(
        throwable: Throwable?,
        modelId: String,
        backend: GpuExecutionBackend,
        gpuLayers: Int,
        draftGpuLayers: Int,
    ): BridgeError? {
        if (throwable != null) {
            return BridgeError(
                code = "JNI_LOAD_EXCEPTION",
                detail = throwable.message ?: throwable::class.simpleName,
            )
        }
        val nativeError = parseNativeBackendError(backendDiagnosticsJson().orEmpty())
        if (nativeError != null) {
            return BridgeError(nativeError.first, nativeError.second)
        }
        if (gpuLayers <= 0 && draftGpuLayers <= 0) {
            return BridgeError(
                code = "JNI_LOAD_FAILED",
                detail = "modelId=$modelId|backend=${backend.name.lowercase()}",
            )
        }
        return BridgeError(
            code = "GPU_BACKEND_LOAD_FAILED",
            detail = "modelId=$modelId|backend=${backend.name.lowercase()}|gpu_layers=$gpuLayers|draft_gpu_layers=$draftGpuLayers",
        )
    }

    private fun isRetryableGpuLoadFailure(error: BridgeError?): Boolean {
        val normalizedCode = error?.code.orEmpty().trim().lowercase()
        val normalizedDetail = error?.detail.orEmpty().trim().lowercase()
        val combined = "$normalizedCode|$normalizedDetail"
        if (combined.isBlank()) {
            return false
        }
        return RETRYABLE_GPU_LOAD_ERROR_TERMS.any { term -> combined.contains(term) }
    }

    private data class GpuLoadAttempt(
        val targetGpuLayers: Int,
        val draftGpuLayers: Int,
    )

    private fun buildGpuLoadAttempts(targetGpuLayers: Int, draftGpuLayers: Int): List<GpuLoadAttempt> {
        if (targetGpuLayers <= 0) {
            return listOf(
                GpuLoadAttempt(
                    targetGpuLayers = targetGpuLayers,
                    draftGpuLayers = draftGpuLayers.coerceAtLeast(0),
                ),
            )
        }
        val attempts = linkedSetOf<Int>()
        attempts += targetGpuLayers
        attempts += (targetGpuLayers * 3) / 4
        attempts += targetGpuLayers / 2
        attempts += targetGpuLayers / 4
        attempts += 0
        return attempts
            .map { candidate ->
                GpuLoadAttempt(
                    targetGpuLayers = candidate.coerceAtLeast(0),
                    draftGpuLayers = scaledDraftGpuLayers(
                        requestedTargetGpuLayers = targetGpuLayers,
                        requestedDraftGpuLayers = draftGpuLayers,
                        candidateTargetGpuLayers = candidate.coerceAtLeast(0),
                    ),
                )
            }
    }

    private fun scaledDraftGpuLayers(
        requestedTargetGpuLayers: Int,
        requestedDraftGpuLayers: Int,
        candidateTargetGpuLayers: Int,
    ): Int {
        if (requestedTargetGpuLayers <= 0 || requestedDraftGpuLayers <= 0 || candidateTargetGpuLayers <= 0) {
            return 0
        }
        val scaled = (requestedDraftGpuLayers.toDouble() * candidateTargetGpuLayers.toDouble() / requestedTargetGpuLayers.toDouble())
            .toInt()
        return scaled.coerceAtLeast(0).coerceAtMost(candidateTargetGpuLayers)
    }

    private fun isOpenClBackendActive(preferredBackend: GpuExecutionBackend): Boolean {
        val diagnostics = backendDiagnosticsJson().orEmpty()
        if (diagnostics.isBlank()) return false
        val openclCount = parseBackendDeviceCount(diagnostics, OPENCL_DEVICE_COUNT_REGEX) ?: 0
        val hexagonCount = parseBackendDeviceCount(diagnostics, HEXAGON_DEVICE_COUNT_REGEX) ?: 0
        return when (preferredBackend) {
            GpuExecutionBackend.OPENCL -> openclCount > 0
            GpuExecutionBackend.AUTO -> openclCount > 0 && hexagonCount == 0
            else -> false
        }
    }

    private fun logBridge(tag: String, message: String) {
        // Uses println as a cross-platform fallback; Android Logcat captures stdout.
        println("NativeJniLlamaCppBridge|$tag|$message")
    }

    private fun updateKvCacheMethodState(resolution: KvCacheMethodResolution) {
        kvCacheMethodState.set(resolution)
    }

    private fun currentKvMethodGenerationResult(
        finishReason: GenerationFinishReason,
        tokenCount: Int,
        firstTokenMs: Long,
        totalMs: Long,
        cancelled: Boolean,
        prefillMs: Long? = null,
        decodeMs: Long? = null,
        tokensPerSec: Double? = null,
        peakRssMb: Double? = null,
        errorCode: String? = null,
    ): GenerationResult {
        val kvState = kvCacheMethodState.get()
        return GenerationResult(
            finishReason = finishReason,
            tokenCount = tokenCount,
            firstTokenMs = firstTokenMs,
            totalMs = totalMs,
            cancelled = cancelled,
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            tokensPerSec = tokensPerSec,
            peakRssMb = peakRssMb,
            errorCode = errorCode,
            requestedKvCacheMethod = kvState.requestedMethod,
            effectiveKvCacheMethod = kvState.effectiveMethod,
            kvCacheMethodPreset = kvState.preset,
            kvCacheMethodDemotionReason = kvState.demotionReason,
        )
    }

    private fun summarizeCacheKey(cacheKey: String?): String {
        val normalized = cacheKey?.takeIf { it.isNotBlank() } ?: return "empty"
        return normalized.take(12)
    }

    private fun isStrictBackendSelection(config: RuntimeGenerationConfig): Boolean {
        if (!config.strictGpuOffload) {
            return false
        }
        return when (config.gpuBackend) {
            GpuExecutionBackend.HEXAGON,
            GpuExecutionBackend.OPENCL,
            -> true
            GpuExecutionBackend.AUTO,
            GpuExecutionBackend.CPU,
            -> false
        }
    }

    interface NativeApi {
        fun interface TokenCallback {
            fun onToken(token: String)
        }

        fun interface ProgressCallback {
            fun onProgress(progress: Float): Boolean
        }

        data class StreamStatus(
            val finishReason: GenerationFinishReason,
            val errorCode: String? = null,
        )

        fun initialize(): Boolean
        fun loadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nCtx: Int,
            nGpuLayers: Int,
            flashAttnCode: Int,
            kvCacheMethodCode: Int,
            kvCacheMethodPresetCode: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
            progressCallback: ProgressCallback? = null,
        ): Boolean
        fun setSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            nKeep: Int,
        )
        fun generateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicyCode: Int,
            onToken: TokenCallback,
        ): StreamStatus
        fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String
        fun cancelGeneration(): Boolean
        fun unloadModel()
        fun supportsGpuOffload(): Boolean
        fun modelLayerCount(): Int? = null
        fun modelSizeBytes(): Long? = null
        fun estimateMaxGpuLayers(nCtx: Int): Int? = null
        fun backendDiagnosticsJson(): String
        fun peakRssMb(): Double? = null
        fun currentRssMb(): Double? = null
        fun isRuntimeReleased(): Boolean = true
        fun prefixCacheDiagnosticsLine(): String? = null
        fun setBackendProfile(profile: String) {}
        fun saveSessionCache(filePath: String): Boolean = false
        fun loadSessionCache(filePath: String): Boolean = false
        fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean = false
        fun freeMultimodal() {}
        fun isMultimodalEnabled(): Boolean = false
        fun generateStreamWithImages(
            requestId: String,
            prompt: String,
            imagePaths: Array<String>,
            maxTokens: Int,
            onToken: TokenCallback,
        ): StreamStatus = StreamStatus(GenerationFinishReason.ERROR, "MULTIMODAL_NOT_SUPPORTED")
    }

    private class JniNativeApi : NativeApi {
        external fun nativeInitialize(): Boolean
        external fun nativeLoadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nCtx: Int,
            nGpuLayers: Int,
            flashAttnCode: Int,
            kvCacheMethodCode: Int,
            kvCacheMethodPresetCode: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
            progressCallback: NativeApi.ProgressCallback?,
        ): Boolean
        external fun nativeSetSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            nKeep: Int,
        )
        external fun nativeGenerateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicy: Int,
            callback: NativeApi.TokenCallback,
        ): Int
        external fun nativeGenerate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicy: Int): String
        external fun nativeCancelGeneration(): Boolean
        external fun nativeUnloadModel()
        external fun nativeSupportsGpuOffload(): Boolean
        external fun nativeModelLayerCount(): Int
        external fun nativeModelSizeBytes(): Long
        external fun nativeEstimateMaxGpuLayers(nCtx: Int): Int
        external fun nativeBackendDiagnosticsJson(): String
        external fun nativePeakRssMb(): Double
        external fun nativeCurrentRssMb(): Double
        external fun nativeIsRuntimeReleased(): Boolean
        external fun nativePrefixCacheDiagnosticsLine(): String
        external fun nativeSetBackendProfile(profile: String)
        external fun nativeSaveSessionCache(filePath: String): Boolean
        external fun nativeLoadSessionCache(filePath: String): Boolean
        external fun nativeInitMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean
        external fun nativeFreeMultimodal()
        external fun nativeIsMultimodalEnabled(): Boolean
        external fun nativeGenerateStreamWithImages(
            requestId: String,
            prompt: String,
            imagePaths: Array<String>,
            maxTokens: Int,
            callback: NativeApi.TokenCallback,
        ): Int

        override fun initialize(): Boolean = nativeInitialize()

        override fun loadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nCtx: Int,
            nGpuLayers: Int,
            flashAttnCode: Int,
            kvCacheMethodCode: Int,
            kvCacheMethodPresetCode: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
            progressCallback: NativeApi.ProgressCallback?,
        ): Boolean {
            return nativeLoadModel(
                modelId,
                modelPath,
                nThreads,
                nThreadsBatch,
                nBatch,
                nUbatch,
                nCtx,
                nGpuLayers,
                flashAttnCode,
                kvCacheMethodCode,
                kvCacheMethodPresetCode,
                temperature,
                topK,
                topP,
                minP,
                typicalP,
                repeatLastN,
                repeatPenalty,
                frequencyPenalty,
                presencePenalty,
                mirostat,
                mirostatTau,
                mirostatEta,
                xtcThreshold,
                xtcProbability,
                seed,
                speculativeEnabled,
                speculativeDraftModelPath,
                speculativeMaxDraftTokens,
                speculativeMinDraftTokens,
                speculativeDraftGpuLayers,
                useMmap,
                useMlock,
                nKeep,
                progressCallback,
            )
        }

        override fun generateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicyCode: Int,
            onToken: NativeApi.TokenCallback,
        ): NativeApi.StreamStatus {
            val statusCode = nativeGenerateStream(requestId, prompt, maxTokens, cacheKey, cachePolicyCode, onToken)
            return when (statusCode) {
                0 -> NativeApi.StreamStatus(GenerationFinishReason.COMPLETED)
                1 -> NativeApi.StreamStatus(GenerationFinishReason.MAX_TOKENS)
                2 -> NativeApi.StreamStatus(GenerationFinishReason.CANCELLED, "JNI_CANCELLED")
                3 -> NativeApi.StreamStatus(GenerationFinishReason.CALLBACK_ERROR, "JNI_CALLBACK_ERROR")
                4 -> NativeApi.StreamStatus(GenerationFinishReason.UTF8_STREAM_ERROR, "JNI_UTF8_STREAM_ERROR")
                5 -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_RUNTIME_ERROR")
                else -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_UNKNOWN_STATUS_$statusCode")
            }
        }

        override fun setSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            typicalP: Float,
            repeatLastN: Int,
            repeatPenalty: Float,
            frequencyPenalty: Float,
            presencePenalty: Float,
            mirostat: Int,
            mirostatTau: Float,
            mirostatEta: Float,
            xtcThreshold: Float,
            xtcProbability: Float,
            seed: Int,
            nKeep: Int,
        ) {
            nativeSetSamplingConfig(
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
                nKeep = nKeep,
            )
        }

        override fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String =
            nativeGenerate(prompt, maxTokens, cacheKey, cachePolicyCode)

        override fun cancelGeneration(): Boolean = nativeCancelGeneration()

        override fun unloadModel() = nativeUnloadModel()

        override fun supportsGpuOffload(): Boolean = nativeSupportsGpuOffload()

        override fun modelLayerCount(): Int? = nativeModelLayerCount().takeIf { it > 0 }

        override fun modelSizeBytes(): Long? = nativeModelSizeBytes().takeIf { it > 0L }

        override fun estimateMaxGpuLayers(nCtx: Int): Int? = nativeEstimateMaxGpuLayers(nCtx).takeIf { it >= 0 }

        override fun backendDiagnosticsJson(): String = nativeBackendDiagnosticsJson()

        override fun peakRssMb(): Double? {
            val value = nativePeakRssMb()
            return value.takeIf { !it.isNaN() && it >= 0.0 }
        }

        override fun currentRssMb(): Double? {
            val value = nativeCurrentRssMb()
            return value.takeIf { !it.isNaN() && it >= 0.0 }
        }

        override fun isRuntimeReleased(): Boolean = nativeIsRuntimeReleased()

        override fun prefixCacheDiagnosticsLine(): String = nativePrefixCacheDiagnosticsLine()

        override fun setBackendProfile(profile: String) = nativeSetBackendProfile(profile)

        override fun saveSessionCache(filePath: String): Boolean = nativeSaveSessionCache(filePath)

        override fun loadSessionCache(filePath: String): Boolean = nativeLoadSessionCache(filePath)

        override fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean =
            nativeInitMultimodal(mmProjPath, useGpu, imageMaxTokens)

        override fun freeMultimodal() = nativeFreeMultimodal()

        override fun isMultimodalEnabled(): Boolean = nativeIsMultimodalEnabled()

        override fun generateStreamWithImages(
            requestId: String,
            prompt: String,
            imagePaths: Array<String>,
            maxTokens: Int,
            onToken: NativeApi.TokenCallback,
        ): NativeApi.StreamStatus {
            val statusCode = nativeGenerateStreamWithImages(requestId, prompt, imagePaths, maxTokens, onToken)
            return when (statusCode) {
                0 -> NativeApi.StreamStatus(GenerationFinishReason.COMPLETED)
                1 -> NativeApi.StreamStatus(GenerationFinishReason.MAX_TOKENS)
                2 -> NativeApi.StreamStatus(GenerationFinishReason.CANCELLED, "JNI_CANCELLED")
                3 -> NativeApi.StreamStatus(GenerationFinishReason.CALLBACK_ERROR, "JNI_CALLBACK_ERROR")
                4 -> NativeApi.StreamStatus(GenerationFinishReason.UTF8_STREAM_ERROR, "JNI_UTF8_STREAM_ERROR")
                5 -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_RUNTIME_ERROR")
                else -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_UNKNOWN_STATUS_$statusCode")
            }
        }
    }

    companion object {
        private const val DEFAULT_NATIVE_LIBRARY_NAME = "pocket_llama"
        private const val LIBRARY_V8 = "pocket_llama_v8"
        private const val LIBRARY_V8_2 = "pocket_llama_v8_2"
        private const val LIBRARY_V8_2_DOTPROD = "pocket_llama_v8_2_dotprod"
        private const val LIBRARY_V8_2_I8MM = "pocket_llama_v8_2_i8mm"
        private const val LIBRARY_V8_2_DOTPROD_I8MM = "pocket_llama_v8_2_dotprod_i8mm"
        private const val CPU_INFO_PATH = "/proc/cpuinfo"
        const val ENABLE_ADB_FALLBACK_ENV: String = "POCKETGPT_ENABLE_ADB_FALLBACK"
        const val ENABLE_GPU_OFFLOAD_ENV: String = "POCKETGPT_ENABLE_GPU_OFFLOAD"
        const val ENABLE_OPTIMIZED_NATIVE_LIBRARIES_ENV: String = "POCKETGPT_ENABLE_OPTIMIZED_NATIVE_LIBRARIES"
        private val COMPILED_BACKEND_REGEX = "\"compiled_backend\"\\s*:\\s*\"([^\"]*)\"".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val OPENCL_DEVICE_COUNT_REGEX = "\"opencl_device_count\"\\s*:\\s*(\\d+)".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val HEXAGON_DEVICE_COUNT_REGEX = "\"hexagon_device_count\"\\s*:\\s*(\\d+)".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val LAST_ERROR_CODE_REGEX = "\"last_error_code\"\\s*:\\s*\"([^\"]*)\"".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val LAST_ERROR_DETAIL_REGEX = "\"last_error_detail\"\\s*:\\s*\"([^\"]*)\"".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val SUPPORTS_Q1_0_REGEX = "\"supports_q1_0\"\\s*:\\s*(true|false)".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private val SUPPORTS_Q1_0_G128_REGEX = "\"supports_q1_0_g128\"\\s*:\\s*(true|false)".toRegex(
            option = RegexOption.IGNORE_CASE,
        )
        private const val BONSAI_CPU_SAFE_BATCH = 128
        private val RETRYABLE_GPU_LOAD_ERROR_TERMS = setOf(
            "gpu",
            "opencl",
            "hexagon",
            "backend",
            "memory",
            "alloc",
            "oom",
            "out_of_memory",
            "context_init",
            "load_failed",
        )
        private fun defaultFallbackEnabled(): Boolean {
            val raw = System.getenv(ENABLE_ADB_FALLBACK_ENV)
                ?.trim()
                ?.lowercase()
                ?: return false
            return raw in setOf("1", "true", "yes")
        }

        private fun defaultGpuOffloadEnabled(): Boolean {
            val raw = System.getenv(ENABLE_GPU_OFFLOAD_ENV)
                ?.trim()
                ?.lowercase()
                ?: return true
            return raw !in setOf("0", "false", "no", "off")
        }

        private fun defaultOptimizedNativeLibrariesEnabled(): Boolean {
            val raw = System.getenv(ENABLE_OPTIMIZED_NATIVE_LIBRARIES_ENV)
                ?.trim()
                ?.lowercase()
                ?: return false
            return raw in setOf("1", "true", "yes", "on")
        }

        private const val LOAD_PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val LOAD_PROGRESS_EMIT_STEP = 0.05f
        private val CPU_FEATURE_TOKEN_REGEX = Regex("[a-z0-9_]+")

        private data class CpuFeatureSnapshot(
            val hasFp16: Boolean,
            val hasDotProd: Boolean,
            val hasI8mm: Boolean,
        )

        private fun detectCpuFeatures(): CpuFeatureSnapshot {
            val rawText = runCatching { File(CPU_INFO_PATH).readText() }
                .getOrElse { "" }
                .lowercase()
            if (rawText.isBlank()) {
                return CpuFeatureSnapshot(
                    hasFp16 = false,
                    hasDotProd = false,
                    hasI8mm = false,
                )
            }
            val tokens = CPU_FEATURE_TOKEN_REGEX.findAll(rawText)
                .map { match -> match.value }
                .toSet()
            val hasFp16 = tokens.contains("fphp") || tokens.contains("fp16")
            val hasDotProd = tokens.contains("dotprod") || tokens.contains("asimddp")
            val hasI8mm = tokens.contains("i8mm")
            return CpuFeatureSnapshot(
                hasFp16 = hasFp16,
                hasDotProd = hasDotProd,
                hasI8mm = hasI8mm,
            )
        }

        private fun defaultLoadingDetail(stage: ModelLoadingStage): String {
            return when (stage) {
                ModelLoadingStage.PRECHECK -> "Checking model availability..."
                ModelLoadingStage.UNLOADING_PREVIOUS -> "Releasing previous model..."
                ModelLoadingStage.INITIALIZING_RUNTIME -> "Initializing runtime..."
                ModelLoadingStage.LOADING_MODEL -> "Loading model..."
                ModelLoadingStage.RESTORING_SESSION_CACHE -> "Restoring session cache..."
                ModelLoadingStage.WARMING_UP -> "Warming up..."
                ModelLoadingStage.COMPLETED -> "Loaded"
            }
        }
    }
}
