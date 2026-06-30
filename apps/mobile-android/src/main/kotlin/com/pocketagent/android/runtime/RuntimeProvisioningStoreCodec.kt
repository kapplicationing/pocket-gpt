package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.GgufMetadataExtractor
import com.pocketagent.core.model.ModelParameterProfile
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.inference.ModelCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun AndroidRuntimeProvisioningStore.decodeStoredVersion(json: JSONObject): StoredVersionEntry? {
    val version = sanitizeVersion(json.optString("version", "").trim())
    val absolutePath = normalizeAbsolutePath(json.optString("absolutePath", "").trim())
    val sha = json.optString("sha256", "").trim()
    if (version.isEmpty() || absolutePath.isEmpty() || sha.isEmpty()) {
        return null
    }
    return StoredVersionEntry(
        version = version,
        absolutePath = absolutePath,
        sha256 = sha,
        provenanceIssuer = json.optString("provenanceIssuer", PROVISIONING_DEFAULT_ISSUER).trim().ifEmpty { PROVISIONING_DEFAULT_ISSUER },
        provenanceSignature = json.optString("provenanceSignature", "").trim(),
        runtimeCompatibility = json.optString("runtimeCompatibility", PROVISIONING_RUNTIME_COMPATIBILITY_TAG)
            .trim()
            .ifEmpty { PROVISIONING_RUNTIME_COMPATIBILITY_TAG },
        fileSizeBytes = json.optLong("fileSizeBytes", 0L).coerceAtLeast(0L),
        importedAtEpochMs = json.optLong("importedAtEpochMs", System.currentTimeMillis()),
    )
}

internal fun AndroidRuntimeProvisioningStore.encodeStoredVersions(entries: List<StoredVersionEntry>): JSONArray {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(
            JSONObject()
                .put("version", entry.version)
                .put("absolutePath", entry.absolutePath)
                .put("sha256", entry.sha256)
                .put("provenanceIssuer", entry.provenanceIssuer)
                .put("provenanceSignature", entry.provenanceSignature)
                .put("runtimeCompatibility", entry.runtimeCompatibility)
                .put("fileSizeBytes", entry.fileSizeBytes)
                .put("importedAtEpochMs", entry.importedAtEpochMs),
        )
    }
    return array
}

internal fun AndroidRuntimeProvisioningStore.writeManagedMetadataIfApplicable(
    modelId: String,
    entry: StoredVersionEntry,
    displayName: String? = null,
    sourceKind: ModelSourceKind? = null,
    sourceRef: ModelSourceRef? = null,
    promptProfileId: String? = null,
    installedArtifacts: List<InstalledArtifactDescriptor>? = null,
) {
    val target = File(entry.absolutePath)
    if (!target.exists() || !target.isFile || !isMetadataSidecarEligiblePath(target)) {
        return
    }
    val metadataFile = metadataFileFor(entry.absolutePath)
    val normalizedSpec = ModelCatalog.normalizedSpecFor(modelId)
    val variant = normalizedSpec?.variant(entry.version) ?: normalizedSpec?.variant(null)
    val primaryArtifact = InstalledArtifactDescriptor(
        artifactId = "$modelId::${entry.version}::primary",
        role = ModelArtifactRole.PRIMARY_GGUF,
        fileName = target.name,
        absolutePath = entry.absolutePath,
        expectedSha256 = entry.sha256,
        runtimeCompatibility = entry.runtimeCompatibility,
        fileSizeBytes = entry.fileSizeBytes,
    )
    val bundledArtifacts = variant?.artifactBundle
        ?.artifacts
        ?.filterNot { artifact -> artifact.role == ModelArtifactRole.PRIMARY_GGUF }
        ?.map { artifact ->
            val fileName = artifact.locator.fileName.orEmpty()
            InstalledArtifactDescriptor(
                artifactId = artifact.artifactId,
                role = artifact.role,
                fileName = fileName,
                absolutePath = resolveSiblingArtifactPath(
                    primaryAbsolutePath = entry.absolutePath,
                    fileName = fileName,
                ),
                expectedSha256 = artifact.sha256,
                runtimeCompatibility = artifact.runtimeCompatibility,
                fileSizeBytes = artifact.fileSizeBytes,
                required = artifact.required,
            )
        }
        .orEmpty()
    val resolvedArtifacts = installedArtifacts ?: (listOf(primaryArtifact) + bundledArtifacts)
    runCatching {
        StoredModelSidecarMetadataStore.write(
            metadataFile = metadataFile,
            metadata = StoredModelSidecarMetadata(
                modelId = modelId,
                version = entry.version,
                displayName = displayName,
                sourceKind = sourceKind ?: when (normalizedSpec?.source?.kind) {
                    null -> ModelSourceKind.LOCAL_IMPORT
                    else -> normalizedSpec.source.kind
                },
                sourceRef = sourceRef,
                promptProfileId = promptProfileId ?: normalizedSpec?.promptProfile?.profileId,
                artifacts = resolvedArtifacts,
            ),
        )
        GgufMetadataExtractor.extractAndPersist(
            modelFile = target,
            metadataFile = metadataFile,
        )
    }
}

internal fun AndroidRuntimeProvisioningStore.readSidecarMetadata(absolutePath: String): StoredModelSidecarMetadata? {
    return StoredModelSidecarMetadataStore.read(metadataFileFor(absolutePath))
}

internal fun StoredModelParameterSnapshot.toModelParameterProfile(): ModelParameterProfile {
    return ModelParameterProfile(
        architecture = architecture,
        quantization = quantization,
        quantizationVersion = quantizationVersion,
        contextLength = contextLength,
        layerCount = layerCount,
        embeddingSize = embeddingSize,
        headCount = headCount,
        headCountKv = headCountKv,
        vocabularySize = vocabularySize,
    )
}

internal fun AndroidRuntimeProvisioningStore.metadataFileFor(absolutePath: String): File {
    return File("$absolutePath$PROVISIONING_METADATA_SUFFIX")
}

private fun AndroidRuntimeProvisioningStore.resolveSiblingArtifactPath(
    primaryAbsolutePath: String,
    fileName: String,
): String? {
    if (fileName.isBlank()) {
        return null
    }
    return artifactSearchDirectories(anchorPath = primaryAbsolutePath)
        .asSequence()
        .map { directory -> File(directory, fileName) }
        .firstOrNull { candidate -> candidate.exists() && candidate.isFile }
        ?.absolutePath
}

private fun AndroidRuntimeProvisioningStore.isMetadataSidecarEligiblePath(target: File): Boolean {
    if (isManagedModelFile(target)) {
        return true
    }
    val packageName = context.packageName
    val appOwnedRoots = listOfNotNull(
        context.filesDir,
        context.cacheDir,
        context.getExternalFilesDir(null),
        File("/storage/emulated/0/Android/media/$packageName/models"),
        File("/storage/emulated/0/Download/$packageName/models"),
        File("/sdcard/Android/media/$packageName/models"),
        File("/sdcard/Download/$packageName/models"),
    )
    val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return false
    return appOwnedRoots.any { root ->
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
        targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")
    }
}
