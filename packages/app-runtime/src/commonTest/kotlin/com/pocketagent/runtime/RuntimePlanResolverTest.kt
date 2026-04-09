package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.RuntimeModelRegistryPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePlanResolverTest {
    @Test
    fun `sampling-only overrides do not change prefix cache slot`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = listOf("</s>"),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        val second = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = listOf("</s>"),
            requestConfig = baseConfig.copy(temperature = 0.2f, topK = 8, topP = 0.6f),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
    }

    @Test
    fun `different sessions resolve different prefix cache slots`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        val second = resolver.resolve(
            sessionId = "session-2",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertNotEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
    }

    @Test
    fun `different model versions keep live prefix cache stable but rotate session cache identities`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelVersion = "q4_0",
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        val second = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelVersion = "ud_iq2_xxs",
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
        assertNotEquals(first.sessionCacheKey, second.sessionCacheKey)
        assertEquals("q4_0", first.sessionCacheIdentity.modelVersion)
        assertEquals("ud_iq2_xxs", second.sessionCacheIdentity.modelVersion)
    }

    @Test
    fun `load-affecting overrides keep prefix cache slot stable but rotate session cache key`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        val second = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig.copy(nCtx = 1024),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
        assertNotEquals(first.sessionCacheKey, second.sessionCacheKey)
    }

    @Test
    fun `resolve gates speculative decoding for low memory devices and applies pressure aware keep alive`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val requestConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )
            .copy(speculativeEnabled = true, speculativeDraftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M)

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = requestConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 15, thermalLevel = 7, ramClassGb = 6),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertFalse(plan.effectiveConfig.speculativeEnabled)
        assertEquals(1024, plan.effectiveConfig.nCtx)
        assertEquals(60_000L, plan.keepAliveMs)
    }

    @Test
    fun `resolve keeps speculative decoding enabled for compatible target and draft families`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertTrue(plan.effectiveConfig.speculativeEnabled)
        assertEquals(ModelCatalog.QWEN3_0_6B_Q4_K_M, plan.effectiveConfig.speculativeDraftModelId)
    }

    @Test
    fun `resolve disables speculative decoding when draft and target families are incompatible`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ).copy(speculativeDraftModelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertFalse(plan.effectiveConfig.speculativeEnabled)
        assertNull(plan.effectiveConfig.speculativeDraftModelId)
        assertEquals(0, plan.effectiveConfig.speculativeDraftGpuLayers)
        assertTrue(plan.diagnostics.contains("layer=speculative_gate"))
    }

    @Test
    fun `resolve applies estimated gpu layer ceiling when native metadata is available`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(12, plan.effectiveConfig.gpuLayers)
        assertTrue(plan.diagnostics.contains("layer=gpu_layer_estimate"))
    }

    @Test
    fun `resolve honors explicit keep alive when adaptive ttl disabled`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(
                idleUnloadTtlMs = 5 * 60_000L,
                adaptiveIdleTtl = false,
            ),
            deviceState = DeviceState(batteryPercent = 10, thermalLevel = 7, ramClassGb = 4),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(5 * 60_000L, plan.keepAliveMs)
    }

    @Test
    fun `resolve reduces context when memory estimate exceeds tracked ceiling`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val tracker = MemoryBudgetTracker().also {
            it.recordAvailableMemoryAfterRelease(1500.0)
        }
        val resolver = RuntimePlanResolver(
            availableCpuThreads = { 8 },
            memoryBudgetTracker = tracker,
        )

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.FAST,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(1024, plan.effectiveConfig.nCtx)
        assertTrue(plan.diagnostics.contains("layer=memory_estimate"))
        assertTrue((plan.estimatedMemoryMb ?: 0.0) > 0.0)
    }

    @Test
    fun `resolve clamps gpu layers to recommendation before reporting blocked plan`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val tracker = MemoryBudgetTracker().also {
            it.recordAvailableMemoryAfterRelease(700.0)
        }
        val resolver = RuntimePlanResolver(
            availableCpuThreads = { 8 },
            memoryBudgetTracker = tracker,
            recommendedGpuLayers = { _, _ -> 4 },
        )

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.FAST,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertEquals(1024, plan.effectiveConfig.nCtx)
        assertEquals(4, plan.effectiveConfig.gpuLayers)
        assertEquals(0, plan.effectiveConfig.speculativeDraftGpuLayers)
        assertTrue(plan.diagnostics.contains("layer=memory_gpu_recommendation"))
        assertNotNull(plan.loadBlockedReason)
    }

    @Test
    fun `resolve uses speculative and batch rescue before blocking model load`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val tracker = MemoryBudgetTracker().also {
            it.recordAvailableMemoryAfterRelease(1600.0)
        }
        val resolver = RuntimePlanResolver(
            availableCpuThreads = { 8 },
            memoryBudgetTracker = tracker,
            recommendedGpuLayers = { _, _ -> 4 },
        )

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.FAST,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertNull(plan.loadBlockedReason)
        assertEquals(1024, plan.effectiveConfig.nCtx)
        assertFalse(plan.effectiveConfig.speculativeEnabled)
        assertEquals(0, plan.effectiveConfig.speculativeDraftGpuLayers)
        assertEquals(64, plan.effectiveConfig.nBatch)
        assertEquals(64, plan.effectiveConfig.nUbatch)
        assertTrue(plan.diagnostics.contains("layer=memory_speculative_rescue"))
        assertTrue(plan.diagnostics.contains("layer=memory_batch_rescue"))
    }

    @Test
    fun `resolve leaves plan unblocked when memory ceiling is unknown`() {
        val runtimeInferencePorts = buildRuntimeInferencePorts()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.FAST,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            runtimeInferencePorts = runtimeInferencePorts,
        )

        assertNull(plan.loadBlockedReason)
    }

    private fun buildRuntimeInferencePorts(): RuntimeInferencePorts {
        return RuntimeInferencePorts(
            modelRegistry = ResolverModelRegistryPort(),
        )
    }
}

private class ResolverModelRegistryPort : RuntimeModelRegistryPort {
    private val metadataByModelId = mapOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4 to ModelRuntimeMetadata(
            layerCount = 22,
            sizeBytes = 1_200_000_000L,
            contextLength = 4096,
            embeddingSize = 2048,
            headCountKv = 8,
            keyLength = 128,
            valueLength = 128,
            vocabSize = 151_936,
            architecture = "qwen3",
        ),
    )
    private val pathByModelId = mapOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4 to "/tmp/qwen-0.8b.gguf",
        ModelCatalog.QWEN3_1_7B_Q4_K_M to "/tmp/qwen3-1.7b.gguf",
        ModelCatalog.QWEN3_0_6B_Q4_K_M to "/tmp/qwen3-0.6b.gguf",
    )

    override fun registerModelPath(modelId: String, absolutePath: String) = Unit

    override fun registeredModelPath(modelId: String): String? = pathByModelId[modelId]

    override fun registerModelMetadata(modelId: String, metadata: ModelRuntimeMetadata) = Unit

    override fun cachedModelMetadata(modelId: String): ModelRuntimeMetadata? = metadataByModelId[modelId]

    override fun cachedModelLayerCount(modelId: String): Int? = metadataByModelId[modelId]?.layerCount

    override fun cachedModelSizeBytes(modelId: String): Long? = metadataByModelId[modelId]?.sizeBytes

    override fun cachedEstimatedMaxGpuLayers(modelId: String, nCtx: Int): Int? = if (nCtx <= 2048) 12 else 8
}
