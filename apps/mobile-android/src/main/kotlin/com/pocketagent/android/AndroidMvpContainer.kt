package com.pocketagent.android

import android.content.Context
import com.pocketagent.android.runtime.createDefaultAndroidInferenceModule
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
import com.pocketagent.inference.AdaptiveRoutingPolicy
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.android.runtime.RuntimeBootstrapper
import com.pocketagent.runtime.PolicyAwareNetworkClient
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.SamplingOverrides
import com.pocketagent.runtime.RuntimeConfig
import com.pocketagent.runtime.RuntimeOrchestrator
import com.pocketagent.runtime.RuntimeRequestContext
import com.pocketagent.android.voice.AndroidLocalToolRuntime
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule

private fun defaultInferenceModule(appContext: Context? = null): InferenceModule {
    return if (appContext != null) {
        createDefaultAndroidInferenceModule(appContext.applicationContext)
    } else {
        LlamaCppInferenceModule()
    }
}

private fun defaultToolModule(appContext: Context? = null): ToolModule {
    return if (appContext != null) {
        AndroidLocalToolRuntime(appContext.applicationContext)
    } else {
        SafeLocalToolRuntime()
    }
}

class AndroidMvpContainer(
    private val appContext: Context? = null,
    private val conversationModule: ConversationModule = InMemoryConversationModule(),
    private val inferenceModule: InferenceModule = defaultInferenceModule(appContext),
    private val routingModule: RoutingModule = AdaptiveRoutingPolicy(),
    private val policyModule: PolicyModule = DefaultPolicyModule(offlineOnly = true),
    private val observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
    private val toolModule: ToolModule = defaultToolModule(appContext),
    private val memoryModule: MemoryModule = FileBackedMemoryModule.ephemeralRuntimeModule(),
    private val runtimeEnvConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
    private val artifactPayloadByModelId: Map<String, ByteArray> = runtimeEnvConfig.artifactPayloadByModelId,
    private val artifactFilePathByModelId: Map<String, String> = runtimeEnvConfig.artifactFilePathByModelId,
    private val artifactSha256ByModelId: Map<String, String> = runtimeEnvConfig.artifactSha256ByModelId,
    private val artifactProvenanceIssuerByModelId: Map<String, String> =
        runtimeEnvConfig.artifactProvenanceIssuerByModelId,
    private val artifactProvenanceSignatureByModelId: Map<String, String> =
        runtimeEnvConfig.artifactProvenanceSignatureByModelId,
    private val runtimeCompatibilityTag: String = runtimeEnvConfig.runtimeCompatibilityTag,
    private val requireNativeRuntimeForStartupChecks: Boolean = runtimeEnvConfig.requireNativeRuntimeForStartupChecks,
    private val prefixCacheEnabled: Boolean = runtimeEnvConfig.prefixCacheEnabled,
    private val prefixCacheStrict: Boolean = runtimeEnvConfig.prefixCacheStrict,
    private val responseCacheTtlSec: Long = runtimeEnvConfig.responseCacheTtlSec,
    private val responseCacheMaxEntries: Int = runtimeEnvConfig.responseCacheMaxEntries,
    private val streamContractV2Enabled: Boolean = runtimeEnvConfig.streamContractV2Enabled,
    private val networkPolicyClient: PolicyAwareNetworkClient = PolicyAwareNetworkClient(policyModule),
) {
    private val runtimeConfig = RuntimeConfig(
        artifactPayloadByModelId = artifactPayloadByModelId,
        artifactFilePathByModelId = artifactFilePathByModelId,
        artifactSha256ByModelId = artifactSha256ByModelId,
        artifactProvenanceIssuerByModelId = artifactProvenanceIssuerByModelId,
        artifactProvenanceSignatureByModelId = artifactProvenanceSignatureByModelId,
        runtimeCompatibilityTag = runtimeCompatibilityTag,
        requireNativeRuntimeForStartupChecks = requireNativeRuntimeForStartupChecks,
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
            responseCacheTtlSec = responseCacheTtlSec,
            responseCacheMaxEntries = responseCacheMaxEntries,
            streamContractV2Enabled = streamContractV2Enabled,
        )

    private val orchestrator = RuntimeOrchestrator(
        conversationModule = conversationModule,
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        toolModule = toolModule,
        memoryModule = memoryModule,
        runtimeConfig = runtimeConfig,
        networkPolicyClient = networkPolicyClient,
        memoryBudgetTracker = appContext?.let { context ->
            RuntimeBootstrapper.runtimeTuning(context.applicationContext).memoryBudgetTracker
        },
        recommendedGpuLayers = { modelId, config ->
            appContext?.let { context ->
                val runtimeTuning = RuntimeBootstrapper.runtimeTuning(context.applicationContext)
                runtimeTuning
                    .applyRecommendedConfig(
                        modelIdHint = modelId,
                        baseConfig = config,
                        gpuQualifiedLayers = config.gpuLayers.coerceAtLeast(0),
                    )
                    .gpuLayers
                    .takeIf { it != config.gpuLayers }
            }
        },
        mmProjPathResolver = run {
            var cachedStore: com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore? = null
            return@run { modelId: String ->
                val store = cachedStore ?: appContext?.let { context ->
                    com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore(context.applicationContext)
                        .also { cachedStore = it }
                }
                store?.resolveMmProjPath(modelId)
            }
        },
    )

    fun createSession(): SessionId = orchestrator.createSession()

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
        keepModelLoaded: Boolean = false,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        requestId: String = "legacy",
        performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
    ): ChatResponse {
        return sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            keepModelLoaded = keepModelLoaded,
            requestTimeoutMs = requestTimeoutMs,
            requestId = requestId,
            performanceConfig = performanceConfig,
            onToken = {},
        )
    }

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
        keepModelLoaded: Boolean = false,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        requestId: String = "legacy",
        performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
        samplingOverrides: SamplingOverrides? = null,
        onToken: (String) -> Unit,
    ): ChatResponse {
        return orchestrator.sendUserMessage(
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
                residencyPolicy = ModelResidencyPolicy(),
                samplingOverrides = samplingOverrides,
            ),
            onToken = onToken,
        )
    }

    fun runTool(toolName: String, jsonArgs: String): String = orchestrator.runTool(toolName, jsonArgs)

    fun analyzeImage(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
    ): String {
        return orchestrator.analyzeImage(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = deviceState,
        )
    }

    fun runStartupChecks(): List<String> = orchestrator.runStartupChecks()

    fun exportDiagnostics(): String = orchestrator.exportDiagnostics()

    fun listSessions(): List<SessionId> = orchestrator.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = orchestrator.listTurns(sessionId)

    fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        orchestrator.restoreSession(sessionId = sessionId, turns = turns)
    }

    fun deleteSession(sessionId: SessionId): Boolean = orchestrator.deleteSession(sessionId)

    fun setRoutingMode(mode: RoutingMode) {
        orchestrator.setRoutingMode(mode)
    }

    fun getRoutingMode(): RoutingMode = orchestrator.getRoutingMode()

    fun runtimeBackend(): RuntimeBackend? = orchestrator.runtimeBackendEnum()

    companion object {
        const val MODEL_ENV_PREFIX: String = RuntimeConfig.MODEL_ENV_PREFIX
        const val MODEL_SIDELOAD_PATH_ENV_SUFFIX: String = RuntimeConfig.MODEL_SIDELOAD_PATH_ENV_SUFFIX
        const val MODEL_SHA256_ENV_SUFFIX: String = RuntimeConfig.MODEL_SHA256_ENV_SUFFIX
        const val MODEL_PROVENANCE_SIGNATURE_ENV_SUFFIX: String = RuntimeConfig.MODEL_PROVENANCE_SIGNATURE_ENV_SUFFIX
        const val MODEL_PROVENANCE_ISSUER_ENV_SUFFIX: String = RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV_SUFFIX
        const val MODEL_PROVENANCE_ISSUER_ENV: String = RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV
        const val MODEL_RUNTIME_COMPATIBILITY_ENV: String = RuntimeConfig.MODEL_RUNTIME_COMPATIBILITY_ENV
        const val REQUIRE_NATIVE_RUNTIME_STARTUP_ENV: String = RuntimeConfig.REQUIRE_NATIVE_RUNTIME_STARTUP_ENV
        const val PREFIX_CACHE_ENABLED_ENV: String = RuntimeConfig.PREFIX_CACHE_ENABLED_ENV
        const val PREFIX_CACHE_STRICT_ENV: String = RuntimeConfig.PREFIX_CACHE_STRICT_ENV
        const val RESPONSE_CACHE_TTL_SEC_ENV: String = RuntimeConfig.RESPONSE_CACHE_TTL_SEC_ENV
        const val RESPONSE_CACHE_MAX_ENTRIES_ENV: String = RuntimeConfig.RESPONSE_CACHE_MAX_ENTRIES_ENV
        const val STREAM_CONTRACT_V2_ENV: String = RuntimeConfig.STREAM_CONTRACT_V2_ENV
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV
        const val ANDROID_RUNTIME_MODE_ENV: String = "POCKETGPT_ANDROID_RUNTIME_MODE"

        fun sideLoadPathEnvName(modelId: String): String = RuntimeConfig.sideLoadPathEnvName(modelId)

        fun sha256EnvName(modelId: String): String = RuntimeConfig.sha256EnvName(modelId)

        fun provenanceSignatureEnvName(modelId: String): String = RuntimeConfig.provenanceSignatureEnvName(modelId)

        fun provenanceIssuerEnvName(modelId: String): String = RuntimeConfig.provenanceIssuerEnvName(modelId)

        fun baselineModels(): List<String> = ModelCatalog.baselineModels()

        private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 600_000L
    }
}
