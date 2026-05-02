package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class StoredModelParameterSnapshot(
    val architecture: String? = null,
    val quantization: String? = null,
    val quantizationVersion: Int? = null,
    val contextLength: Int? = null,
    val layerCount: Int? = null,
    val embeddingSize: Int? = null,
    val headCount: Int? = null,
    val headCountKv: Int? = null,
    val vocabularySize: Int? = null,
)

data class StoredModelSidecarMetadata(
    val modelId: String,
    val version: String,
    val sourceKind: ModelSourceKind = ModelSourceKind.LOCAL_IMPORT,
    val promptProfileId: String? = null,
    val artifacts: List<InstalledArtifactDescriptor> = emptyList(),
    val parameters: StoredModelParameterSnapshot = StoredModelParameterSnapshot(),
)

internal object StoredModelSidecarMetadataStore {
    private data class CachedRead(
        val lastModified: Long,
        val length: Long,
        val metadata: StoredModelSidecarMetadata?,
    )

    private val readCache = ConcurrentHashMap<String, CachedRead>()

    fun read(metadataFile: File): StoredModelSidecarMetadata? {
        if (!metadataFile.exists() || !metadataFile.isFile) {
            readCache.remove(metadataFile.absolutePath)
            return null
        }
        val cacheKey = metadataFile.absolutePath
        val lastModified = metadataFile.lastModified()
        val length = metadataFile.length()
        readCache[cacheKey]?.let { cached ->
            if (cached.lastModified == lastModified && cached.length == length) {
                return cached.metadata
            }
        }
        return runCatching {
            val json = JSONObject(metadataFile.readText())
            val artifacts = json.optJSONArray("artifacts")
                ?.let(::decodeArtifacts)
                .orEmpty()
            val parameters = json.optJSONObject("parameters")?.let(::decodeParameters)
                ?: decodeParametersFromLegacyGguf(json.optJSONObject("gguf"))
            StoredModelSidecarMetadata(
                modelId = json.optString("modelId", "").trim(),
                version = json.optString("version", "").trim(),
                sourceKind = parseSourceKind(json.optString("sourceKind", "").trim()) ?: ModelSourceKind.LOCAL_IMPORT,
                promptProfileId = json.optString("promptProfileId", "").trim().ifEmpty { null },
                artifacts = artifacts,
                parameters = parameters,
            )
        }.getOrNull().also { parsed ->
            readCache[cacheKey] = CachedRead(
                lastModified = lastModified,
                length = length,
                metadata = parsed,
            )
        }
    }

    fun write(
        metadataFile: File,
        metadata: StoredModelSidecarMetadata,
    ) {
        val json = JSONObject()
            .put("modelId", metadata.modelId)
            .put("version", metadata.version)
            .put("sourceKind", metadata.sourceKind.name)
            .put("promptProfileId", metadata.promptProfileId ?: JSONObject.NULL)
            .put("artifacts", encodeArtifacts(metadata.artifacts))
            .put("parameters", encodeParameters(metadata.parameters))
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(json.toString())
        readCache[metadataFile.absolutePath] = CachedRead(
            lastModified = metadataFile.lastModified(),
            length = metadataFile.length(),
            metadata = metadata,
        )
    }

    private fun encodeArtifacts(artifacts: List<InstalledArtifactDescriptor>): JSONArray {
        return JSONArray().apply {
            artifacts.forEach { artifact ->
                put(
                    JSONObject()
                        .put("artifactId", artifact.artifactId)
                        .put("role", artifact.role.name)
                        .put("fileName", artifact.fileName)
                        .put("absolutePath", artifact.absolutePath ?: JSONObject.NULL)
                        .put("expectedSha256", artifact.expectedSha256 ?: JSONObject.NULL)
                        .put("runtimeCompatibility", artifact.runtimeCompatibility ?: JSONObject.NULL)
                        .put("fileSizeBytes", artifact.fileSizeBytes ?: JSONObject.NULL)
                        .put("required", artifact.required),
                )
            }
        }
    }

