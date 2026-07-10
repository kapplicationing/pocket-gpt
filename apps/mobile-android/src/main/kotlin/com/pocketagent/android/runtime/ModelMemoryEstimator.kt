package com.pocketagent.android.runtime

import android.util.Log
import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import com.pocketagent.runtime.RuntimeModelMemoryEstimator
import org.json.JSONObject
import java.io.File

/**
 * Pre-flight memory estimation for model loading.
 *
 * When GGUF metadata is available (from the `.meta.json` sidecar):
 *   Total = (modelFileSize + kvCache + computeBuffer) × 1.1
 *
 * When GGUF metadata is NOT available:
 *   Total = modelFileSize × 1.2
 *
 * Formulas follow PocketPal's memoryEstimator.ts.
 */
object ModelMemoryEstimator {

    private const val LOG_TAG = "ModelMemoryEstimator"

    data class EstimationResult(
        val estimatedBytes: Long,
        val modelFileSizeBytes: Long,
        val kvCacheBytes: Long,
        val computeBufferBytes: Long,
        val usedGgufMetadata: Boolean,
        val availableMemoryBytes: Long?,
        val fitsInMemory: Boolean?,
    ) {
        val estimatedMb: Double get() = estimatedBytes.toDouble() / (1024.0 * 1024.0)
        val availableMemoryMb: Double? get() = availableMemoryBytes?.toDouble()?.div(1024.0 * 1024.0)
    }

    /**
     * Estimate memory required to load a model.
     *
     * @param modelFilePath absolute path to the .gguf file
     * @param nCtx context length to use (from RuntimeGenerationConfig)
     * @param kvCachePreset KV cache preset
     * @param nUbatch micro-batch size for compute buffer estimation
     * @param availableMemoryBytes optional current available device memory in bytes
     */
    fun estimate(
        modelFilePath: String,
        nCtx: Int = 2048,
        kvCachePreset: KvCachePreset = KvCachePreset.SAFE,
        nUbatch: Int = 512,
        availableMemoryBytes: Long? = null,
    ): EstimationResult {
        val modelFile = File(modelFilePath)
        val modelFileSizeBytes = if (modelFile.exists()) modelFile.length() else 0L

        val metadataFile = File("$modelFilePath.meta.json")
        val gguf = readGgufMetadata(metadataFile)

        return if (gguf != null) {
            estimateWithMetadata(
                modelFileSizeBytes = modelFileSizeBytes,
                gguf = gguf,
                nCtx = nCtx,
                kvCachePreset = kvCachePreset,
                nUbatch = nUbatch,
                availableMemoryBytes = availableMemoryBytes,
            )
        } else {
            estimateFallback(
                modelFileSizeBytes = modelFileSizeBytes,
                availableMemoryBytes = availableMemoryBytes,
            )
        }
    }

    private fun estimateWithMetadata(
        modelFileSizeBytes: Long,
        gguf: GgufFields,
        nCtx: Int,
        kvCachePreset: KvCachePreset,
        nUbatch: Int,
        availableMemoryBytes: Long?,
    ): EstimationResult {
        val estimate = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelFileSizeBytes,
            metadata = ModelRuntimeMetadata(
                layerCount = gguf.nLayers,
                sizeBytes = modelFileSizeBytes,
                embeddingSize = gguf.nEmbd,
                headCountKv = gguf.nHeadKv,
                keyLength = gguf.embdHeadK,
                valueLength = gguf.embdHeadV,
                vocabSize = gguf.nVocab,
            ),
            nCtx = nCtx,
            kvCachePreset = kvCachePreset,
            nUbatch = nUbatch,
            availableMemoryMb = availableMemoryBytes?.toDouble()?.div(MB),
        )

