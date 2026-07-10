package com.pocketagent.runtime

import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeModelMemoryEstimatorTest {
    @Test
    fun `kv cache memory decreases across retained presets`() {
        val estimates = KvCachePreset.entries.map { preset -> estimate(preset).kvCacheBytes }

        assertTrue(estimates.zipWithNext().all { (larger, smaller) -> larger > smaller })
    }

    @Test
    fun `balanced includes quantized kv rotation overhead`() {
        val safe = estimate(KvCachePreset.SAFE)
        val balanced = estimate(KvCachePreset.BALANCED)

        assertTrue(balanced.kvCacheBytes < safe.kvCacheBytes)
        assertTrue(balanced.estimatedBytes > balanced.modelWeightsBytes + balanced.kvCacheBytes)
    }

    @Test
    fun `missing metadata uses file size fallback`() {
        val fileSize = 400L * 1024 * 1024
        val result = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = fileSize,
            metadata = null,
            nCtx = 2048,
            kvCachePreset = KvCachePreset.SAFE,
            nUbatch = 512,
        )

        assertEquals((fileSize * 1.2).toLong(), result.estimatedBytes)
        assertEquals(0L, result.kvCacheBytes)
    }

    private fun estimate(preset: KvCachePreset): RuntimeMemoryEstimate {
        return RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = 3L * 1024 * 1024 * 1024,
            metadata = ModelRuntimeMetadata(
                layerCount = 32,
                embeddingSize = 4096,
                headCountKv = 8,
                keyLength = 128,
                valueLength = 128,
                vocabSize = 32_000,
            ),
            nCtx = 4096,
            kvCachePreset = preset,
            nUbatch = 512,
        )
    }
}
