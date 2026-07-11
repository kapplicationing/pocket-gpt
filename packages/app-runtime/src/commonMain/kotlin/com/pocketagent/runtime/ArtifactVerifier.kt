package com.pocketagent.runtime

import com.pocketagent.inference.ArtifactDistributionChannel
import com.pocketagent.inference.ArtifactVerificationResult
import com.pocketagent.inference.ArtifactVerificationStatus
import com.pocketagent.inference.ModelArtifact
import com.pocketagent.inference.ModelArtifactManager
import com.pocketagent.nativebridge.RuntimeModelRegistryPort
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

class ArtifactVerifier(
    private val config: RuntimeConfig,
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
    private val modelArtifactManager: ModelArtifactManager = ModelArtifactManager(),
) {
    private val artifactShaByFilePath: MutableMap<String, String> = mutableMapOf()

    init {
        val candidateModelIds = linkedSetOf<String>()
        candidateModelIds += modelRegistry.allMetadata().map { metadata -> metadata.modelId }
        candidateModelIds += config.artifactPayloadByModelId.keys
        candidateModelIds += config.artifactFilePathByModelId.keys
        candidateModelIds += config.artifactSha256ByModelId.keys
        candidateModelIds += config.artifactProvenanceIssuerByModelId.keys
        candidateModelIds += config.artifactProvenanceSignatureByModelId.keys

        val registeredModelIds = candidateModelIds.filter { modelId -> hasRuntimeConfigEntry(modelId) }
        registeredModelIds.forEach { modelId ->
            registerArtifact(
                modelId = modelId,
                fileName = resolveArtifactFileName(modelId),
            )
        }
        val defaultModelId = modelRegistry.defaultGetReadyModelId(profile = config.modelRuntimeProfile)
            ?.takeIf { candidate -> registeredModelIds.contains(candidate) }
            ?: registeredModelIds.firstOrNull()
        if (defaultModelId != null) {
            modelArtifactManager.setActiveModel(defaultModelId)
        }
    }

    fun manager(): ModelArtifactManager = modelArtifactManager

    internal fun registerRuntimeModelPaths(modelRegistry: RuntimeModelRegistryPort) {
        config.artifactFilePathByModelId.forEach { (modelId, rawPath) ->
            val path = rawPath.trim()
            if (path.isNotEmpty()) {
                modelRegistry.registerModelPath(modelId = modelId, absolutePath = path)
                ModelRuntimeMetadataSidecar.read(path)?.let { metadata ->
                    modelRegistry.registerModelMetadata(modelId = modelId, metadata = metadata)
                }
            }
        }
    }

    fun verifyArtifactOrThrow(modelId: String) {
        val result = verifyArtifactForModel(modelId)
        if (!result.passed) {
            throw RuntimeArtifactVerificationException(
                result = result,
                message = artifactVerificationFailureMessage(result),
            )
        }
    }

    fun verifyArtifactForModel(modelId: String): ArtifactVerificationResult {
        val filePath = config.artifactFilePathByModelId[modelId]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (filePath != null) {
            val path = Paths.get(filePath)
            val payloadPresent = runCatching {
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)
            val payloadSha256 = if (payloadPresent) {
                runCatching { sha256HexFromFile(path) }.getOrNull()
            } else {
                null
            }
            return modelArtifactManager.verifyArtifactForLoad(
                modelId = modelId,
                version = null,
                payload = null,
                payloadSha256 = payloadSha256,
                payloadPresent = payloadPresent,
                allowLastKnownGoodFallback = false,
                provenanceIssuer = config.artifactProvenanceIssuerByModelId[modelId].orEmpty(),
                provenanceSignature = config.artifactProvenanceSignatureByModelId[modelId].orEmpty(),
                runtimeCompatibility = config.runtimeCompatibilityTag,
            )
        }
        return modelArtifactManager.verifyArtifactForLoad(
            modelId = modelId,
            version = null,
            payload = config.artifactPayloadByModelId[modelId],
            allowLastKnownGoodFallback = false,
            provenanceIssuer = config.artifactProvenanceIssuerByModelId[modelId].orEmpty(),
            provenanceSignature = config.artifactProvenanceSignatureByModelId[modelId].orEmpty(),
            runtimeCompatibility = config.runtimeCompatibilityTag,
        )
    }

    fun artifactVerificationFailureMessage(result: ArtifactVerificationResult): String {
        return buildString {
            append("MODEL_ARTIFACT_VERIFICATION_ERROR:")
            append(result.status.name)
            append(":model=")
            append(result.modelId)
            append(";version=")
            append(result.version ?: "none")
            append(";expected_sha=")
            append(result.expectedSha256 ?: "none")
            append(";actual_sha=")
            append(result.actualSha256 ?: "none")
            append(";expected_issuer=")
            append(result.expectedIssuer ?: "none")
            append(";actual_issuer=")
            append(result.actualIssuer ?: "none")
            append(";expected_runtime=")
            append(result.expectedRuntimeCompatibility ?: "none")
            append(";actual_runtime=")
            append(result.actualRuntimeCompatibility ?: "none")
        }
    }

    private fun registerArtifact(modelId: String, fileName: String) {
        modelArtifactManager.registerArtifact(
            ModelArtifact(
                modelId = modelId,
                version = "1",
                fileName = fileName,
                expectedSha256 = config.artifactSha256ByModelId[modelId]?.trim().orEmpty(),
                distributionChannel = ArtifactDistributionChannel.SIDE_LOAD_MANUAL_INTERNAL,
                provenanceIssuer = config.artifactProvenanceIssuerByModelId[modelId].orEmpty(),
                provenanceSignature = config.artifactProvenanceSignatureByModelId[modelId].orEmpty(),
                runtimeCompatibility = config.runtimeCompatibilityTag,
            ),
        )
    }

    private fun hasRuntimeConfigEntry(modelId: String): Boolean {
        val hasPayload = config.artifactPayloadByModelId[modelId]?.isNotEmpty() == true
        val hasPath = config.artifactFilePathByModelId[modelId]
            ?.trim()
            ?.isNotEmpty() == true
        if (!hasPayload && !hasPath) {
            return false
        }
        val expectedSha = config.artifactSha256ByModelId[modelId]?.trim().orEmpty()
        if (expectedSha.isEmpty()) {
            return false
        }
        val issuer = config.artifactProvenanceIssuerByModelId[modelId]?.trim().orEmpty()
        if (issuer.isEmpty()) {
            return false
        }
        val signature = config.artifactProvenanceSignatureByModelId[modelId]?.trim().orEmpty()
        if (signature.isEmpty()) {
            return false
        }
        return true
    }

    private fun resolveArtifactFileName(modelId: String): String {
        val absolutePath = config.artifactFilePathByModelId[modelId]
            ?.trim()
            .orEmpty()
        val fromPath = absolutePath
            .takeIf { it.isNotEmpty() }
            ?.let { File(it).name }
            ?.takeIf { it.isNotEmpty() }
        return fromPath ?: "$modelId.gguf"
    }

    private fun sha256HexFromFile(path: Path): String {
        val normalizedPath = path.toAbsolutePath().normalize().toString()
        artifactShaByFilePath[normalizedPath]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(DEFAULT_SHA_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        val result = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        artifactShaByFilePath[normalizedPath] = result
        return result
    }

    companion object {
        private const val DEFAULT_SHA_BUFFER_SIZE: Int = 1024 * 1024
    }
}

class RuntimeArtifactVerificationException(
    val result: ArtifactVerificationResult,
    message: String,
) : IllegalStateException(message) {
    val verificationStatus: ArtifactVerificationStatus = result.status
    val errorCode: String = verificationStatus.name.lowercase()
}
