package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.ModelLoadOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeJniLlamaCppBridgeTest {
    @Test
    fun `auto native library selection uses conservative baseline first`() {
        val attemptedLibraries = mutableListOf<String>()
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello"),
            libraryLoader = { library ->
                attemptedLibraries += library
            },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals(listOf("pocket_llama"), attemptedLibraries)
    }

    @Test
    fun `auto native library selection falls back to armv8 baseline when default artifact is missing`() {
        val attemptedLibraries = mutableListOf<String>()
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello"),
            libraryLoader = { library ->
                attemptedLibraries += library
                if (library == "pocket_llama") {
                    error("missing $library")
                }
            },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals(listOf("pocket_llama", "pocket_llama_v8"), attemptedLibraries)
    }

    @Test
    fun `explicit library name bypasses cpu feature candidate selection`() {
        val attemptedLibraries = mutableListOf<String>()
        val bridge = NativeJniLlamaCppBridge(
            libraryName = "pocket_llama_custom",
            nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello"),
            libraryLoader = { library -> attemptedLibraries += library },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals(listOf("pocket_llama_custom"), attemptedLibraries)
    }

    @Test
    fun `uses native runtime when library is available`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello", peakRssMb = 1536.0)
        val fallback = FakeFallbackBridge()
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = fallback,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.NATIVE_JNI, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        val result = bridge.generate(
            requestId = "req-native-1",
            prompt = "prompt",
            maxTokens = 16,
            cacheKey = "k1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE,
            onToken = { tokens.add(it) },
        )
        assertTrue(result.success)
        assertEquals(1536.0, result.peakRssMb)
        bridge.unloadModel()

        assertTrue(nativeApi.loadCalled)
        assertTrue(nativeApi.generateCalled)
        assertTrue(nativeApi.unloadCalled)
        assertEquals("k1", nativeApi.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE.code, nativeApi.lastCachePolicyCode)
        assertFalse(fallback.loadCalled)
        assertEquals(listOf("native ", "hello "), tokens)
    }

    @Test
    fun `set runtime generation config hot updates native sampler after load`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))

        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                sampling = RuntimeSamplingConfig(
                    temperature = 0.2f,
                    topK = 8,
                    topP = 0.6f,
                    minP = 0.1f,
                    repeatPenalty = 1.3f,
                    mirostat = 2,
                ),
                nKeep = 96,
            ),
        )

        assertEquals(1, nativeApi.loadGpuLayers.size)
        assertEquals(1, nativeApi.samplingConfigCalls)
        assertEquals(0.2f, nativeApi.lastSamplingTemperature)
        assertEquals(8, nativeApi.lastSamplingTopK)
        assertEquals(0.6f, nativeApi.lastSamplingTopP)
        assertEquals(0.1f, nativeApi.lastSamplingMinP)
        assertEquals(1.3f, nativeApi.lastSamplingRepeatPenalty)
        assertEquals(2, nativeApi.lastSamplingMirostat)
        assertEquals(96, nativeApi.lastSamplingNKeep)
    }

    @Test
    fun `opencl policy forces flash attention off and safe turboquant preset on native load`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 16,
                flashAttnMode = FlashAttnMode.ON,
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(FlashAttnMode.OFF.code), nativeApi.loadFlashAttnCodes)
        assertEquals(listOf(KvCacheMethod.TURBOQUANT.code), nativeApi.loadKvCacheMethodCodes)
        assertEquals(listOf(KvCacheMethodPreset.SAFE.code), nativeApi.loadKvCacheMethodPresetCodes)
    }

    @Test
    fun `bonsai rejects cpu only devices before load`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = false,
            backendDiagnosticsJson = """{"compiled_backend":"cpu","supports_q1_0_g128":true}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = false,
                flashAttnMode = FlashAttnMode.ON,
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE,
                nBatch = 576,
                nUbatch = 384,
            ),
        )

        assertTrue(bridge.isReady())
        assertFalse(
            bridge.loadModel(
                ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                "/tmp/bonsai-8b-q1_0_g128.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertFalse(nativeApi.loadCalled)
        assertEquals("RUNTIME_INCOMPATIBLE_MODEL_FORMAT", bridge.lastError()?.code)
        assertTrue(bridge.lastError()?.detail?.contains("qualified_gpu_required=true") == true)
    }

    @Test
    fun `bonsai cpu fallback keeps cpu safe runtime config on gpu capable device`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1,"supports_q1_0_g128":true}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = false,
                flashAttnMode = FlashAttnMode.ON,
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE,
                nBatch = 576,
                nUbatch = 384,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                "/tmp/bonsai-8b-q1_0_g128.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertEquals(listOf(FlashAttnMode.OFF.code), nativeApi.loadFlashAttnCodes)
        assertEquals(listOf(KvCacheMethod.TURBOQUANT.code), nativeApi.loadKvCacheMethodCodes)
        assertEquals(listOf(KvCacheMethodPreset.SAFE.code), nativeApi.loadKvCacheMethodPresetCodes)
        assertEquals(listOf(128), nativeApi.loadNBatches)
        assertEquals(listOf(128), nativeApi.loadNUbatches)
    }

    @Test
    fun `q1 release rejects explicitly unsupported gpu device classes before load`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1,"supports_q1_0_g128":true}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = false,
                    probeStatus = OpenClProbeQualificationStatus.FAILED,
                )
            },
        )

        assertTrue(bridge.isReady())
        assertFalse(
            bridge.loadModel(
                ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                "/tmp/bonsai-8b-q1_0_g128.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertFalse(nativeApi.loadCalled)
        assertTrue(bridge.lastError()?.detail?.contains("device_gpu_path_supported=false") == true)
    }

    @Test
    fun `non bonsai cpu load preserves requested runtime config`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = false,
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = false,
                flashAttnMode = FlashAttnMode.ON,
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE,
                nBatch = 576,
                nUbatch = 384,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(FlashAttnMode.ON.code), nativeApi.loadFlashAttnCodes)
        assertEquals(listOf(KvCacheMethod.TURBOQUANT.code), nativeApi.loadKvCacheMethodCodes)
        assertEquals(listOf(KvCacheMethodPreset.AGGRESSIVE.code), nativeApi.loadKvCacheMethodPresetCodes)
        assertEquals(listOf(576), nativeApi.loadNBatches)
        assertEquals(listOf(384), nativeApi.loadNUbatches)
    }

    @Test
    fun `turboquant requests preserve turboquant metadata`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = KvCacheMethodPreset.BALANCED,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val result = bridge.generate(
            requestId = "req-kv-method-1",
            prompt = "prompt",
            maxTokens = 8,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
        ) {}

        assertTrue(result.success)
        assertEquals(KvCacheMethod.TURBOQUANT, result.requestedKvCacheMethod)
        assertEquals(KvCacheMethod.TURBOQUANT, result.effectiveKvCacheMethod)
        assertEquals(KvCacheMethodPreset.BALANCED, result.kvCacheMethodPreset)
        assertEquals(null, result.kvCacheMethodDemotionReason)
    }

    @Test
    fun `falls back to adb bridge when native runtime is unavailable`() {
        val nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = "")
        val fallback = FakeFallbackBridge(ready = true, loadOk = true, generateOk = true)
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = fallback,
            fallbackEnabled = true,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.ADB_FALLBACK, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        val result = bridge.generate(
            requestId = "req-fallback-1",
            prompt = "prompt",
            maxTokens = 16,
            cacheKey = "fallback-k1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE_STRICT,
            onToken = { tokens.add(it) },
        )
        assertTrue(result.success)
        bridge.unloadModel()

        assertTrue(fallback.loadCalled)
        assertTrue(fallback.generateCalled)
        assertTrue(fallback.unloadCalled)
        assertEquals("fallback-k1", fallback.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE_STRICT, fallback.lastCachePolicy)
    }

    @Test
    fun `is not ready when both native and fallback are unavailable`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = ""),
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = FakeFallbackBridge(ready = false),
            fallbackEnabled = true,
        )

        assertFalse(bridge.isReady())
        assertEquals(RuntimeBackend.UNAVAILABLE, bridge.runtimeBackend())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
    }

    @Test
    fun `cancel generation delegates to active backend`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val nativeBridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
        )
        assertTrue(nativeBridge.isReady())
        assertTrue(nativeBridge.cancelGeneration())
        assertTrue(nativeApi.cancelCalled)

        val fallback = FakeFallbackBridge()
        val fallbackBridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = ""),
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = fallback,
            fallbackEnabled = true,
        )
        assertTrue(fallbackBridge.isReady())
        assertTrue(fallbackBridge.cancelGeneration())
        assertTrue(fallback.cancelCalled)
    }

    @Test
    fun `jni exceptions are recorded with deterministic bridge error code`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "native hello",
                throwOnLoad = true,
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals("JNI_LOAD_EXCEPTION", bridge.lastError()?.code)
    }

    @Test
    fun `gpu load failure does not retry on cpu when strict offload is enabled`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = false,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = "{\"compiled_backend\":\"opencl\"}",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
                gpuBackend = GpuExecutionBackend.OPENCL,
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 2,
            ),
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(32), nativeApi.loadGpuLayers)
        assertEquals(listOf(2), nativeApi.loadDraftGpuLayers)
        assertEquals("GPU_BACKEND_LOAD_FAILED", bridge.lastError()?.code)
    }

    @Test
    fun `gpu load backoff retries with fewer layers and records actual applied values`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = false,
            loadResults = mutableListOf(false, false, true),
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """
                {"compiled_backend":"opencl","last_error_code":"GPU_BACKEND_LOAD_FAILED","last_error_detail":"opencl out_of_memory"}
            """.trimIndent(),
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
                gpuBackend = GpuExecutionBackend.AUTO,
                strictGpuOffload = false,
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 4,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(32, 24, 16), nativeApi.loadGpuLayers)
        assertEquals(listOf(4, 3, 2), nativeApi.loadDraftGpuLayers)
        assertEquals(16, bridge.actualGpuLayers())
        assertEquals(2, bridge.actualDraftGpuLayers())
        assertEquals(2, bridge.lastGpuLoadRetryCount())
    }

    @Test
    fun `estimate max gpu layers delegates to native runtime`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "native hello",
                estimateMaxGpuLayersValue = 14,
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals(14, bridge.estimateMaxGpuLayers(2048))
    }

    @Test
    fun `forwards mmap and draft gpu controls to native load`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 24,
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 3,
                useMmap = false,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b-q4_0.gguf"))
        assertEquals(listOf(24), nativeApi.loadGpuLayers)
        assertEquals(listOf(3), nativeApi.loadDraftGpuLayers)
        assertEquals(listOf(false), nativeApi.loadUseMmap)
    }

    @Test
    fun `explicit gpu backend fails fast when offload is disabled`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.supportsGpuOffload())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals("GPU_BACKEND_UNAVAILABLE", bridge.lastError()?.code)
        assertTrue(nativeApi.loadGpuLayers.isEmpty())
    }

    @Test
    fun `explicit opencl backend fails before native load when diagnostics report zero opencl devices`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """
                {"compiled_backend":"hexagon,opencl","runtime_supported":true,"hexagon_device_count":1,"opencl_device_count":0}
            """.trimIndent(),
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 24,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals("GPU_BACKEND_UNAVAILABLE", bridge.lastError()?.code)
        assertTrue(nativeApi.loadGpuLayers.isEmpty())
    }

    @Test
    fun `opencl falls back to model id heuristics and demotes unsupported quantization when model version is absent`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                modelPath = "/tmp/model.gguf",
            ),
        )
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `bonsai q1 keeps opencl backend and gpu layers when release-qualified`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1,"supports_q1_0_g128":true}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.AUTO,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                modelPath = "/tmp/Bonsai-8B.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertEquals(listOf("opencl"), nativeApi.backendProfiles)
        assertEquals(listOf(20), nativeApi.loadGpuLayers)
    }

    @Test
    fun `bonsai q1 is rejected when runtime format capability is unavailable`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1,"supports_q1_0_g128":false}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )

        assertTrue(bridge.isReady())
        assertFalse(
            bridge.loadModel(
                modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                modelPath = "/tmp/Bonsai-8B.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertFalse(nativeApi.loadCalled)
        assertEquals("RUNTIME_INCOMPATIBLE_MODEL_FORMAT", bridge.lastError()?.code)
    }

    @Test
    fun `opencl heuristic fallback demotes gpu layers when quantization cannot be inferred`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                modelPath = "/tmp/model.gguf",
            ),
        )
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `opencl keeps gpu layers when quantization is explicitly q4_0`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                modelPath = "/tmp/qwen-model-q4_0.gguf",
            ),
        )
        assertEquals(listOf(20), nativeApi.loadGpuLayers)
    }

    @Test
    fun `opencl demotes gpu layers when quantization is explicitly q8_0`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                modelPath = "/tmp/qwen-model-q8_0.gguf",
            ),
        )
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `opencl uses model version quantization when path is generic and model id is coarse`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                modelPath = "/tmp/model.gguf",
                options = ModelLoadOptions(modelVersion = "q4_0"),
            ),
        )
        assertEquals(listOf(20), nativeApi.loadGpuLayers)
    }

    @Test
    fun `opencl demotes gpu layers when model version quantization is unsupported`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.OPENCL,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                modelPath = "/tmp/model.gguf",
                options = ModelLoadOptions(modelVersion = "ud_iq2_xxs"),
            ),
        )
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `bonsai q1 quant keeps opencl backend and gpu layers on native load`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"opencl","opencl_device_count":1,"supports_q1_0_g128":true}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
            openClQualificationProvider = {
                OpenClQualificationSnapshot(
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                )
            },
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.AUTO,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                modelPath = "/tmp/bonsai-8b.gguf",
                options = ModelLoadOptions(modelVersion = "q1_0_g128"),
            ),
        )
        assertEquals(listOf("opencl"), nativeApi.backendProfiles)
        assertEquals(listOf(20), nativeApi.loadGpuLayers)
    }

    @Test
    fun `auto backend still applies opencl quant demotion when release policy forces opencl`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
            backendDiagnosticsJson = """{"compiled_backend":"hexagon,opencl","hexagon_device_count":1,"opencl_device_count":1}""",
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 20,
                gpuBackend = GpuExecutionBackend.AUTO,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(
            bridge.loadModel(
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                modelPath = "/tmp/model.gguf",
            ),
        )
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `auto backend can downgrade to cpu when gpu offload is disabled`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
                gpuBackend = GpuExecutionBackend.AUTO,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
        assertEquals(null, bridge.lastError())
    }

    @Test
    fun `invalid model path extension is rejected before native load`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.bin"))
        assertEquals("MODEL_PATH_INVALID", bridge.lastError()?.code)
        assertFalse(nativeApi.loadCalled)
    }

    @Test
    fun `backend diagnostics are returned when native runtime is active`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
                backendDiagnosticsJson = "{\"compiled_backend\":\"opencl\"}",
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals("{\"compiled_backend\":\"opencl\"}", bridge.backendDiagnosticsJson())
    }

    @Test
    fun `prefix cache diagnostics are returned when native runtime is active`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
                prefixCacheDiagnosticsLine = "PREFIX_CACHE_DIAG|restore_state_success=1|restore_state_failure=0",
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals(
            "PREFIX_CACHE_DIAG|restore_state_success=1|restore_state_failure=0",
            bridge.prefixCacheDiagnosticsLine(),
        )
    }

    @Test
    fun `lifecycle events expose load and offload transitions`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        val states = mutableListOf<ModelLifecycleState>()
        val subscription = bridge.observeModelLifecycleState { event ->
            states += event.state
        }

        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, bridge.getLoadedModel()?.modelId)
        assertTrue(bridge.offloadModel("test"))
        assertEquals(null, bridge.getLoadedModel())

        waitForLifecycleStates(
            states = states,
            expected = setOf(
                ModelLifecycleState.LOADING,
                ModelLifecycleState.LOADED,
                ModelLifecycleState.OFFLOADING,
                ModelLifecycleState.UNLOADED,
            ),
        )

        subscription.close()

        assertTrue(states.contains(ModelLifecycleState.LOADING))
        assertTrue(states.contains(ModelLifecycleState.LOADED))
        assertTrue(states.contains(ModelLifecycleState.OFFLOADING))
        assertTrue(states.contains(ModelLifecycleState.UNLOADED))
    }

    @Test
    fun `load progress events are monotonic and stay within normalized bounds`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
                loadProgressSteps = listOf(0.01f, 0.10f, 0.55f, 0.55f, 1.0f),
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )
        val observedProgress = mutableListOf<Float>()
        val subscription = bridge.observeModelLifecycleState { event ->
            if (event.state == ModelLifecycleState.LOADING && event.loadingProgress != null) {
                observedProgress += event.loadingProgress
            }
        }

        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))

        // Allow dispatcher thread to process events
        Thread.sleep(100)

        subscription.close()
        assertTrue(observedProgress.isNotEmpty())
        assertEquals(observedProgress.sorted(), observedProgress)
        assertTrue(observedProgress.all { progress -> progress in 0.0f..1.0f })
    }

    @Test
    fun `newer offload request cancels in progress native load`() {
        lateinit var bridge: NativeJniLlamaCppBridge
        bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
                loadProgressSteps = listOf(0.15f, 0.30f, 0.60f),
                onProgress = { _, _ ->
                    bridge.offloadModel("cancel_inflight_load")
                },
                backendDiagnosticsJson = """
                    {"last_error_code":"LOAD_CANCELLED_NEWER_REQUEST","last_error_detail":"superseded"}
                """.trimIndent(),
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST, bridge.currentModelLifecycleState().error?.code)
    }

    @Test
    fun `oom native load failure maps to out of memory lifecycle code`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = false,
                generatedText = "ok",
                backendDiagnosticsJson = """
                    {"last_error_code":"OUT_OF_MEMORY","last_error_detail":"stage=model_load|estimated_mb=4096"}
                """.trimIndent(),
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val error = bridge.currentModelLifecycleState().error
        assertNotNull(error)
        assertEquals(ModelLifecycleErrorCode.OUT_OF_MEMORY, error.code)
    }

    @Test
    fun `resolveKvCacheMethod with ULTRA preset passes through`() {
        val result = resolveKvCacheMethod(KvCacheMethod.AUTO, KvCacheMethodPreset.ULTRA)
        assertEquals(KvCacheMethod.TURBOQUANT, result.effectiveMethod)
        assertEquals(KvCacheMethodPreset.ULTRA, result.preset)
    }

    @Test
    fun `resolveKvCacheMethod with EXTREME preset passes through`() {
        val result = resolveKvCacheMethod(KvCacheMethod.AUTO, KvCacheMethodPreset.EXTREME)
        assertEquals(KvCacheMethod.TURBOQUANT, result.effectiveMethod)
        assertEquals(KvCacheMethodPreset.EXTREME, result.preset)
    }
}

private fun waitForLifecycleStates(
    states: List<ModelLifecycleState>,
    expected: Set<ModelLifecycleState>,
    timeoutMs: Long = 1_000L,
) {
    val startedAt = System.currentTimeMillis()
    while ((System.currentTimeMillis() - startedAt) < timeoutMs) {
        if (expected.all(states::contains)) {
            return
        }
        Thread.onSpinWait()
    }
}

private class FakeNativeApi(
    private val initializeOk: Boolean,
    private val loadOk: Boolean,
    private val generatedText: String,
    private val throwOnLoad: Boolean = false,
    private val supportsGpuOffload: Boolean = false,
    private val loadResults: MutableList<Boolean>? = null,
    private val backendDiagnosticsJson: String = "{}",
    private val peakRssMb: Double? = null,
    private val modelLayerCount: Int? = null,
    private val modelSizeBytes: Long? = null,
    private val estimateMaxGpuLayersValue: Int? = null,
    private val prefixCacheDiagnosticsLine: String? = null,
    private val loadProgressSteps: List<Float> = emptyList(),
    private val onProgress: ((Float, NativeJniLlamaCppBridge.NativeApi.ProgressCallback?) -> Unit)? = null,
) : NativeJniLlamaCppBridge.NativeApi {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false
    var cancelCalled = false
    var lastCacheKey: String? = null
    var lastCachePolicyCode: Int? = null
    var samplingConfigCalls: Int = 0
    var lastSamplingTemperature: Float? = null
    var lastSamplingTopK: Int? = null
    var lastSamplingTopP: Float? = null
    var lastSamplingMinP: Float? = null
    var lastSamplingTypicalP: Float? = null
    var lastSamplingRepeatLastN: Int? = null
    var lastSamplingRepeatPenalty: Float? = null
    var lastSamplingFrequencyPenalty: Float? = null
    var lastSamplingPresencePenalty: Float? = null
    var lastSamplingMirostat: Int? = null
    var lastSamplingMirostatTau: Float? = null
    var lastSamplingMirostatEta: Float? = null
    var lastSamplingXtcThreshold: Float? = null
    var lastSamplingXtcProbability: Float? = null
    var lastSamplingSeed: Int? = null
    var lastSamplingNKeep: Int? = null
    val backendProfiles = mutableListOf<String>()
    val loadGpuLayers = mutableListOf<Int>()
    val loadFlashAttnCodes = mutableListOf<Int>()
    val loadKvCacheMethodCodes = mutableListOf<Int>()
    val loadKvCacheMethodPresetCodes = mutableListOf<Int>()
    val loadNBatches = mutableListOf<Int>()
    val loadNUbatches = mutableListOf<Int>()
    val loadDraftGpuLayers = mutableListOf<Int>()
    val loadUseMmap = mutableListOf<Boolean>()
    val loadUseMlock = mutableListOf<Boolean>()
    val loadNKeep = mutableListOf<Int>()

    override fun initialize(): Boolean = initializeOk

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
        samplingConfigCalls += 1
        lastSamplingTemperature = temperature
        lastSamplingTopK = topK
        lastSamplingTopP = topP
        lastSamplingMinP = minP
        lastSamplingTypicalP = typicalP
        lastSamplingRepeatLastN = repeatLastN
        lastSamplingRepeatPenalty = repeatPenalty
        lastSamplingFrequencyPenalty = frequencyPenalty
        lastSamplingPresencePenalty = presencePenalty
        lastSamplingMirostat = mirostat
        lastSamplingMirostatTau = mirostatTau
        lastSamplingMirostatEta = mirostatEta
        lastSamplingXtcThreshold = xtcThreshold
        lastSamplingXtcProbability = xtcProbability
        lastSamplingSeed = seed
        lastSamplingNKeep = nKeep
    }

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
        progressCallback: NativeJniLlamaCppBridge.NativeApi.ProgressCallback?,
    ): Boolean {
        if (throwOnLoad) {
            error("simulated native load exception")
        }
        loadCalled = true
        loadGpuLayers += nGpuLayers
        loadFlashAttnCodes += flashAttnCode
        loadKvCacheMethodCodes += kvCacheMethodCode
        loadKvCacheMethodPresetCodes += kvCacheMethodPresetCode
        loadNBatches += nBatch
        loadNUbatches += nUbatch
        loadDraftGpuLayers += speculativeDraftGpuLayers
        loadUseMmap += useMmap
        loadUseMlock += useMlock
        loadNKeep += nKeep
        loadProgressSteps.forEach { progress ->
            onProgress?.invoke(progress, progressCallback)
            if (progressCallback?.onProgress(progress) == false) {
                return false
            }
        }
        return if (loadResults != null && loadResults.isNotEmpty()) {
            loadResults.removeAt(0)
        } else {
            loadOk
        }
    }

    override fun generateStream(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicyCode: Int,
        onToken: NativeJniLlamaCppBridge.NativeApi.TokenCallback,
    ): NativeJniLlamaCppBridge.NativeApi.StreamStatus {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicyCode = cachePolicyCode
        generatedText
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token -> onToken.onToken("$token ") }
        return NativeJniLlamaCppBridge.NativeApi.StreamStatus(GenerationFinishReason.COMPLETED)
    }

    override fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicyCode = cachePolicyCode
        return generatedText
    }

    override fun unloadModel() {
        unloadCalled = true
    }

    override fun cancelGeneration(): Boolean {
        cancelCalled = true
        return true
    }

    override fun supportsGpuOffload(): Boolean = supportsGpuOffload

    override fun backendDiagnosticsJson(): String = backendDiagnosticsJson

    override fun setBackendProfile(profile: String) {
        backendProfiles += profile
    }

    override fun peakRssMb(): Double? = peakRssMb

    override fun modelLayerCount(): Int? = modelLayerCount

    override fun modelSizeBytes(): Long? = modelSizeBytes

    override fun estimateMaxGpuLayers(nCtx: Int): Int? = estimateMaxGpuLayersValue

    override fun prefixCacheDiagnosticsLine(): String? = prefixCacheDiagnosticsLine

    override fun currentRssMb(): Double? = peakRssMb

    override fun isRuntimeReleased(): Boolean = unloadCalled
}

private class FakeFallbackBridge(
    private val ready: Boolean = true,
    private val loadOk: Boolean = true,
    private val generateOk: Boolean = true,
) : LlamaCppRuntimeBridge {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false
    var cancelCalled = false
    var lastCacheKey: String? = null
    var lastCachePolicy: CachePolicy? = null

    override fun isReady(): Boolean = ready

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN3_1_7B_Q4_K_M,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalled = true
        return loadOk
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicy = cachePolicy
        if (!generateOk) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "FALLBACK_FAILURE",
            )
        }
        onToken("fallback ")
        onToken("token ")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 2,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        unloadCalled = true
    }

    override fun cancelGeneration(): Boolean {
        cancelCalled = true
        return true
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (ready) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.UNAVAILABLE
    }
}
