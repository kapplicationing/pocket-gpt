package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.DefaultPolicyModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.InMemoryObservabilityModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.inference.AdaptiveRoutingPolicy
import com.pocketagent.inference.ArtifactVerificationStatus
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RuntimeImageInputModule
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleError
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.createDefaultRuntimeInferenceModule
import com.pocketagent.nativebridge.runtimeInferencePorts
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class RuntimeOrchestrator(
    private val conversationModule: ConversationModule = InMemoryConversationModule(),
    private val inferenceModule: InferenceModule = createDefaultRuntimeInferenceModule(),
    private val routingModule: RoutingModule = AdaptiveRoutingPolicy(),
    private val policyModule: PolicyModule = DefaultPolicyModule(offlineOnly = true),
    private val observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
    private val toolModule: ToolModule = SafeLocalToolRuntime(),
    private val memoryModule: MemoryModule = FileBackedMemoryModule.ephemeralRuntimeModule(),
    private val runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
    private val networkPolicyClient: PolicyAwareNetworkClient = PolicyAwareNetworkClient(policyModule),
    private val modelSpecProvider: ModelSpecProvider = ModelCatalog,
    private val modelRegistry: ModelRegistry = ModelRegistry.default(modelSpecProvider),
    private val artifactVerifier: ArtifactVerifier = ArtifactVerifier(runtimeConfig, modelRegistry = modelRegistry),
    private val diagnosticsRedactor: DiagnosticsRedactor = DiagnosticsRedactor(),
    private val memoryBudgetTracker: MemoryBudgetTracker? = null,
    private val recommendedGpuLayers: (String, PerformanceRuntimeConfig) -> Int? = { _, _ -> null },
    private val mmProjPathResolver: (String) -> String? = { null },
    private val availableCpuCoresProvider: () -> Int = { Runtime.getRuntime().availableProcessors() },
) : RuntimeContainer {
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts()
    private val runtimeLifecycleObserverLock = Any()
    private val runtimeLifecycleDispatchLock = Object()
    private val runtimeLifecycleObservers: MutableMap<Int, (ModelLifecycleEvent) -> Unit> = mutableMapOf()
    private val runtimeLifecycleDispatchQueue: ArrayDeque<ModelLifecycleEvent> = ArrayDeque()
    private var nextRuntimeLifecycleObserverId: Int = 1
    @Volatile
    private var runtimeLifecycleDispatcherStarted: Boolean = false
    @Volatile
    private var currentRuntimeLifecycleEvent: ModelLifecycleEvent = ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
    private val imageInputModule = RuntimeImageInputModule(inferenceModule)
    private val sessionManager = RuntimeSessionManager(conversationModule, memoryModule)
    private val interactionRegistry = ModelInteractionRegistry(specProvider = modelSpecProvider)
    private val interactionPlanner = InteractionPlanner(
        interactionRegistry = interactionRegistry,
        enabledToolNames = toolModule.listEnabledTools(),
    )
    private val inferenceExecutor = InferenceExecutor(
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
        runtimeInferencePorts = runtimeInferencePorts,
    )
    private val toolLoopCoordinator = ToolLoopCoordinator(toolModule)
    private val runtimePlanResolver = RuntimePlanResolver(
        memoryBudgetTracker = memoryBudgetTracker,
        recommendedGpuLayers = recommendedGpuLayers,
    )
    private val sessionCacheManager = SessionCacheManager(
        cacheDir = File(
            System.getProperty("java.io.tmpdir").orEmpty().ifBlank { "." },
            "pocketgpt-session-cache",
        ),
    )
    private val lastResolvedRuntimePlan = AtomicReference<ResolvedRuntimePlan?>(null)
    private val runtimeResidencyManager = RuntimeResidencyManager(
        inferenceModule = inferenceModule,
        runtimeInferencePorts = runtimeInferencePorts,
        onAfterLoad = ::restoreSessionCache,
        onBeforeUnload = ::saveSessionCache,
    )
    private var routingMode: RoutingMode = RoutingMode.AUTO
    private val modelLifecycleLock = ReentrantLock()
    private val pendingLoadModelId = AtomicReference<String?>(null)
    private val modelLifecycleCoordinator = ModelLifecycleCoordinator(
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        runtimeConfig = runtimeConfig,
        residentModelIdProvider = { runtimeResidencyManager.loadedModelId() },
    )
    private val sendMessageUseCase = SendMessageUseCase(
        conversationModule = conversationModule,
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        memoryModule = memoryModule,
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
        artifactVerifier = artifactVerifier,
        interactionPlanner = interactionPlanner,
        inferenceExecutor = inferenceExecutor,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        runtimePlanResolver = runtimePlanResolver,
        runtimeResidencyManager = runtimeResidencyManager,
        cancelByRequest = ::cancelGenerationByRequest,
        cancelBySession = ::cancelGeneration,
        runtimeInferencePorts = runtimeInferencePorts,
    )
    private val toolExecutionUseCase = ToolExecutionUseCase(
        policyModule = policyModule,
        toolLoopCoordinator = toolLoopCoordinator,
    )
    private val imageAnalyzeUseCase = ImageAnalyzeUseCase(
        policyModule = policyModule,
        inferenceModule = inferenceModule,
        artifactVerifier = artifactVerifier,
        imageInputModule = imageInputModule,
        observabilityModule = observabilityModule,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        routingModeProvider = { routingMode },
        residentModelIdProvider = { runtimeResidencyManager.loadedModelId() },
        availableCpuCoresProvider = availableCpuCoresProvider,
    )
    private val diagnosticsUseCase = DiagnosticsUseCase(
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        diagnosticsRedactor = diagnosticsRedactor,
    )
    private val startupChecksUseCase = StartupChecksUseCase(
        artifactVerifier = artifactVerifier,
        interactionPlanner = interactionPlanner,
        inferenceModule = inferenceModule,
        policyModule = policyModule,
        runtimeConfig = runtimeConfig,
        networkPolicyClient = networkPolicyClient,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        runtimeBackendProvider = ::runtimeBackend,
        modelRegistry = modelRegistry,
    )
    private val runtimeWarmupCoordinator = RuntimeWarmupCoordinator(
        inferenceModule = inferenceModule,
        artifactVerifier = artifactVerifier,
        observabilityModule = observabilityModule,
        runtimeResidencyManager = runtimeResidencyManager,
        runtimePlanResolver = runtimePlanResolver,
        onWarmupStage = ::emitWarmupLifecycleStage,
        runtimeInferencePorts = runtimeInferencePorts,
    )

    init {
        val managedRuntime = runtimeInferencePorts.managedRuntime
        val modelRegistry = runtimeInferencePorts.modelRegistry
        if (managedRuntime != null && modelRegistry != null) {
            artifactVerifier.registerRuntimeModelPaths(modelRegistry)
            currentRuntimeLifecycleEvent = managedRuntime.currentModelLifecycleState()
            managedRuntime.observeModelLifecycleState(::emitRuntimeLifecycleEvent)
        }
    }

    override fun createSession(): SessionId = sessionManager.createSession()

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        context: RuntimeRequestContext,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
    ): ChatResponse {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { message -> message.role == InteractionRole.USER }
            ?.parts
            ?.filterIsInstance<InteractionContentPart.Text>()
            ?.joinToString(separator = "\n") { it.text }
            ?.trim()
            .orEmpty()
        return sendMessageUseCase.execute(
            SendMessageUseCase.Request(
                sessionId = sessionId,
                userText = latestUserText,
                messages = messages,
                taskType = taskType,
                executionContext = context,
                onToken = onToken,
                onThinkingStateChanged = onThinkingStateChanged,
                routingMode = routingMode,
            ),
        )
    }

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        previousResponseId: String?,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        return sendChatMessages(
            sessionId = sessionId,
            messages = messages,
            taskType = taskType,
            context = RuntimeRequestContext(
                deviceState = deviceState,
                maxTokens = maxTokens,
                keepModelLoaded = keepModelLoaded,
                requestTimeoutMs = requestTimeoutMs,
                requestId = requestId,
                previousResponseId = previousResponseId,
                performanceConfig = performanceConfig,
                residencyPolicy = residencyPolicy,
            ),
            onToken = onToken,
            onThinkingStateChanged = onThinkingStateChanged,
        )
    }

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        context: RuntimeRequestContext,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
    ): ChatResponse {
        return sendMessageUseCase.execute(
            SendMessageUseCase.Request(
                sessionId = sessionId,
                userText = userText,
                messages = emptyList(),
                taskType = taskType,
                executionContext = context.copy(previousResponseId = null),
                onToken = onToken,
                onThinkingStateChanged = onThinkingStateChanged,
                routingMode = routingMode,
            ),
        )
    }

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        return sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            context = RuntimeRequestContext(
                deviceState = deviceState,
                maxTokens = maxTokens,
                keepModelLoaded = keepModelLoaded,
                requestTimeoutMs = requestTimeoutMs,
                requestId = requestId,
                performanceConfig = performanceConfig,
                residencyPolicy = residencyPolicy,
            ),
            onToken = onToken,
            onThinkingStateChanged = onThinkingStateChanged,
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        return runToolDetailed(toolName = toolName, jsonArgs = jsonArgs).toLegacyString()
    }

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return toolExecutionUseCase.execute(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(
        imagePath: String,
        prompt: String,
    ): String {
        return legacyImageResponse(
            analyzeImageDetailed(
                imagePath = imagePath,
                prompt = prompt,
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            ),
        )
    }

    override fun analyzeImageDetailed(
        imagePath: String,
        prompt: String,
    ): ImageAnalysisResult {
        return analyzeImageDetailed(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        )
    }

    fun analyzeImage(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): String {
        return legacyImageResponse(
            analyzeImageDetailed(
                imagePath = imagePath,
                prompt = prompt,
                deviceState = deviceState,
            ),
        )
    }

    fun analyzeImageDetailed(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): ImageAnalysisResult {
        return imageAnalyzeUseCase.execute(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = deviceState,
        )
    }

    private fun legacyImageResponse(result: ImageAnalysisResult): String {
        return when (result) {
            is ImageAnalysisResult.Success -> result.content
            is ImageAnalysisResult.Failure -> {
                val failure = result.failure
                if (failure is ImageFailure.PolicyDenied || (failure is ImageFailure.Runtime && failure.code == "image_runtime_error")) {
                    throw IllegalStateException(failure.technicalDetail ?: failure.userMessage)
                }
                result.toLegacyString()
            }
        }
    }

    override fun exportDiagnostics(): String {
        val nativeResidencyState = runtimeInferencePorts.residency?.residencyState()
        val prefixCacheDiagnostics = runtimeInferencePorts.residency?.prefixCacheDiagnosticsLine()
        return buildString {
            append(diagnosticsUseCase.export())
            append('\n')
            append(runtimeResidencyManager.diagnosticsLine(nativeResidencyState))
            memoryBudgetTracker?.let {
                append('\n')
                append(it.diagnosticsLine())
            }
            prefixCacheDiagnostics?.let {
                append('\n')
                append(it)
            }
        }
    }

    override fun exportDiagnosticsJson(): String? {
        val nativeResidencyState = runtimeInferencePorts.residency?.residencyState()
        val lifecycleEvent = currentRuntimeLifecycleEvent
        val sessionCacheDiagnostics = sessionCacheManager.diagnostics()
        val lastPlan = lastResolvedRuntimePlan.get()
        return buildString {
            append('{')
            append("\"residency\":")
            append(
                jsonObject(
                    "residentModelId" to runtimeResidencyManager.loadedModelId(),
                    "queueDepth" to runtimeResidencyManager.queueDepth(),
                    "state" to nativeResidencyState?.toString(),
                    "diagnostics" to runtimeResidencyManager.diagnosticsLine(nativeResidencyState),
                ),
            )
            append(",\"memoryBudget\":")
            append(
                jsonObject(
                    "availableMemoryCeilingMb" to memoryBudgetTracker?.availableMemoryCeilingMb,
                    "largestSuccessfulLoadMb" to memoryBudgetTracker?.largestSuccessfulLoadMb,
                    "lastUpdatedAtEpochMs" to memoryBudgetTracker?.lastUpdatedAtEpochMs,
                ),
            )
            append(",\"lifecycle\":")
            append(
                jsonObject(
                    "state" to lifecycleEvent?.state?.name,
                    "modelId" to lifecycleEvent?.modelId,
                    "modelVersion" to lifecycleEvent?.modelVersion,
                    "loadingStage" to lifecycleEvent?.loadingStage?.name,
                    "loadingProgress" to lifecycleEvent?.loadingProgress,
                    "detail" to lifecycleEvent?.loadingDetail,
                    "errorCode" to lifecycleEvent?.error?.code?.name,
                    "errorDetail" to lifecycleEvent?.error?.detail,
                ),
            )
            append(",\"sessionCache\":")
            append(
                jsonObject(
                    "entryCount" to sessionCacheDiagnostics.entryCount,
                    "totalBytes" to sessionCacheDiagnostics.totalBytes,
                    "maxTotalBytes" to sessionCacheDiagnostics.maxTotalBytes,
                ),
            )
            append(",\"lastLoadPlan\":")
            append(
                jsonObject(
                    "modelId" to lastPlan?.modelId,
                    "estimatedMemoryMb" to lastPlan?.estimatedMemoryMb,
                    "loadBlockedReason" to lastPlan?.loadBlockedReason,
                    "diagnostics" to lastPlan?.diagnostics?.joinToString(separator = "|"),
                    "sessionCacheKey" to lastPlan?.sessionCacheKey,
                ),
            )
            append('}')
        }
    }

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? {
        return currentRuntimeLifecycleEvent
    }

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        ensureRuntimeLifecycleDispatcherStarted()
        val observerId = synchronized(runtimeLifecycleObserverLock) {
            val nextId = nextRuntimeLifecycleObserverId
            nextRuntimeLifecycleObserverId += 1
            runtimeLifecycleObservers[nextId] = listener
            nextId
        }
        listener(currentRuntimeLifecycleEvent)
        return AutoCloseable {
            synchronized(runtimeLifecycleObserverLock) {
                runtimeLifecycleObservers.remove(observerId)
            }
        }
    }

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> {
        return startupChecksUseCase.run()
    }

    override fun warmupActiveModel(): WarmupResult {
        val result = runtimeWarmupCoordinator.warmup()
        if (result.attempted && result.warmed) {
            emitCompletedLifecycleEvent(
                modelId = loadedModel()?.modelId,
                modelVersion = loadedModel()?.modelVersion,
            )
        }
        return result
    }

    override fun evictResidentModel(reason: String): Boolean {
        runtimeResidencyManager.unload(reason)
        observabilityModule.recordLatencyMetric(
            "inference.resident_eviction.${sanitizeMetricSegment(reason)}",
            1.0,
        )
        return true
    }

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        // Pre-flight checks (outside lock) — fast rejection without blocking other operations.
        val availableModels = inferenceModule.listAvailableModels().toSet()
        if (!availableModels.contains(modelId)) {
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "model_not_available:$modelId",
            )
        }
        if (!artifactVerifier.manager().setActiveModel(modelId)) {
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "model_unregistered:$modelId",
            )
        }
        val verification = artifactVerifier.verifyArtifactForModel(modelId)
        if (!verification.passed) {
            val detail = artifactVerifier.artifactVerificationFailureMessage(verification)
            val code = if (verification.status == ArtifactVerificationStatus.RUNTIME_INCOMPATIBLE) {
                ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE
            } else {
                ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE
            }
            return RuntimeModelLifecycleCommandResult.rejected(
                code = code,
                detail = detail.ifBlank { "artifact_verification_failed" },
            )
        }
        interactionPlanner.ensureTemplateAvailable(modelId)?.let { templateError ->
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE,
                detail = templateError,
            )
        }
        val managedRuntime = runtimeInferencePorts.managedRuntime
            ?: return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.BACKEND_INIT_FAILED,
                detail = "runtime_load_requires_native_bridge",
            )

        // Register intent for last-one-wins tracking.
        pendingLoadModelId.set(modelId)

        // Acquire lifecycle lock — serializes all load/unload operations.
        modelLifecycleLock.lock()
        try {
            // Last-one-wins check: if a newer load was requested while we waited, abort.
            if (pendingLoadModelId.get() != modelId) {
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "superseded_by_pending_load:${pendingLoadModelId.get()}",
                )
            }

            // Stop-Await-Release: cancel active generations and wait for them to finish.
            if (!inferenceExecutor.isIdle()) {
                inferenceExecutor.cancelAllAndAwaitIdle(timeoutMs = STOP_AWAIT_TIMEOUT_MS)
            }

            // Unload previous model if one is resident.
            if (runtimeResidencyManager.loadedModelId() != null) {
                runtimeResidencyManager.unload(reason = "model_switch")
                awaitNativeCleanup(managedRuntime)
            }

            // Re-check last-one-wins after awaiting + unload.
            if (pendingLoadModelId.get() != modelId) {
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "superseded_after_unload:${pendingLoadModelId.get()}",
                )
            }

            val performanceConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                gpuEnabled = managedRuntime.supportsGpuOffload(),
            )
            val runtimePlan = runtimePlanResolver.resolve(
                sessionId = "manual-load",
                modelId = modelId,
                modelVersion = modelVersion,
                taskType = "manual_load",
                stopSequences = emptyList(),
                requestConfig = performanceConfig,
                residencyPolicy = ModelResidencyPolicy(),
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
                runtimeInferencePorts = runtimeInferencePorts,
            )
            lastResolvedRuntimePlan.set(runtimePlan)
            runtimePlan.loadBlockedReason?.let { blockedReason ->
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.OUT_OF_MEMORY,
                    detail = blockedReason,
                    loadedModel = loadedModel(),
                )
            }
            managedRuntime.setRuntimeGenerationConfig(runtimePlan.generationConfig)
            val loaded = managedRuntime.loadModel(
                modelId = modelId,
                modelVersion = modelVersion,
                strictGpuOffload = runtimePlan.generationConfig.strictGpuOffload,
            )
            if (!loaded) {
                val bridgeError = managedRuntime.lastBridgeError()
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = mapBridgeLifecycleCode(bridgeError?.code),
                    detail = bridgeError?.detail,
                    loadedModel = loadedModel(),
                )
            }
            runtimeResidencyManager.attachResidentSlot(
                modelId = modelId,
                slotId = runtimePlan.prefixCacheSlotId,
                keepAliveMs = runtimePlan.keepAliveMs,
                sessionCacheIdentity = runtimePlan.sessionCacheIdentity,
            )
            initMultimodalIfAvailable(modelId, managedRuntime)
            return RuntimeModelLifecycleCommandResult.applied(
                loadedModel = RuntimeLoadedModel(
                    modelId = modelId,
                    modelVersion = modelVersion,
                ),
            )
        } finally {
            pendingLoadModelId.compareAndSet(modelId, null)
            modelLifecycleLock.unlock()
        }
    }

    private fun initMultimodalIfAvailable(modelId: String, managedRuntime: ManagedRuntimePort) {
        val mmProjPath = runCatching { mmProjPathResolver(modelId) }.getOrElse { null }
        if (mmProjPath.isNullOrBlank()) {
            return
        }
        val useGpu = managedRuntime.supportsGpuOffload()
        val initialized = runCatching {
            managedRuntime.initMultimodal(
                mmProjPath = mmProjPath,
                useGpu = useGpu,
                imageMaxTokens = 0,
            )
        }.getOrElse { false }
        // Multimodal init result is observable via isMultimodalEnabled() — no runtime log needed.
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        modelLifecycleLock.lock()
        try {
            // Stop-Await-Release: cancel active generations before offloading.
            if (!inferenceExecutor.isIdle()) {
                inferenceExecutor.cancelAllAndAwaitIdle(timeoutMs = STOP_AWAIT_TIMEOUT_MS)
            }
            val currentlyLoaded = loadedModel()
            return when (runtimeResidencyManager.requestUnload(reason = reason)) {
                RuntimeUnloadDisposition.UNLOADED,
                RuntimeUnloadDisposition.NO_RESIDENT_MODEL,
                -> RuntimeModelLifecycleCommandResult.applied(loadedModel = null)

                RuntimeUnloadDisposition.QUEUED -> RuntimeModelLifecycleCommandResult.queued(
                    loadedModel = currentlyLoaded,
                    detail = "offload_queued_while_generation_active",
                )
            }
        } finally {
            modelLifecycleLock.unlock()
        }
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        val modelId = runtimeResidencyManager.loadedModelId() ?: return null
        return RuntimeLoadedModel(
            modelId = modelId,
            modelVersion = resolveResidentModelVersion(modelId),
        )
    }

    override fun activeGenerationCount(): Int = runtimeResidencyManager.queueDepth()

    override fun touchKeepAlive(): Boolean = runtimeResidencyManager.touchKeepAlive()

    override fun shortenKeepAlive(ttlMs: Long): Boolean = runtimeResidencyManager.shortenKeepAlive(ttlMs)

    override fun onTrimMemory(level: Int): Boolean = runtimeResidencyManager.onTrimMemory(level)

    override fun onAppBackground(): Boolean = runtimeResidencyManager.onAppBackground()

    override fun onAppForeground(): Boolean {
        if (!runtimeResidencyManager.wasAutoReleased) {
            return false
        }
        // Clear the auto-released state but do NOT eagerly reload the model.
        // The model will be loaded lazily on the next user-initiated action
        // (message send via ensureLoaded, or explicit load tap).
        // This prevents expensive surprise loads when the user merely foregrounds the app.
        val modelId = runtimeResidencyManager.lastAutoReleasedModelId
        runtimeResidencyManager.clearAutoReleasedState()
        if (modelId != null) {
            emitRuntimeLifecycleEvent(
                ModelLifecycleEvent(
                    state = ModelLifecycleState.UNLOADED,
                    modelId = modelId,
                    loadingDetail = "Model was released in the background. It will reload when needed.",
                ),
            )
        }
        return false
    }

    override fun addAutoReleaseDisableReason(reason: String) {
        runtimeResidencyManager.addAutoReleaseDisableReason(reason)
    }

    override fun removeAutoReleaseDisableReason(reason: String) {
        runtimeResidencyManager.removeAutoReleaseDisableReason(reason)
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        sessionManager.restoreSession(sessionId, turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = sessionManager.deleteSession(sessionId)

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        return inferenceExecutor.cancelBySession(sessionId.value)
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        return inferenceExecutor.cancelByRequest(requestId)
    }

    fun listSessions(): List<SessionId> = sessionManager.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = sessionManager.listTurns(sessionId)

    override fun runtimeBackend(): String? = runtimeBackendEnum()?.name

    override fun supportsGpuOffload(): Boolean {
        return runtimeInferencePorts.managedRuntime?.supportsGpuOffload() ?: false
    }

    fun runtimeBackendEnum(): RuntimeBackend? {
        return runtimeInferencePorts.managedRuntime?.runtimeBackend()
    }

    private fun saveSessionCache(slot: ResidentRuntimeSlot, reason: String) {
        val identity = slot.sessionCacheIdentity ?: return
        val sessionCache = runtimeInferencePorts.sessionCache ?: return
        if (reason == "reconcile_missing_file" || reason == "reconcile_missing_version") {
            sessionCacheManager.evict(identity.cacheKey)
            return
        }
        sessionCacheManager.save(
            serializer = object : SessionCacheSerializer {
                override fun saveSessionCache(filePath: String): Boolean = sessionCache.saveSessionCache(filePath)
            },
            identity = identity,
        )
    }

    private fun restoreSessionCache(slot: ResidentRuntimeSlot) {
        val identity = slot.sessionCacheIdentity ?: return
        val sessionCache = runtimeInferencePorts.sessionCache ?: return
        if (!sessionCacheManager.hasCacheFor(identity)) {
            return
        }
        emitLoadingStage(
            modelId = slot.modelId,
            modelVersion = resolveResidentModelVersion(slot.modelId),
            stage = ModelLoadingStage.RESTORING_SESSION_CACHE,
            progress = 0.94f,
        )
        sessionCacheManager.restore(
            serializer = object : SessionCacheSerializer {
                override fun saveSessionCache(filePath: String): Boolean = sessionCache.saveSessionCache(filePath)
                override fun loadSessionCache(filePath: String): Boolean = sessionCache.loadSessionCache(filePath)
            },
            identity = identity,
        )
        emitCompletedLifecycleEvent(
            modelId = slot.modelId,
            modelVersion = resolveResidentModelVersion(slot.modelId),
        )
    }

    private fun emitWarmupLifecycleStage(modelId: String, stage: ModelLoadingStage, progress: Float) {
        emitLoadingStage(
            modelId = modelId,
            modelVersion = resolveResidentModelVersion(modelId),
            stage = stage,
            progress = progress,
        )
    }

    private fun resolveResidentModelVersion(modelId: String): String? {
        runtimeResidencyManager.listResident()
            .firstOrNull { slot -> slot.modelId == modelId }
            ?.sessionCacheIdentity
            ?.modelVersion
            ?.let { return it }
        val currentEvent = currentRuntimeLifecycleEvent
        if (currentEvent.state == ModelLifecycleState.LOADED && currentEvent.modelId == modelId) {
            return currentEvent.modelVersion
        }
        val runtimeEvent = runtimeInferencePorts.managedRuntime?.currentModelLifecycleState()
        if (runtimeEvent?.state == ModelLifecycleState.LOADED && runtimeEvent.modelId == modelId) {
            return runtimeEvent.modelVersion
        }
        return null
    }

    private fun awaitNativeCleanup(managedRuntime: ManagedRuntimePort) {
        val deadlineMs = System.currentTimeMillis() + NATIVE_CLEANUP_TIMEOUT_MS
        var lastRssMb = managedRuntime.currentRssMb()
        var stableReads = 0
        while (System.currentTimeMillis() <= deadlineMs) {
            val released = managedRuntime.isRuntimeReleased()
            val currentRssMb = managedRuntime.currentRssMb()
            if (released) {
                if (currentRssMb == null || lastRssMb == null) {
                    return
                }
                stableReads = if (abs(currentRssMb - lastRssMb) <= NATIVE_CLEANUP_STABLE_DELTA_MB) {
                    stableReads + 1
                } else {
                    0
                }
                if (stableReads >= NATIVE_CLEANUP_REQUIRED_STABLE_READS) {
                    return
                }
            }
            lastRssMb = currentRssMb ?: lastRssMb
            Thread.sleep(NATIVE_CLEANUP_POLL_INTERVAL_MS)
        }
        observabilityModule.recordLatencyMetric("inference.native_cleanup_timeout", 1.0)
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV
    }

    private fun ensureRuntimeLifecycleDispatcherStarted() {
        if (runtimeLifecycleDispatcherStarted) {
            return
        }
        synchronized(runtimeLifecycleDispatchLock) {
            if (runtimeLifecycleDispatcherStarted) {
                return
            }
            runtimeLifecycleDispatcherStarted = true
            val dispatcherThread = Thread({
                while (true) {
                    val nextEvent = synchronized(runtimeLifecycleDispatchLock) {
                        while (runtimeLifecycleDispatchQueue.isEmpty()) {
                            runtimeLifecycleDispatchLock.wait()
                        }
                        runtimeLifecycleDispatchQueue.removeFirst()
                    }
                    val observers = synchronized(runtimeLifecycleObserverLock) {
                        runtimeLifecycleObservers.values.toList()
                    }
                    observers.forEach { observer ->
                        runCatching { observer(nextEvent) }
                    }
                }
            }, "runtime-orchestrator-lifecycle")
            dispatcherThread.isDaemon = true
            dispatcherThread.start()
        }
    }

    private fun emitRuntimeLifecycleEvent(event: ModelLifecycleEvent) {
        currentRuntimeLifecycleEvent = event
        ensureRuntimeLifecycleDispatcherStarted()
        synchronized(runtimeLifecycleDispatchLock) {
            runtimeLifecycleDispatchQueue.addLast(event)
            runtimeLifecycleDispatchLock.notifyAll()
        }
    }

    private fun emitLoadingStage(
        modelId: String?,
        modelVersion: String?,
        stage: ModelLoadingStage,
        progress: Float,
    ) {
        emitRuntimeLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADING,
                modelId = modelId,
                modelVersion = modelVersion,
                loadingDetail = defaultLoadingDetail(stage),
                loadingStage = stage,
                loadingProgress = progress.coerceIn(0f, 1f),
            ),
        )
    }

    private fun emitCompletedLifecycleEvent(
        modelId: String?,
        modelVersion: String?,
    ) {
        emitRuntimeLifecycleEvent(
            ModelLifecycleEvent(
                state = if (modelId.isNullOrBlank()) ModelLifecycleState.UNLOADED else ModelLifecycleState.LOADED,
                modelId = modelId,
                modelVersion = modelVersion,
                loadingDetail = if (modelId.isNullOrBlank()) null else defaultLoadingDetail(ModelLoadingStage.COMPLETED),
                loadingStage = if (modelId.isNullOrBlank()) null else ModelLoadingStage.COMPLETED,
                loadingProgress = if (modelId.isNullOrBlank()) null else 1.0f,
            ),
        )
    }
}

