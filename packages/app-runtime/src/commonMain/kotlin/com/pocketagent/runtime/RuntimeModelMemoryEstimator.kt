package com.pocketagent.runtime

import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.ModelRuntimeMetadata

data class RuntimeMemoryEstimate(
    val estimatedBytes: Long,
    val modelWeightsBytes: Long,
    val kvCacheBytes: Long,
    val computeBufferBytes: Long,
    val usedModelMetadata: Boolean,
    val fitsInMemory: Boolean? = null,
) {
    val estimatedMb: Double get() = estimatedBytes.toDouble() / 1_048_576.0
}

object RuntimeModelMemoryEstimator {
    fun estimate(
        modelFileSizeBytes: Long,
        metadata: ModelRuntimeMetadata?,
        nCtx: Int,
        kvCachePreset: KvCachePreset,
        nUbatch: Int,
        availableMemoryMb: Double? = null,
    ): RuntimeMemoryEstimate {
        val validMetadata: ModelRuntimeMetadata = metadata?.takeIf { candidate ->
            (candidate.layerCount ?: 0) > 0 &&
                (candidate.embeddingSize ?: 0) > 0 &&
                (candidate.headCountKv ?: 0) > 0 &&
                (candidate.keyLength ?: 0) > 0 &&
                (candidate.valueLength ?: 0) > 0 &&
                (candidate.vocabSize ?: 0) > 0
        } ?: return fallbackEstimate(
            modelFileSizeBytes = modelFileSizeBytes,
            availableMemoryMb = availableMemoryMb,
        )

        val effectiveCtx = minOf(
            nCtx.coerceAtLeast(1),
            validMetadata.slidingWindow?.coerceAtLeast(1) ?: nCtx.coerceAtLeast(1),
        )
        val layerCount = validMetadata.layerCount ?: 0
        val headCountKv = validMetadata.headCountKv ?: 0
        val keyLength = validMetadata.keyLength ?: 0
        val valueLength = validMetadata.valueLength ?: 0
        val vocabSize = validMetadata.vocabSize ?: 0
        val embeddingSize = validMetadata.embeddingSize ?: 0

        // Asymmetric K/V: the aggressive preset keeps keys at Q8_0 and compresses values to Q4_0.
        // AGGRESSIVE: keys Q8_0, values Q4_0. BALANCED: both Q8_0. SAFE: both F16.
        val kvCacheBytes = (
            layerCount.toLong() *
                effectiveCtx.toLong() *
                headCountKv.toLong() *
                (
                    keyLength.toDouble() * bytesPerElementK(kvCachePreset) +
                        valueLength.toDouble() * bytesPerElementV(kvCachePreset)
                    )
            ).toLong()
        val computeBufferBytes = (vocabSize.toLong() + embeddingSize.toLong()) * nUbatch.coerceAtLeast(1).toLong() * 4L
        // Hadamard rotation overhead: one sign vector (float[head_dim]) per layer.
        // It is only allocated when quantized KV is active.
        val rotationOverheadBytes = when (kvCachePreset) {
            KvCachePreset.SAFE -> 0L
            else -> layerCount.toLong() * keyLength.toLong() * 4L
        }
        val estimatedBytes = ((modelFileSizeBytes + kvCacheBytes + computeBufferBytes + rotationOverheadBytes).toDouble() * METADATA_OVERHEAD_MULTIPLIER)
            .toLong()
        return RuntimeMemoryEstimate(
            estimatedBytes = estimatedBytes,
            modelWeightsBytes = modelFileSizeBytes,
            kvCacheBytes = kvCacheBytes,
            computeBufferBytes = computeBufferBytes,
            usedModelMetadata = true,
            fitsInMemory = availableMemoryMb?.let { estimatedBytes.toDouble() / BYTES_PER_MB <= it * SAFETY_MARGIN },
        )
    }

    private fun fallbackEstimate(
        modelFileSizeBytes: Long,
        availableMemoryMb: Double?,
    ): RuntimeMemoryEstimate {
        val estimatedBytes = (modelFileSizeBytes.toDouble() * FALLBACK_OVERHEAD_MULTIPLIER).toLong()
        return RuntimeMemoryEstimate(
            estimatedBytes = estimatedBytes,
            modelWeightsBytes = modelFileSizeBytes,
            kvCacheBytes = 0L,
            computeBufferBytes = 0L,
            usedModelMetadata = false,
            fitsInMemory = availableMemoryMb?.let { estimatedBytes.toDouble() / BYTES_PER_MB <= it * SAFETY_MARGIN },
        )
    }

    // Bytes per element for the exact upstream GGML types selected by each preset.
    private fun bytesPerElementK(preset: KvCachePreset): Double {
        return when (preset) {
            KvCachePreset.SAFE -> 2.0          // F16
            KvCachePreset.BALANCED -> 1.0625   // Q8_0
            KvCachePreset.AGGRESSIVE -> 1.0625 // Q8_0 (keys get more bits)
        }
    }

    private fun bytesPerElementV(preset: KvCachePreset): Double {
        return when (preset) {
            KvCachePreset.SAFE -> 2.0          // F16
            KvCachePreset.BALANCED -> 1.0625   // Q8_0
            KvCachePreset.AGGRESSIVE -> 0.5625 // Q4_0
        }
    }

    private const val METADATA_OVERHEAD_MULTIPLIER = 1.1
    private const val FALLBACK_OVERHEAD_MULTIPLIER = 1.2
    private const val SAFETY_MARGIN = 0.85
    private const val BYTES_PER_MB = 1024.0 * 1024.0
}
