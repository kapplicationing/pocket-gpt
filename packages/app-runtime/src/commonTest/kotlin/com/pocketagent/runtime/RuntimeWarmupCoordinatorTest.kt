package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CacheAwareGenerationPort
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.RuntimeResidencyPort
import com.pocketagent.nativebridge.RuntimeResidencyState
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeWarmupCoordinatorTest {
    @Test
    fun `warmup rejects before config or load when chat owns runtime`() {
        val inferenceModule = WarmupInferenceModule()
        val warmupPorts = WarmupRuntimePorts()
        val runtimeInferencePorts = RuntimeInferencePorts(
            managedRuntime = warmupPorts,
            cacheAwareGeneration = warmupPorts,
            residency = warmupPorts,
        )
        val operations = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            operations.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease
        val coordinator = RuntimeWarmupCoordinator(
            inferenceModule = inferenceModule,
            artifactVerifier = ArtifactVerifier(warmupRuntimeConfig()),
            observabilityModule = WarmupObservabilityModule(),
            runtimeResidencyManager = RuntimeResidencyManager(
                inferenceModule,
                runtimeInferencePorts = runtimeInferencePorts,
                nowMs = monotonicClock(),
            ),
            runtimePlanResolver = RuntimePlanResolver(availableCpuThreads = { 6 }),
            availableCpuThreads = { 6 },
            nowMs = monotonicClock(),
            runtimeInferencePorts = runtimeInferencePorts,
            operationCoordinator = operations,
        )

        try {
            val result = coordinator.warmup()

            assertFalse(result.attempted)
            assertEquals(RUNTIME_BUSY_GENERATION_CODE.lowercase(), result.errorCode)
            assertEquals(0, warmupPorts.configCalls)
            assertEquals(0, warmupPorts.loadCalls)
            assertEquals(0, warmupPorts.generateCalls)
        } finally {
            active.close()
        }
    }

    @Test
    fun `warmup keeps active model resident and reuses same load key`() {
        val inferenceModule = WarmupInferenceModule()
        val warmupPorts = WarmupRuntimePorts()
        val runtimeInferencePorts = RuntimeInferencePorts(
            managedRuntime = warmupPorts,
            cacheAwareGeneration = warmupPorts,
            residency = warmupPorts,
        )
        val runtimeConfig = warmupRuntimeConfig()
        val artifactVerifier = ArtifactVerifier(runtimeConfig)
        val expectedModelVersion = artifactVerifier.manager().getActiveModelVersion()
        val observability = WarmupObservabilityModule()
        val residencyManager = RuntimeResidencyManager(
            inferenceModule,
            runtimeInferencePorts = runtimeInferencePorts,
            nowMs = monotonicClock(),
        )
        val lifecycleStages = mutableListOf<ModelLoadingStage>()
        val coordinator = RuntimeWarmupCoordinator(
            inferenceModule = inferenceModule,
            artifactVerifier = artifactVerifier,
            observabilityModule = observability,
            runtimeResidencyManager = residencyManager,
            runtimePlanResolver = RuntimePlanResolver(availableCpuThreads = { 6 }),
            availableCpuThreads = { 6 },
            nowMs = monotonicClock(),
            onWarmupStage = { _, stage, _ -> lifecycleStages += stage },
            runtimeInferencePorts = runtimeInferencePorts,
        )

        val first = coordinator.warmup()
        val second = coordinator.warmup()

        assertTrue(first.attempted)
        assertTrue(first.warmed)
        assertFalse(first.speculativePath)
        assertTrue(second.attempted)
        assertTrue(second.warmed)
        assertTrue(second.residentHit)
        assertEquals(0, inferenceModule.loadCalls)
        assertEquals(1, warmupPorts.loadCalls)
        assertEquals(expectedModelVersion, warmupPorts.lastModelVersion)
        assertEquals(2, warmupPorts.generateCalls)
        assertEquals(1, warmupPorts.lastMaxTokens)
        assertEquals("Ready?", warmupPorts.lastPrompt)
        assertEquals(1, residencyManager.listResident().size)
        assertTrue(observability.metrics.keys.contains("inference.model_load_ms"))
        assertTrue(observability.metrics.keys.contains("inference.warmup_ms"))
        assertTrue(observability.metrics.keys.contains("inference.warmup.speculative_path"))
        assertTrue(observability.metrics.keys.contains("inference.resident_hit"))
        assertTrue(lifecycleStages.contains(ModelLoadingStage.WARMING_UP))
    }

    @Test
    fun `warmup skips cpu only runtimes`() {
        val inferenceModule = WarmupInferenceModule()
        val warmupPorts = WarmupRuntimePorts(
            supportsGpuOffload = false,
            activeBackendIdentity = "cpu",
        )
        val runtimeInferencePorts = RuntimeInferencePorts(
            managedRuntime = warmupPorts,
            cacheAwareGeneration = warmupPorts,
            residency = warmupPorts,
        )
        val coordinator = RuntimeWarmupCoordinator(
            inferenceModule = inferenceModule,
            artifactVerifier = ArtifactVerifier(warmupRuntimeConfig()),
            observabilityModule = WarmupObservabilityModule(),
            runtimeResidencyManager = RuntimeResidencyManager(
                inferenceModule,
                runtimeInferencePorts = runtimeInferencePorts,
                nowMs = monotonicClock(),
            ),
            runtimePlanResolver = RuntimePlanResolver(availableCpuThreads = { 6 }),
            availableCpuThreads = { 6 },
            nowMs = monotonicClock(),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        val result = coordinator.warmup()

        assertFalse(result.attempted)
        assertFalse(result.warmed)
        assertEquals("warmup_cpu_only_runtime", result.errorCode)
        assertEquals(0, warmupPorts.generateCalls)
        assertEquals(0, warmupPorts.loadCalls)
    }
}

private class WarmupInferenceModule : InferenceModule {
    var loadCalls: Int = 0
    private var loadedModelId: String? = null

    override fun listAvailableModels(): List<String> = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4)

    override fun loadModel(modelId: String): Boolean {
        if (loadedModelId != modelId) {
            loadCalls += 1
            loadedModelId = modelId
        }
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) = Unit

    override fun unloadModel() {
        loadedModelId = null
    }
}