private const val STOP_AWAIT_TIMEOUT_MS = 5_000L
private const val NATIVE_CLEANUP_POLL_INTERVAL_MS = 50L
private const val NATIVE_CLEANUP_TIMEOUT_MS = 2_000L
private const val NATIVE_CLEANUP_STABLE_DELTA_MB = 4.0
private const val NATIVE_CLEANUP_REQUIRED_STABLE_READS = 2

private fun mapBridgeLifecycleCode(errorCode: String?): ModelLifecycleErrorCode {
    val normalized = errorCode?.trim()?.uppercase().orEmpty()
    return when {
        normalized.contains("OUT_OF_MEMORY") ||
            normalized.contains("OOM") ||
            normalized.contains("ENOMEM") ->
            ModelLifecycleErrorCode.OUT_OF_MEMORY

        normalized.contains("MODEL") || normalized.contains("FILE") || normalized.contains("PATH") ->
            ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE

        normalized.contains("COMPAT") || normalized.contains("PROVENANCE") || normalized.contains("CHECKSUM") ->
            ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE

        normalized.contains("BUSY") -> ModelLifecycleErrorCode.BUSY_GENERATION

        normalized.contains("CANCELLED_NEWER_REQUEST") ->
            ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST

        normalized.contains("JNI") ||
            normalized.contains("RUNTIME") ||
            normalized.contains("BACKEND") ->
            ModelLifecycleErrorCode.BACKEND_INIT_FAILED

        else -> ModelLifecycleErrorCode.UNKNOWN
    }
}