        Log.d(LOG_TAG, "ESTIMATE|model_mb=%.0f|kv_mb=%.0f|compute_mb=%.0f|total_mb=%.0f|avail_mb=%s|fits=%s".format(
            modelFileSizeBytes / MB,
            estimate.kvCacheBytes / MB,
            estimate.computeBufferBytes / MB,
            estimate.estimatedBytes / MB,
            availableMemoryBytes?.let { "%.0f".format(it / MB) } ?: "unknown",
            estimate.fitsInMemory?.toString() ?: "unknown",
        ))

        return EstimationResult(
            estimatedBytes = estimate.estimatedBytes,
            modelFileSizeBytes = modelFileSizeBytes,
            kvCacheBytes = estimate.kvCacheBytes,
            computeBufferBytes = estimate.computeBufferBytes,
            usedGgufMetadata = true,
            availableMemoryBytes = availableMemoryBytes,
            fitsInMemory = estimate.fitsInMemory,
        )
    }

    private fun estimateFallback(
        modelFileSizeBytes: Long,
        availableMemoryBytes: Long?,
    ): EstimationResult {
        val estimate = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelFileSizeBytes,
            metadata = null,
            nCtx = 2048,
            kvCachePreset = KvCachePreset.SAFE,
            nUbatch = 512,
            availableMemoryMb = availableMemoryBytes?.toDouble()?.div(MB),
        )

        Log.d(LOG_TAG, "ESTIMATE_FALLBACK|model_mb=%.0f|total_mb=%.0f|avail_mb=%s|fits=%s".format(
            modelFileSizeBytes / MB,
            estimate.estimatedBytes / MB,
            availableMemoryBytes?.let { "%.0f".format(it / MB) } ?: "unknown",
            estimate.fitsInMemory?.toString() ?: "unknown",
        ))

        return EstimationResult(
            estimatedBytes = estimate.estimatedBytes,
            modelFileSizeBytes = modelFileSizeBytes,
            kvCacheBytes = 0L,
            computeBufferBytes = 0L,
            usedGgufMetadata = false,
            availableMemoryBytes = availableMemoryBytes,
            fitsInMemory = estimate.fitsInMemory,
        )
    }

    private fun readGgufMetadata(metadataFile: File): GgufFields? {
        if (!metadataFile.exists()) return null
        return try {
            val root = JSONObject(metadataFile.readText())
            val gguf = root.optJSONObject("gguf") ?: return null
            val dims = gguf.optJSONObject("dimensions") ?: return null
            val attn = gguf.optJSONObject("attention") ?: return null
            val arch = gguf.optJSONObject("architecture")

            val nLayers = dims.optInt("blockCount", 0)
            val nEmbd = dims.optInt("embeddingSize", 0)
            val nHeadKv = attn.optInt("headCountKv", 0)
            val nVocab = arch?.optInt("vocabSize", 0) ?: 0
            // keyLength/valueLength in GGUF = per-head embedding dimensions
            val embdHeadK = attn.optInt("keyLength", 0)
            val embdHeadV = attn.optInt("valueLength", 0)

            val fields = GgufFields(
                nLayers = nLayers,
                nEmbd = nEmbd,
                nHeadKv = nHeadKv,
                nVocab = nVocab,
                embdHeadK = embdHeadK,
                embdHeadV = embdHeadV,
            )
            if (!fields.isValid()) {
                Log.w(LOG_TAG, "GGUF metadata has invalid/zero fields: $fields")
                null
            } else {
                fields
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read GGUF metadata from ${metadataFile.name}: ${e.message}")
            null
        }
    }

    private data class GgufFields(
        val nLayers: Int,
        val nEmbd: Int,
        val nHeadKv: Int,
        val nVocab: Int,
        val embdHeadK: Int,
        val embdHeadV: Int,
    ) {
        fun isValid(): Boolean = nLayers > 0 && nEmbd > 0 && nHeadKv > 0 &&
            nVocab > 0 && embdHeadK > 0 && embdHeadV > 0
    }

    private const val MB = 1024.0 * 1024.0
}
