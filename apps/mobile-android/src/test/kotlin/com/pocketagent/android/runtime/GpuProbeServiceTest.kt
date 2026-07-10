package com.pocketagent.android.runtime

import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuProbeServiceTest {
    @Test
    fun `runner fails when native runtime is unavailable`() {
        val bridge = FakeGpuProbeBridge(
            ready = false,
            gpuSupported = true,
            errorDetail = "runtime:init_failed",
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.NATIVE_RUNTIME_UNAVAILABLE, result.failureReason)
        assertEquals("runtime:init_failed", result.detail)
    }

    @Test
    fun `runner fails when runtime reports gpu unsupported`() {
        val bridge = FakeGpuProbeBridge(
            ready = true,
            gpuSupported = false,
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.RUNTIME_UNSUPPORTED, result.failureReason)
    }

    @Test
    fun `runner reports native load failure on first ladder layer`() {
        val bridge = FakeGpuProbeBridge(
            ready = true,
            gpuSupported = true,
            loadByLayer = mapOf(1 to false),
            errorDetail = "native:load_failed",
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.NATIVE_LOAD_FAILED, result.failureReason)
        assertEquals("native:load_failed", result.detail)
    }

    @Test
    fun `runner reports native generate failure on first ladder layer`() {
        val bridge = FakeGpuProbeBridge(
            ready = true,
            gpuSupported = true,
            generateByLayer = mapOf(1 to false),
            errorDetail = "native:generate_failed",
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.NATIVE_GENERATE_FAILED, result.failureReason)
        assertEquals("native:generate_failed", result.detail)
    }

    @Test
    fun `runner returns partial qualification when a higher layer fails`() {
        val bridge = FakeGpuProbeBridge(
            ready = true,
            gpuSupported = true,
            generateByLayer = mapOf(4 to false),
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(2, result.maxStableGpuLayers)
        assertTrue(result.detail?.contains("last_generate_failed_layer=4") == true)
    }

    @Test
    fun `runner qualifies max stable layer and uses conservative batch defaults`() {
        val bridge = FakeGpuProbeBridge(
            ready = true,
            gpuSupported = true,
        )
        val runner = GpuProbeRunner(bridgeFactory = { bridge })

        val result = runner.runProbeLadder(testRequest())

        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(32, result.maxStableGpuLayers)
        assertEquals(listOf(1, 2, 4, 8, 16, 32), bridge.seenLayers)
        assertTrue(bridge.generationConfigs.all { cfg -> cfg.nBatch == 256 && cfg.nUbatch == 256 })
        assertTrue(
            bridge.generationConfigs.all { cfg ->
                cfg.kvCachePreset == KvCachePreset.SAFE
            },
        )
    }
}

private fun testRequest(): GpuProbeRequest {
    return GpuProbeRequest(
        modelId = "qwen3.5-0.8b-q4",
        modelVersion = "v1",
        modelPath = "/tmp/model.gguf",
        layerLadder = listOf(1, 2, 4, 8, 16, 32),
    )
}

private class FakeGpuProbeBridge(
    private val ready: Boolean,
    private val gpuSupported: Boolean,
    private val loadByLayer: Map<Int, Boolean> = emptyMap(),
    private val generateByLayer: Map<Int, Boolean> = emptyMap(),
    private val errorDetail: String? = null,
) : GpuProbeBridge {
    private var currentLayer: Int = 0
    val generationConfigs: MutableList<RuntimeGenerationConfig> = mutableListOf()
    val seenLayers: MutableList<Int> = mutableListOf()

    override fun isReady(): Boolean = ready

    override fun supportsGpuOffload(): Boolean = gpuSupported

    override fun setBackendProfile(profile: String) = Unit

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        generationConfigs += config
        currentLayer = config.gpuLayers
        seenLayers += currentLayer
    }

    override fun loadModel(modelId: String, modelPath: String): Boolean {
        return loadByLayer[currentLayer] ?: true
    }

    override fun generateSyncProbe(prompt: String, maxTokens: Int, cachePolicy: CachePolicy): Boolean {
        return generateByLayer[currentLayer] ?: true
    }

    override fun unloadModel() = Unit

    override fun lastErrorDetail(): String? = errorDetail
}