    private fun decodeArtifacts(array: JSONArray): List<InstalledArtifactDescriptor> {
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val role = parseArtifactRole(json.optString("role", "").trim()) ?: continue
                add(
                    InstalledArtifactDescriptor(
                        artifactId = json.optString("artifactId", "").trim(),
                        role = role,
                        fileName = json.optString("fileName", "").trim(),
                        absolutePath = json.optString("absolutePath", "").trim().ifEmpty { null },
                        expectedSha256 = json.optString("expectedSha256", "").trim().ifEmpty { null },
                        runtimeCompatibility = json.optString("runtimeCompatibility", "").trim().ifEmpty { null },
                        fileSizeBytes = json.optLong("fileSizeBytes", -1L).takeIf { it >= 0L },
                        required = json.optBoolean("required", true),
                    ),
                )
            }
        }
    }

    private fun encodeParameters(parameters: StoredModelParameterSnapshot): JSONObject {
        return JSONObject()
            .put("architecture", parameters.architecture ?: JSONObject.NULL)
            .put("quantization", parameters.quantization ?: JSONObject.NULL)
            .put("quantizationVersion", parameters.quantizationVersion ?: JSONObject.NULL)
            .put("contextLength", parameters.contextLength ?: JSONObject.NULL)
            .put("layerCount", parameters.layerCount ?: JSONObject.NULL)
            .put("embeddingSize", parameters.embeddingSize ?: JSONObject.NULL)
            .put("headCount", parameters.headCount ?: JSONObject.NULL)
            .put("headCountKv", parameters.headCountKv ?: JSONObject.NULL)
            .put("vocabularySize", parameters.vocabularySize ?: JSONObject.NULL)
    }

    private fun decodeParameters(json: JSONObject): StoredModelParameterSnapshot {
        return StoredModelParameterSnapshot(
            architecture = json.optString("architecture", "").trim().ifEmpty { null },
            quantization = json.optString("quantization", "").trim().ifEmpty { null },
            quantizationVersion = json.optInt("quantizationVersion", -1).takeIf { it >= 0 },
            contextLength = json.optInt("contextLength", -1).takeIf { it >= 0 },
            layerCount = json.optInt("layerCount", -1).takeIf { it >= 0 },
            embeddingSize = json.optInt("embeddingSize", -1).takeIf { it >= 0 },
            headCount = json.optInt("headCount", -1).takeIf { it >= 0 },
            headCountKv = json.optInt("headCountKv", -1).takeIf { it >= 0 },
            vocabularySize = json.optInt("vocabularySize", -1).takeIf { it >= 0 },
        )
    }

    private fun decodeParametersFromLegacyGguf(gguf: JSONObject?): StoredModelParameterSnapshot {
        if (gguf == null) {
            return StoredModelParameterSnapshot()
        }
        val architecture = gguf.optJSONObject("architecture")
        val dimensions = gguf.optJSONObject("dimensions")
        val attention = gguf.optJSONObject("attention")
        return StoredModelParameterSnapshot(
            architecture = architecture?.optString("architecture", "")?.trim()?.ifEmpty { null },
            quantizationVersion = architecture?.optInt("quantizationVersion", -1)?.takeIf { it >= 0 },
            contextLength = dimensions?.optInt("contextLength", -1)?.takeIf { it >= 0 },
            layerCount = dimensions?.optInt("blockCount", -1)?.takeIf { it >= 0 },
            embeddingSize = dimensions?.optInt("embeddingSize", -1)?.takeIf { it >= 0 },
            headCount = attention?.optInt("headCount", -1)?.takeIf { it >= 0 },
            headCountKv = attention?.optInt("headCountKv", -1)?.takeIf { it >= 0 },
            vocabularySize = architecture?.optInt("vocabSize", -1)?.takeIf { it >= 0 },
        )
    }

    private fun parseArtifactRole(raw: String): ModelArtifactRole? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching { ModelArtifactRole.valueOf(raw.uppercase()) }.getOrNull()
    }

    private fun parseSourceKind(raw: String): ModelSourceKind? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching { ModelSourceKind.valueOf(raw.uppercase()) }.getOrNull()
    }
}
