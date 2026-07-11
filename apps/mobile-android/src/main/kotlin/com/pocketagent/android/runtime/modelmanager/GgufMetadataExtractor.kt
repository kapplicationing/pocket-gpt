package com.pocketagent.android.runtime.modelmanager

import android.util.Log
import com.pocketagent.android.runtime.writeTextAtomically
import com.pocketagent.android.runtime.modelmanager.gguf.GgufMetadata
import com.pocketagent.android.runtime.modelmanager.gguf.GgufMetadataReaderImpl
import java.io.File
import org.json.JSONObject

/**
 * Extracts GGUF header metadata from a downloaded model file and writes it
 * into the companion `.meta.json` sidecar file. This provides runtime-useful
 * information (architecture, context length, embedding size, quantization, etc.)
 * without requiring the native inference engine to load the model.
 *
 * The extraction is streaming-only and typically completes in < 50ms even for
 * multi-GB files because only the GGUF header is parsed — tensor data is skipped.
 */
internal object GgufMetadataExtractor {

    private const val LOG_TAG = "GgufMetadataExtractor"

    private val reader = GgufMetadataReaderImpl(
        skipKeys = setOf(
            "tokenizer.ggml.tokens",
            "tokenizer.ggml.scores",
            "tokenizer.ggml.token_type",
            "tokenizer.ggml.merges",
        ),
        arraySummariseThreshold = 64,
    )

    /**
     * Reads GGUF header metadata from [modelFile] and appends it to the
     * existing `.meta.json` sidecar at [metadataFile].
     *
     * If the sidecar already exists, the GGUF fields are merged into the
     * existing JSON. If extraction fails, the existing metadata is left
     * untouched.
     *
     * @return true if GGUF metadata was successfully extracted and persisted.
     */
    @Suppress("TooGenericExceptionCaught")
    fun extractAndPersist(modelFile: File, metadataFile: File): Boolean {
        if (!modelFile.exists() || !modelFile.isFile) {
            Log.w(LOG_TAG, "Model file does not exist: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val gguf = inspect(modelFile).getOrThrow()
            val ggufJson = toJson(gguf)

            val existing = if (metadataFile.exists()) {
                runCatching { JSONObject(metadataFile.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            existing.put("gguf", ggufJson)
            existing.put("ggufExtractedAtEpochMs", System.currentTimeMillis())

            writeTextAtomically(metadataFile, existing.toString())
            Log.i(
                LOG_TAG,
                "Extracted GGUF metadata for ${modelFile.name}: " +
                    "arch=${gguf.architecture?.architecture}, ctx=${gguf.dimensions?.contextLength}",
            )
            true
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Failed to extract GGUF metadata from ${modelFile.name}: ${error.message}")
            false
        }
    }

    fun inspect(modelFile: File): Result<GgufMetadata> {
        return runCatching {
            require(modelFile.exists() && modelFile.isFile) {
                "Model file does not exist: ${modelFile.absolutePath}"
            }
            modelFile.inputStream().buffered().use(reader::readStructuredMetadata)
        }
    }

    fun inspectValidatedModelStructure(modelFile: File): Result<GgufMetadata> {
        return runCatching {
            require(modelFile.exists() && modelFile.isFile) {
                "Model file does not exist: ${modelFile.absolutePath}"
            }
            modelFile.inputStream().use { input ->
                reader.readValidatedModelStructure(input = input, fileSize = modelFile.length())
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun toJson(gguf: GgufMetadata): JSONObject {
        val json = JSONObject()
        json.put("version", gguf.version.label)
        json.put("tensorCount", gguf.tensorCount)
        json.put("kvCount", gguf.kvCount)

        json.put("name", gguf.basic.name ?: JSONObject.NULL)
        json.put("nameLabel", gguf.basic.nameLabel ?: JSONObject.NULL)
        json.put("sizeLabel", gguf.basic.sizeLabel ?: JSONObject.NULL)

        gguf.architecture?.let { arch ->
            val archJson = JSONObject()
            archJson.put("architecture", arch.architecture ?: JSONObject.NULL)
            archJson.put("fileType", arch.fileType ?: JSONObject.NULL)
            archJson.put("vocabSize", arch.vocabSize ?: JSONObject.NULL)
            archJson.put("finetune", arch.finetune ?: JSONObject.NULL)
            archJson.put("quantizationVersion", arch.quantizationVersion ?: JSONObject.NULL)
            json.put("architecture", archJson)
        }

        gguf.dimensions?.let { dims ->
            val dimsJson = JSONObject()
            dimsJson.put("contextLength", dims.contextLength ?: JSONObject.NULL)
            dimsJson.put("embeddingSize", dims.embeddingSize ?: JSONObject.NULL)
            dimsJson.put("blockCount", dims.blockCount ?: JSONObject.NULL)
            dimsJson.put("feedForwardSize", dims.feedForwardSize ?: JSONObject.NULL)
            json.put("dimensions", dimsJson)
        }

        gguf.attention?.let { attn ->
            val attnJson = JSONObject()
            attnJson.put("headCount", attn.headCount ?: JSONObject.NULL)
            attnJson.put("headCountKv", attn.headCountKv ?: JSONObject.NULL)
            attnJson.put("keyLength", attn.keyLength ?: JSONObject.NULL)
            attnJson.put("valueLength", attn.valueLength ?: JSONObject.NULL)
            json.put("attention", attnJson)
        }

        gguf.tokenizer?.let { tok ->
            val tokJson = JSONObject()
            tokJson.put("model", tok.model ?: JSONObject.NULL)
            tokJson.put("chatTemplate", tok.chatTemplate ?: JSONObject.NULL)
            tokJson.put("bosTokenId", tok.bosTokenId ?: JSONObject.NULL)
            tokJson.put("eosTokenId", tok.eosTokenId ?: JSONObject.NULL)
            json.put("tokenizer", tokJson)
        }

        gguf.rope?.let { rope ->
            val ropeJson = JSONObject()
            ropeJson.put("frequencyBase", rope.frequencyBase?.toDouble() ?: JSONObject.NULL)
            ropeJson.put("dimensionCount", rope.dimensionCount ?: JSONObject.NULL)
            ropeJson.put("scalingType", rope.scalingType ?: JSONObject.NULL)
            json.put("rope", ropeJson)
        }

        gguf.experts?.let { experts ->
            val expertsJson = JSONObject()
            expertsJson.put("count", experts.count ?: JSONObject.NULL)
            expertsJson.put("usedCount", experts.usedCount ?: JSONObject.NULL)
            json.put("experts", expertsJson)
        }

        return json
    }
}