private fun sanitizeMetricSegment(raw: String): String {
    return raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
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

private fun jsonObject(vararg fields: Pair<String, Any?>): String {
    return fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${jsonEscape(key)}\":${jsonValue(value)}"
    }
}

private fun jsonValue(value: Any?): String {
    return when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${jsonEscape(value.toString())}\""
    }
}

private fun jsonEscape(value: String): String {
    return buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}

class RuntimeGenerationTimeoutException(
    val timeoutMs: Long,
) : RuntimeException("Generation timed out after ${(timeoutMs / 1000L).coerceAtLeast(1L)}s.")

class RuntimeGenerationCancelledException(
    val requestId: String,
) : RuntimeException("Generation was cancelled for requestId=$requestId")

class RuntimeGenerationFailureException(
    message: String,
    val errorCode: String? = null,
) : RuntimeException(message)

internal class GenerationTimeoutGuard(
    timeoutMs: Long,
    onTimeout: () -> Unit,
) {
    private val timedOutFlag = AtomicBoolean(false)
    private val finishedFlag = AtomicBoolean(false)
    private val timeoutJob = CoroutineScope(Dispatchers.Default).let { scope ->
        scope.launch {
            delay(timeoutMs.coerceAtLeast(1L))
            if (finishedFlag.get()) {
                return@launch
            }
            timedOutFlag.set(true)
            onTimeout()
        }
    }

    fun timedOut(): Boolean = timedOutFlag.get()

    fun finish() {
        finishedFlag.set(true)
        timeoutJob.cancel()
    }
}
