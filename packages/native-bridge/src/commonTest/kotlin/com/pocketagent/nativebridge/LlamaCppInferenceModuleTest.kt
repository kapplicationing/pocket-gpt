package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlamaCppInferenceModuleTest {
    @Test
    fun `generate stream requires loaded model`() {
        val module = LlamaCppInferenceModule(FakeBridge())

        assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
    }

    @Test
    fun `load generate unload delegates to runtime bridge`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val tokens = mutableListOf<String>()
        module.generateStream(InferenceRequest("hello", 32)) { tokens.add(it) }
        module.unloadModel()

        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.generateCalls)
        assertEquals(1, bridge.unloadCalls)
        assertEquals("/tmp/qwen-0.8b.gguf", bridge.lastModelPath)
        assertEquals(listOf("hello ", "from ", "llama "), tokens)
    }

    @Test
    fun `same loaded runtime key reuses resident model without bridge reload`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(1, bridge.loadCalls)
        assertTrue(module.residencyState().residentHit)
        assertEquals(1L, module.residencyState().residentHitCount)
    }

    @Test
    fun `sampling-only config changes do not reload active model`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        module.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                sampling = RuntimeSamplingConfig(
                    temperature = 0.2f,
                    topK = 8,
                    topP = 0.6f,
                ),
            ),
        )
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(1, bridge.loadCalls)
        assertTrue(module.residencyState().residentHit)
    }

    @Test
    fun `load config changes reload active model on next load`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        module.setRuntimeGenerationConfig(
            RuntimeGenerationConfig(
                nThreads = 6,
                nThreadsBatch = 6,
                nBatch = 768,
                nUbatch = 384,
                nCtx = 1024,
                gpuEnabled = true,
                gpuLayers = 32,
            ),
        )
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(2, bridge.loadCalls)
        assertEquals(0, bridge.unloadCalls)
        assertEquals(RuntimeReloadReason.GENERATION_CONFIG_CHANGED, module.residencyState().reloadReason)
    }

    @Test
    fun `mmap and draft gpu config changes also force reload on next load`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        module.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 2,
                useMmap = false,
            ),
        )
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(2, bridge.loadCalls)
        assertEquals(RuntimeReloadReason.GENERATION_CONFIG_CHANGED, module.residencyState().reloadReason)
    }

    @Test
    fun `load fails for unknown model and runtime generation failure throws`() {
        val bridge = FakeBridge(generateOk = false)
        val module = LlamaCppInferenceModule(bridge)

        assertFalse(module.loadModel("unknown-model"))
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val error = assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
        assertTrue(error.message?.contains("generation failed") == true)
    }

    @Test
    fun `last bridge error delegates to runtime bridge contract`() {
        val module = LlamaCppInferenceModule(FakeBridge(lastError = BridgeError("REMOTE_PROCESS_DIED", "service disconnected")))

        assertEquals("REMOTE_PROCESS_DIED", module.lastBridgeError()?.code)
    }

    @Test
    fun `prefix cache diagnostics line delegates to bridge`() {
        val module = LlamaCppInferenceModule(
            FakeBridge(prefixCacheDiagnosticsLine = "PREFIX_CACHE_DIAG|restore_state_success=2"),
        )

        assertEquals("PREFIX_CACHE_DIAG|restore_state_success=2", module.prefixCacheDiagnosticsLine())
    }

    @Test
    fun `runtime backend delegates to bridge`() {
        val module = LlamaCppInferenceModule(FakeBridge(backend = RuntimeBackend.ADB_FALLBACK))
        assertEquals(RuntimeBackend.ADB_FALLBACK, module.runtimeBackend())
    }

    @Test
    fun `active backend identity resolves from bridge diagnostics`() {
        val module = LlamaCppInferenceModule(
            FakeBridge(backendDiagnosticsJson = """{"compiled_backend":"hexagon,opencl","active_backend":"opencl"}"""),
        )

        assertEquals("opencl", module.activeBackendIdentity())
        assertEquals("opencl", module.runtimeInferencePorts().cacheAwareGeneration?.activeBackendIdentity())
    }

    @Test
    fun `load caches estimated gpu layers for the active model context`() {
        val bridge = FakeBridge(estimateMaxGpuLayers = 12)
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(12, module.cachedEstimatedMaxGpuLayers(ModelCatalog.QWEN_3_5_0_8B_Q4, 2048))
    }

    @Test
    fun `registered metadata is preserved and load fills runtime layer count and size`() {
        val bridge = FakeBridge(modelLayerCount = 22, modelSizeBytes = 1_200_000_000L)
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")
        module.registerModelMetadata(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelRuntimeMetadata(
                contextLength = 4096,
                embeddingSize = 1024,
                headCountKv = 8,
                keyLength = 128,
                valueLength = 128,
                vocabSize = 151_936,
                architecture = "qwen3",
            ),
        )

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val metadata = module.cachedModelMetadata(ModelCatalog.QWEN_3_5_0_8B_Q4)

        assertEquals(22, metadata?.layerCount)
        assertEquals(1_200_000_000L, metadata?.sizeBytes)
        assertEquals(4096, metadata?.contextLength)
        assertEquals("qwen3", metadata?.architecture)
    }

    @Test
    fun `generate stream with cache delegates cache key and policy`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        module.generateStreamWithCache(
            requestId = "req-cache",
            request = InferenceRequest("hello", 32),
            cacheKey = "cache-key-v1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE,
            onToken = {},
        )

        assertEquals("cache-key-v1", bridge.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE, bridge.lastCachePolicy)
    }

    @Test
    fun `runtime inference ports expose llama capabilities through provider seam`() {
        val module = LlamaCppInferenceModule(FakeBridge())
        val ports = module.runtimeInferencePorts()

        assertTrue(ports.managedRuntime === module)
        assertTrue(ports.cacheAwareGeneration === module)
        assertTrue(ports.modelRegistry === module)
        assertTrue(ports.residency === module)
        assertTrue(ports.sessionCache === module)
    }

    @Test
    fun `runtime inference ports are empty for inference modules without provider support`() {
        val module = object : InferenceModule {
            override fun listAvailableModels(): List<String> = emptyList()
            override fun loadModel(modelId: String): Boolean = false
            override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) = Unit
            override fun unloadModel() = Unit
        }

        val ports = module.runtimeInferencePorts()

        assertEquals(RuntimeInferencePorts(), ports)
    }
}