private class WarmupRuntimePorts(
    private val supportsGpuOffload: Boolean = true,
    private val activeBackendIdentity: String? = "opencl",
) : ManagedRuntimePort, CacheAwareGenerationPort, RuntimeResidencyPort {
    var loadCalls: Int = 0
    var generateCalls: Int = 0
    var configCalls: Int = 0
    var lastMaxTokens: Int = 0
    var lastPrompt: String = ""
    var lastModelVersion: String? = null
    private var config: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private var residencyState = RuntimeResidencyState()
    private var loadedModelId: String? = null

    override fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean): Boolean {
        if (loadedModelId != modelId || lastModelVersion != modelVersion) {
            loadCalls += 1
            loadedModelId = modelId
            lastModelVersion = modelVersion
        }
        return true
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        configCalls += 1
        this.config = config
    }

    override fun supportsGpuOffload(): Boolean = supportsGpuOffload

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI

    override fun lastBridgeError() = null

    override fun currentModelLifecycleState() = ModelLifecycleEvent(
        state = com.pocketagent.nativebridge.ModelLifecycleState.UNLOADED,
    )

    override fun observeModelLifecycleState(
        listener: (ModelLifecycleEvent) -> Unit,
    ): AutoCloseable {
        listener(currentModelLifecycleState())
        return AutoCloseable { }
    }

    override fun currentRssMb(): Double? = null

    override fun isRuntimeReleased(): Boolean = true

    override fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: com.pocketagent.nativebridge.CachePolicy,
        onToken: (String) -> Unit,
    ): com.pocketagent.nativebridge.GenerationResult {
        generateCalls += 1
        lastPrompt = request.prompt
        lastMaxTokens = request.maxTokens
        onToken("ok")
        return com.pocketagent.nativebridge.GenerationResult(
            finishReason = com.pocketagent.nativebridge.GenerationFinishReason.COMPLETED,
            tokenCount = 1,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
        )
    }

    override fun cancelGeneration(requestId: String): Boolean = true

    override fun actualGpuLayers(): Int? = config.gpuLayers

    override fun actualDraftGpuLayers(): Int? = config.speculativeDraftGpuLayers

    override fun lastGpuLoadRetryCount(): Int? = 0

    override fun activeBackendIdentity(): String? = activeBackendIdentity

    override fun updateResidencySlot(slotId: String?, expiresAtEpochMs: Long?) {
        val wasResident = residencyState.resident
        val sameSlot = slotId != null && slotId == residencyState.slotId
        residencyState = residencyState.copy(
            slotId = slotId,
            expiresAtEpochMs = expiresAtEpochMs,
            resident = slotId != null,
            residentHit = wasResident && sameSlot,
        )
    }

    override fun residencyState(): RuntimeResidencyState = residencyState

    override fun prefixCacheDiagnosticsLine(): String? = null

    override fun recordWarmup(durationMs: Long) {
        residencyState = residencyState.copy(lastWarmupDurationMs = durationMs)
    }
}

private class WarmupObservabilityModule : ObservabilityModule {
    val metrics: MutableMap<String, MutableList<Double>> = mutableMapOf()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        metrics.getOrPut(name) { mutableListOf() }.add(valueMs)
    }

    override fun recordThermalSnapshot(level: Int) = Unit

    override fun exportLocalDiagnostics(): String = "diag"
}

private fun warmupRuntimeConfig(): RuntimeConfig {
    val payload = "warmup-payload".encodeToByteArray()
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to payload),
        artifactFilePathByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to ""),
        artifactSha256ByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to warmupSha256(payload)),
        artifactProvenanceIssuerByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release"),
        artifactProvenanceSignatureByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0"),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
    )
}

private fun warmupSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun monotonicClock(): () -> Long {
    var tick = 1_000L
    return {
        tick += 5L
        tick
    }
}
