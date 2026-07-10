package com.pocketagent.android.runtime

import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeTuningDeciderTest {
    @Test
    fun `memory pressure reduces gpu layers and batch size`() {
        val decider = RuntimeTuningDecider(nowMs = { 42L })
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 16,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                peakRssMb = 3200.0,
            ),
        )

        assertEquals(12, next.gpuLayers)
        assertEquals(128, next.nBatch)
        assertEquals(128, next.nUbatch)
        assertEquals(1, next.speculativeDraftGpuLayers)
        assertEquals(KvCachePreset.AGGRESSIVE, next.kvCachePreset)
        assertEquals(42L, next.updatedAtEpochMs)
        assertEquals("demote_memory_pressure", next.lastDecision)
    }

    @Test
    fun `slow speculative run disables speculative decoding`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = false,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                firstTokenMs = 4500L,
                tokensPerSec = 4.0,
            ),
        )

        assertFalse(next.speculativeEnabled ?: true)
        assertEquals("demote_speculative", next.lastDecision)
    }

    @Test
    fun `gpu runtime failures demote gpu layers`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 8,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "JNI_RUNTIME_ERROR",
            ),
        )

        assertEquals(6, next.gpuLayers)
        assertEquals("demote_gpu_regression", next.lastDecision)
    }

    @Test
    fun `mmap regressions demote mmap usage`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 8,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "mmap_readahead_failed",
            ),
        )

        assertFalse(next.useMmap ?: true)
        assertEquals("demote_use_mmap", next.lastDecision)
    }

    @Test
    fun `slow cpu only mmap success demotes mmap usage`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = false,
        ).copy(
            speculativeEnabled = false,
            speculativeDraftGpuLayers = 0,
            useMmap = true,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                firstTokenMs = 12_000L,
                tokensPerSec = 6.0,
            ),
        )

        assertFalse(next.useMmap ?: true)
        assertEquals("demote_use_mmap", next.lastDecision)
    }

    @Test
    fun `cpu timeout demotes context before batch size`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = false,
        ).copy(
            useMmap = false,
            nCtx = 8192,
            nThreads = 8,
            nThreadsBatch = 12,
            nBatch = 768,
            nUbatch = 768,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "timeout",
            ),
        )

        assertEquals(4096, next.nCtx)
        assertEquals(8, next.nThreads)
        assertEquals(12, next.nThreadsBatch)
        assertEquals("timeout_demote_n_ctx", next.lastDecision)
    }

    @Test
    fun `cpu timeout demotes threads after context floor`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = false,
        ).copy(
            useMmap = false,
            nCtx = 1024,
            nThreads = 6,
            nThreadsBatch = 9,
            nBatch = 512,
            nUbatch = 512,
        )

        val first = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "timeout",
            ),
        )
        val second = decider.nextRecommendation(
            current = first,
            appliedConfig = targetConfig.copy(
                nThreads = first.nThreads ?: targetConfig.nThreads,
                nThreadsBatch = first.nThreadsBatch ?: targetConfig.nThreadsBatch,
                nCtx = first.nCtx ?: targetConfig.nCtx,
            ),
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "timeout",
            ),
        )

        assertEquals(6, first.nThreads)
        assertEquals(6, first.nThreadsBatch)
        assertEquals(4, second.nThreads)
        assertEquals("timeout_demote_n_threads", second.lastDecision)
    }

    @Test
    fun `three benchmark-quality wins promote demoted gpu layers toward target`() {
        val decider = RuntimeTuningDecider(nowMs = { 100L })
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 8,
        )
        val demoted = RuntimeTuningRecommendation(
            gpuLayers = 4,
            kvCachePreset = KvCachePreset.BALANCED,
            speculativeEnabled = true,
            nBatch = 256,
            nUbatch = 256,
            targetGpuLayers = 8,
            targetSpeculativeEnabled = true,
            targetNBatch = 512,
            targetNUbatch = 512,
        )
        val appliedConfig = targetConfig.copy(gpuLayers = 4, nBatch = 256, nUbatch = 256)
        val observation = RuntimeTuningObservation(
            success = true,
            firstTokenMs = 1500L,
            tokensPerSec = 16.0,
            peakRssMb = 1600.0,
        )

        val first = decider.nextRecommendation(demoted, appliedConfig, targetConfig, observation)
        val second = decider.nextRecommendation(first, appliedConfig, targetConfig, observation)
        val third = decider.nextRecommendation(second, appliedConfig, targetConfig, observation)

        assertEquals(6, third.gpuLayers)
        assertEquals(1, third.promotionCount)
        assertEquals(0, third.benchmarkWinCount)
        assertEquals("promote_gpu_layers", third.lastDecision)
    }

    @Test
    fun `storage key distinguishes model identity envelope dimensions`() {
        val baseEnvelope = RuntimeTuningEnvelopeIdentity(
            modelVersion = "qwen3.5-0.8b-q4_k_m-v1",
            quantClass = "q4_k_m",
            artifactIdentity = "abcd1234abcd1234",
            contextBucket = "ctx_2049_4096",
            backendIdentity = "unknown",
        )
        val versionKey = buildRuntimeTuningStorageKey(
            prefix = "runtime_tuning_rec__",
            deviceKey = "pixel_8",
            profileName = "FAST",
            mode = "gpu",
            modelId = "qwen3.5-0.8b-q4",
            envelope = baseEnvelope,
        )
        val contextKey = buildRuntimeTuningStorageKey(
            prefix = "runtime_tuning_rec__",
            deviceKey = "pixel_8",
            profileName = "FAST",
            mode = "gpu",
            modelId = "qwen3.5-0.8b-q4",
            envelope = baseEnvelope.copy(contextBucket = "ctx_gt_8192"),
        )
        val artifactKey = buildRuntimeTuningStorageKey(
            prefix = "runtime_tuning_rec__",
            deviceKey = "pixel_8",
            profileName = "FAST",
            mode = "gpu",
            modelId = "qwen3.5-0.8b-q4",
            envelope = baseEnvelope.copy(
                modelVersion = "qwen3.5-0.8b-q6_k-v2",
                quantClass = "q6_k",
                artifactIdentity = "ffffeeee11112222",
            ),
        )
        val kvPresetKey = buildRuntimeTuningStorageKey(
            prefix = "runtime_tuning_rec__",
            deviceKey = "pixel_8",
            profileName = "FAST",
            mode = "gpu",
            modelId = "qwen3.5-0.8b-q4",
            envelope = baseEnvelope.copy(
                kvPreset = "balanced",
            ),
        )

        assertNotEquals(versionKey, contextKey)
        assertNotEquals(versionKey, artifactKey)
        assertNotEquals(versionKey, kvPresetKey)
        assertNotEquals(contextKey, artifactKey)
        assertNotEquals(contextKey, kvPresetKey)
        assertNotEquals(artifactKey, kvPresetKey)
    }

    @Test
    fun `quant class inference prefers model version then path then model id`() {
        assertEquals(
            "q6_k_v2",
            runtimeTuningQuantClass(
                modelVersion = "manual-q6_k-v2",
                modelPath = "/models/llama-q4_k_m.gguf",
                modelId = "llama-3.2-q8_0",
            ),
        )
        assertEquals(
            "q4_k_m",
            runtimeTuningQuantClass(
                modelVersion = null,
                modelPath = "/models/llama-q4-k-m.gguf",
                modelId = "llama-3.2-q8_0",
            ),
        )
        assertEquals(
            "q8_0",
            runtimeTuningQuantClass(
                modelVersion = null,
                modelPath = null,
                modelId = "llama-3.2-q8_0",
            ),
        )
    }

    @Test
    fun `artifact identity prefers sha and falls back to stable path hash`() {
        assertEquals(
            "abcdef0123456789",
            runtimeTuningArtifactIdentity(
                sha256 = "abcdef0123456789fedcba9876543210",
                absolutePath = "/models/a.gguf",
            ),
        )

        val pathIdentity = runtimeTuningArtifactIdentity(
            sha256 = null,
            absolutePath = "/models/a.gguf",
        )
        assertTrue(pathIdentity.startsWith("path_"))
        assertEquals(
            pathIdentity,
            runtimeTuningArtifactIdentity(
                sha256 = null,
                absolutePath = "/models/a.gguf",
            ),
        )
        assertNotEquals(
            pathIdentity,
            runtimeTuningArtifactIdentity(
                sha256 = null,
                absolutePath = "/models/b.gguf",
            ),
        )
    }

    @Test
    fun `memory pressure demotes balanced kv cache to aggressive`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 16,
        )
        val appliedConfig = targetConfig.copy(kvCachePreset = KvCachePreset.BALANCED)
        val next = decider.nextRecommendation(
            current = RuntimeTuningRecommendation(
                gpuLayers = 16,
                kvCachePreset = KvCachePreset.BALANCED,
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 2,
                useMmap = true,
                nThreads = 8,
                nThreadsBatch = 12,
                nBatch = 768,
                nUbatch = 768,
                nCtx = 8192,
            ),
            appliedConfig = appliedConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(success = false, errorCode = "out_of_memory"),
        )
        assertEquals(KvCachePreset.AGGRESSIVE, next.kvCachePreset)
        assertEquals("demote_memory_pressure", next.lastDecision)
    }

    @Test
    fun `aggressive kv cache stays aggressive on memory pressure`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 16,
        )
        val appliedConfig = targetConfig.copy(kvCachePreset = KvCachePreset.AGGRESSIVE)
        val next = decider.nextRecommendation(
            current = RuntimeTuningRecommendation(
                gpuLayers = 16,
                kvCachePreset = KvCachePreset.AGGRESSIVE,
                speculativeEnabled = true,
                speculativeDraftGpuLayers = 2,
                useMmap = true,
                nThreads = 8,
                nThreadsBatch = 12,
                nBatch = 768,
                nUbatch = 768,
                nCtx = 8192,
            ),
            appliedConfig = appliedConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(success = false, errorCode = "out_of_memory"),
        )
        assertEquals(KvCachePreset.AGGRESSIVE, next.kvCachePreset)
    }

    @Test
    fun `promotion climbs from aggressive toward balanced`() {
        val decider = RuntimeTuningDecider(nowMs = { 100L })
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 16,
        )
        val appliedConfig = targetConfig.copy(kvCachePreset = KvCachePreset.AGGRESSIVE)
        var rec = RuntimeTuningRecommendation(
            kvCachePreset = KvCachePreset.AGGRESSIVE,
            benchmarkWinCount = 2,
        )
        rec = decider.nextRecommendation(
            current = rec,
            appliedConfig = appliedConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                firstTokenMs = 500,
                tokensPerSec = 20.0,
                peakRssMb = 1500.0,
            ),
        )
        assertEquals(KvCachePreset.BALANCED, rec.kvCachePreset)
        assertEquals("promote_kv_preset", rec.lastDecision)
    }
}