private class FakeBridge(
    private val generateOk: Boolean = true,
    private val backend: RuntimeBackend = RuntimeBackend.NATIVE_JNI,
    private val lastError: BridgeError? = null,
    private val backendDiagnosticsJson: String? = null,
    private val prefixCacheDiagnosticsLine: String? = null,
    private val estimateMaxGpuLayers: Int? = null,
    private val modelLayerCount: Int? = null,
    private val modelSizeBytes: Long? = null,
) : LlamaCppRuntimeBridge {
    var loadCalls: Int = 0
    var generateCalls: Int = 0
    var unloadCalls: Int = 0
    var lastModelPath: String? = null
    var lastCacheKey: String? = null
    var lastCachePolicy: CachePolicy? = null

    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN3_1_7B_Q4_K_M,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalls += 1
        lastModelPath = modelPath
        return true
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalls += 1
        lastCacheKey = cacheKey
        lastCachePolicy = cachePolicy
        if (!generateOk) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "FAKE_FAILURE",
            )
        }
        onToken("hello ")
        onToken("from ")
        onToken("llama ")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 3,
            firstTokenMs = 1L,
            totalMs = 3L,
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        unloadCalls += 1
    }

    override fun runtimeBackend(): RuntimeBackend = backend

    override fun lastError(): BridgeError? = lastError

    override fun backendDiagnosticsJson(): String? = backendDiagnosticsJson

    override fun estimateMaxGpuLayers(nCtx: Int): Int? = estimateMaxGpuLayers

    override fun modelLayerCount(): Int? = modelLayerCount

    override fun modelSizeBytes(): Long? = modelSizeBytes

    override fun prefixCacheDiagnosticsLine(): String? = prefixCacheDiagnosticsLine
}
