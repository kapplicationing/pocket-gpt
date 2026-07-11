package com.pocketagent.android.runtime

import com.pocketagent.core.model.ModelArtifactRole
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun AndroidRuntimeProvisioningStore.ensureMigrated() {
    if (migrationEnsured) {
        return
    }
    synchronized(PROVISIONING_MIGRATION_LOCK) {
        if (migrationEnsured) {
            return
        }
        BASELINE_MODEL_SPECS.forEach(::migrateSpecIfNeeded)
        val discoveredDynamicIds = discoverDynamicModelIdsFromMetadata()
        val mergedDynamicIds = (readDynamicModelIds() + discoveredDynamicIds)
            .filterNot(::isBaselineModel)
            .toSortedSet()
        writeDynamicModelIds(mergedDynamicIds)
        mergedDynamicIds
            .map(::dynamicModelSpec)
            .forEach(::migrateSpecIfNeeded)
        recoverOwnedImportArtifacts(
            specs = BASELINE_MODEL_SPECS + mergedDynamicIds.map(::dynamicModelSpec),
        )
        // Run alias migration once per process start to self-heal stale metadata
        // without repeating full migration work on every snapshot call.
        runPathAliasMigrationLocked()
        migrationEnsured = true
    }
}

internal fun AndroidRuntimeProvisioningStore.recoverOwnedImportArtifacts(specs: List<ModelSpec>) {
    val managedDirectory = managedModelDirectory()
    val referencedPaths = specs
        .flatMap(::readStoredVersionEntries)
        .mapTo(mutableSetOf()) { entry -> normalizeAbsolutePath(entry.absolutePath) }
    cleanupOwnedImportArtifacts(
        managedDirectory = managedDirectory,
        referencedPaths = referencedPaths,
        activeImportPaths = PROVISIONING_ACTIVE_IMPORT_PATHS.toSet(),
        normalizePath = ::normalizeAbsolutePath,
        metadataFileFor = ::metadataFileFor,
    ).failedPaths.forEach { failedPath ->
        recordMigrationCorruption(
            ProvisioningRecoverySignal(
                code = "PROVISIONING_IMPORT_ARTIFACT_CLEANUP_FAILED",
                message = "An interrupted model import still uses storage.",
                technicalDetail = "path=$failedPath",
            ),
        )
    }
}

internal fun AndroidRuntimeProvisioningStore.migrateSpecIfNeeded(spec: ModelSpec) {
    if (prefs.contains(versionsKey(spec))) {
        return
    }
    val legacyPath = prefs.readOptional(spec.pathKey)
    val legacySha = prefs.readOptional(spec.shaKey)
    if (legacyPath.isNullOrBlank() || legacySha.isNullOrBlank()) {
        val discovered = discoverPersistedEntries(spec)
        prefs.edit()
            .putString(versionsKey(spec), encodeStoredVersions(discovered).toString())
            .apply()
        if (discovered.isEmpty()) {
            prefs.edit().remove(activeVersionKey(spec)).apply()
        } else {
            val latestVersion = discovered.maxByOrNull { it.importedAtEpochMs }?.version
            prefs.edit().putString(activeVersionKey(spec), latestVersion).apply()
        }
        prefs.edit()
            .remove(spec.pathKey)
            .remove(spec.shaKey)
            .remove(spec.issuerKey)
            .remove(spec.signatureKey)
            .remove(spec.importedAtKey)
            .apply()
        return
    }
    val version = PROVISIONING_INITIAL_MIGRATION_VERSION
    val issuer = prefs.readOptional(spec.issuerKey) ?: PROVISIONING_DEFAULT_ISSUER
    val signature = prefs.readOptional(spec.signatureKey)
        ?: sha256Hex("$issuer|${spec.modelId}|$legacySha|v1".encodeToByteArray())
    val importedAt = prefs.readOptionalLong(spec.importedAtKey) ?: System.currentTimeMillis()
    val fileSizeBytes = File(legacyPath).takeIf { it.exists() && it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
    val entry = StoredVersionEntry(
        version = version,
        absolutePath = normalizeAbsolutePath(legacyPath),
        sha256 = legacySha,
        provenanceIssuer = issuer,
        provenanceSignature = signature,
        runtimeCompatibility = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
        fileSizeBytes = fileSizeBytes,
        importedAtEpochMs = importedAt,
    )
    writeStoredVersionEntries(spec, listOf(entry))
    prefs.edit()
        .putString(activeVersionKey(spec), version)
        .remove(spec.pathKey)
        .remove(spec.shaKey)
        .remove(spec.issuerKey)
        .remove(spec.signatureKey)
        .remove(spec.importedAtKey)
        .apply()
}

internal fun AndroidRuntimeProvisioningStore.discoverDynamicModelIdsFromMetadata(): Set<String> {
    val metadataFiles = discoveryModelDirectories()
        .asSequence()
        .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
        .filter { file -> file.isFile && file.name.endsWith(PROVISIONING_METADATA_SUFFIX, ignoreCase = true) }
        .toList()
    if (metadataFiles.isEmpty()) {
        return emptySet()
    }
    return metadataFiles.mapNotNull { metadataFile ->
        val metadata = StoredModelSidecarMetadataStore.read(metadataFile)
        val json = runCatching { JSONObject(metadataFile.readText()) }.getOrNull()
        val modelId = metadata?.modelId?.trim().orEmpty().ifBlank {
            json?.optString("modelId", "")?.trim().orEmpty()
        }
        if (modelId.isBlank() || isBaselineModel(modelId)) {
            return@mapNotNull null
        }
        val primaryArtifact = metadata?.artifacts?.firstOrNull { artifact ->
            artifact.role == ModelArtifactRole.PRIMARY_GGUF
        }
        val absolutePath = normalizeAbsolutePath(
            primaryArtifact?.absolutePath.orEmpty().ifBlank {
                json?.optString("absolutePath", "")?.trim().orEmpty()
            },
        )
        val modelFile = File(absolutePath)
        if (absolutePath.isBlank() || !modelFile.exists() || !modelFile.isFile) {
            return@mapNotNull null
        }
        modelId
    }.toSet()
}

internal fun AndroidRuntimeProvisioningStore.allModelSpecs(
    dynamicIds: Set<String> = readDynamicModelIds(),
): List<ModelSpec> {
    val dynamicSpecs = dynamicIds
        .filterNot { modelId -> baselineModelIdSet.contains(modelId) }
        .sorted()
        .map { modelId -> dynamicModelSpec(modelId) }
    return BASELINE_MODEL_SPECS + dynamicSpecs
}

internal fun AndroidRuntimeProvisioningStore.modelSpecFor(modelId: String): ModelSpec {
    BASELINE_MODEL_SPECS.firstOrNull { it.modelId == modelId }?.let { return it }
    registerDynamicModelId(modelId)
    return dynamicModelSpec(modelId)
}

internal fun AndroidRuntimeProvisioningStore.isBaselineModel(modelId: String): Boolean {
    return baselineModelIdSet.contains(modelId)
}

internal fun AndroidRuntimeProvisioningStore.readDynamicModelIds(): Set<String> {
    return readDynamicModelIdsWithDiagnostics().ids
}

internal fun AndroidRuntimeProvisioningStore.readDynamicModelIdsWithDiagnostics(): DynamicModelIdsReadResult {
    val raw = prefs.getString(PROVISIONING_DYNAMIC_MODEL_IDS_KEY, null).orEmpty().trim()
    if (raw.isEmpty()) {
        return DynamicModelIdsReadResult(ids = emptySet())
    }
    return runCatching {
        val array = JSONArray(raw)
        val ids = buildSet {
            for (index in 0 until array.length()) {
                val modelId = array.optString(index, "").trim()
                if (modelId.isNotEmpty()) {
                    add(modelId)
                }
            }
        }
        DynamicModelIdsReadResult(ids = ids)
    }.getOrElse { error ->
        backupCorruptPayload(
            key = PROVISIONING_DYNAMIC_MODEL_IDS_KEY,
            raw = raw,
            code = "PROVISIONING_DYNAMIC_MODEL_IDS_CORRUPT",
            detail = "error=${error.message ?: "json_parse_failed"}",
        )
        prefs.edit().putString(PROVISIONING_DYNAMIC_MODEL_IDS_KEY, "[]").apply()
        DynamicModelIdsReadResult(
            ids = emptySet(),
            signal = ProvisioningRecoverySignal(
                code = "PROVISIONING_DYNAMIC_MODEL_IDS_CORRUPT",
                message = "Dynamic model registry was corrupted and has been reset.",
                technicalDetail = "error=${error.message ?: "json_parse_failed"}",
            ),
        )
    }
}

internal fun AndroidRuntimeProvisioningStore.registerDynamicModelId(modelId: String) {
    val normalized = modelId.trim()
    if (normalized.isEmpty() || isBaselineModel(normalized)) {
        return
    }
    val updated = (readDynamicModelIds() + normalized)
    writeDynamicModelIds(updated)
}

internal fun AndroidRuntimeProvisioningStore.unregisterDynamicModelId(modelId: String) {
    if (isBaselineModel(modelId)) {
        return
    }
    val updated = readDynamicModelIds()
        .filterNot { candidate -> candidate == modelId }
        .toSet()
    writeDynamicModelIds(updated)
}

internal fun AndroidRuntimeProvisioningStore.writeDynamicModelIds(ids: Set<String>) {
    val sanitized = ids
        .map { id -> id.trim() }
        .filter { id -> id.isNotEmpty() && !isBaselineModel(id) }
        .sorted()
    val encoded = JSONArray().apply {
        sanitized.forEach { value -> put(value) }
    }
    prefs.edit().putString(PROVISIONING_DYNAMIC_MODEL_IDS_KEY, encoded.toString()).apply()
}

internal fun AndroidRuntimeProvisioningStore.dynamicModelSpec(modelId: String): ModelSpec {
    val prefTag = "dyn_${sha256Hex(modelId.encodeToByteArray()).take(12)}"
    val safeFileName = modelId
        .trim()
        .ifEmpty { "model" }
        .replace(PROVISIONING_MODEL_TOKEN_SANITIZE_REGEX, "-")
        .lowercase(Locale.US)
    return ModelSpec(
        modelId = modelId,
        displayName = modelId,
        fileName = "$safeFileName.gguf",
        prefTag = prefTag,
        pathKey = "legacy_path_$prefTag",
        shaKey = "legacy_sha_$prefTag",
        issuerKey = "legacy_issuer_$prefTag",
        signatureKey = "legacy_signature_$prefTag",
        importedAtKey = "legacy_imported_at_$prefTag",
    )
}

internal fun AndroidRuntimeProvisioningStore.versionsKey(spec: ModelSpec): String {
    return "model_versions_json_${spec.prefTag}"
}

internal fun AndroidRuntimeProvisioningStore.activeVersionKey(spec: ModelSpec): String {
    return "model_active_version_${spec.prefTag}"
}

internal fun AndroidRuntimeProvisioningStore.fileNameForVersion(spec: ModelSpec, version: String): String {
    val suffix = sanitizeVersion(version)
    val dotIndex = spec.fileName.lastIndexOf('.')
    return if (dotIndex <= 0) {
        "${spec.fileName}-$suffix"
    } else {
        val base = spec.fileName.substring(0, dotIndex)
        val ext = spec.fileName.substring(dotIndex)
        "$base-$suffix$ext"
    }
}

internal fun AndroidRuntimeProvisioningStore.generatedVersion(prefix: String): String {
    return "$prefix-${System.currentTimeMillis()}"
}

internal fun AndroidRuntimeProvisioningStore.discoverPersistedEntries(spec: ModelSpec): List<StoredVersionEntry> {
    val metadataEntries = discoverPersistedEntriesFromMetadata(spec)
    if (metadataEntries.isNotEmpty()) {
        return metadataEntries
    }
    val files = discoveryModelDirectories()
        .asSequence()
        .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
        .filter { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
        .toList()
    if (files.isEmpty()) {
        return emptyList()
    }
    val discovered = mutableListOf<StoredVersionEntry>()
    var droppedCount = 0
    val droppedSamples = mutableListOf<String>()
    files.forEach { file ->
        val version = parseVersionFromFileName(spec, file.name)
        if (version.isNullOrBlank()) {
            droppedCount += 1
            if (droppedSamples.size < 3) {
                droppedSamples += "${file.name}:version_unrecognized"
            }
            return@forEach
        }
        val sha = runCatching { sha256HexFromFile(file) }.getOrNull()
        if (sha.isNullOrBlank()) {
            droppedCount += 1
            if (droppedSamples.size < 3) {
                droppedSamples += "${file.name}:sha_compute_failed"
            }
            return@forEach
        }
        val normalizedPath = normalizeAbsolutePath(file.absolutePath)
        discovered += StoredVersionEntry(
            version = sanitizeVersion(version),
            absolutePath = normalizedPath,
            sha256 = sha,
            provenanceIssuer = PROVISIONING_DEFAULT_ISSUER,
            provenanceSignature = sha256Hex("$PROVISIONING_DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray()),
            runtimeCompatibility = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
            fileSizeBytes = file.length().coerceAtLeast(0L),
            importedAtEpochMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }
    if (droppedCount > 0) {
        recordMigrationCorruption(
            ProvisioningRecoverySignal(
                code = "PROVISIONING_DISCOVERY_FILES_SKIPPED",
                message = "Some discovered model files were skipped during migration for ${spec.modelId}.",
                technicalDetail = "model=${spec.modelId};dropped=$droppedCount;" +
                    "samples=${droppedSamples.joinToString(",")}",
            ),
        )
    }
    return discovered.sortedByDescending { it.importedAtEpochMs }
}

// Migration discovery keeps corruption accounting and recovery evidence in one ordered pass.
@Suppress("CyclomaticComplexMethod")
internal fun AndroidRuntimeProvisioningStore.discoverPersistedEntriesFromMetadata(
    spec: ModelSpec,
): List<StoredVersionEntry> {
    val metadataFiles = discoveryModelDirectories()
        .asSequence()
        .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
        .filter { file -> file.isFile && file.name.endsWith(PROVISIONING_METADATA_SUFFIX, ignoreCase = true) }
        .toList()
    if (metadataFiles.isEmpty()) {
        return emptyList()
    }
    val discovered = mutableListOf<StoredVersionEntry>()
    var droppedCount = 0
    var parseFailureCount = 0
    val droppedSamples = mutableListOf<String>()
    metadataFiles.forEach { metadataFile ->
        val raw = runCatching { metadataFile.readText() }.getOrNull()
        if (raw == null) {
            droppedCount += 1
            if (droppedSamples.size < 3) {
                droppedSamples += "${metadataFile.name}:read_failed"
            }
            return@forEach
        }
        val json = runCatching { JSONObject(raw) }.getOrElse { error ->
            droppedCount += 1
            parseFailureCount += 1
            if (droppedSamples.size < 3) {
                droppedSamples += "${metadataFile.name}:json_parse_failed"
            }
            backupCorruptPayload(
                key = "migration_metadata_${spec.prefTag}_${metadataFile.name.hashCode()}",
                raw = raw,
                code = "PROVISIONING_METADATA_JSON_CORRUPT",
                detail = "model=${spec.modelId};file=${metadataFile.name};" +
                    "error=${error.message ?: "json_parse_failed"}",
            )
            return@forEach
        }
        val sidecar = StoredModelSidecarMetadataStore.read(metadataFile)
        val primaryArtifact = sidecar?.artifacts?.firstOrNull { artifact ->
            artifact.role == ModelArtifactRole.PRIMARY_GGUF
        }
        val modelId = sidecar?.modelId?.trim().orEmpty().ifBlank {
            json.optString("modelId", "").trim()
        }
        if (modelId != spec.modelId) {
            return@forEach
        }
        val absolutePath = normalizeAbsolutePath(
            primaryArtifact?.absolutePath.orEmpty().ifBlank {
                json.optString("absolutePath", "").trim()
            },
        )
        val sha = primaryArtifact?.expectedSha256.orEmpty().ifBlank {
            json.optString("sha256", "").trim()
        }
        val version = sanitizeVersion(
            sidecar?.version?.trim().orEmpty().ifBlank {
                json.optString("version", "").trim()
            },
        )
        val modelFile = File(absolutePath)
        if (isInvalidPersistedMetadata(absolutePath, modelFile, sha, version)) {
            droppedCount += 1
            if (droppedSamples.size < 3) {
                droppedSamples += "${metadataFile.name}:missing_required_fields"
            }
            return@forEach
        }
        val issuer = json.optString("provenanceIssuer", PROVISIONING_DEFAULT_ISSUER)
            .trim()
            .ifBlank { PROVISIONING_DEFAULT_ISSUER }
        discovered += StoredVersionEntry(
            version = version,
            absolutePath = absolutePath,
            sha256 = sha,
            provenanceIssuer = issuer,
            provenanceSignature = json.optString("provenanceSignature", "").trim().ifBlank {
                sha256Hex("$issuer|${spec.modelId}|$sha|v1".encodeToByteArray())
            },
            runtimeCompatibility = primaryArtifact?.runtimeCompatibility.orEmpty().ifBlank {
                json.optString("runtimeCompatibility", PROVISIONING_RUNTIME_COMPATIBILITY_TAG)
                    .trim()
                    .ifBlank { PROVISIONING_RUNTIME_COMPATIBILITY_TAG }
            },
            fileSizeBytes = primaryArtifact?.fileSizeBytes
                ?: json.optLong("fileSizeBytes", modelFile.length().coerceAtLeast(0L)).coerceAtLeast(0L),
            importedAtEpochMs = json.optLong("importedAtEpochMs", modelFile.lastModified()).takeIf { it > 0L }
                ?: System.currentTimeMillis(),
        )
    }
    if (droppedCount > 0) {
        recordMigrationCorruption(
            ProvisioningRecoverySignal(
                code = "PROVISIONING_DISCOVERY_METADATA_SKIPPED",
                message = "Some stored metadata entries were skipped during migration for ${spec.modelId}.",
                technicalDetail = buildString {
                    append("model=${spec.modelId};dropped=$droppedCount;parse_failures=$parseFailureCount")
                    if (droppedSamples.isNotEmpty()) {
                        append(";samples=${droppedSamples.joinToString(",")}")
                    }
                },
            ),
        )
    }
    return discovered.sortedByDescending { it.importedAtEpochMs }
}

private fun isInvalidPersistedMetadata(
    absolutePath: String,
    modelFile: File,
    sha: String,
    version: String,
): Boolean {
    return absolutePath.isBlank() || !modelFile.isFile || sha.isBlank() || version.isBlank()
}

internal fun AndroidRuntimeProvisioningStore.discoveryModelDirectories(): List<File> {
    val packageName = context.packageName
    val candidates = listOf(
        managedModelDirectory(),
        managedDownloadWorkspaceDirectory(),
        // Legacy internal storage paths (generation 1)
        File(context.filesDir, PROVISIONING_LEGACY_MODEL_DIR_NAME),
        File(context.filesDir, "models"),
        // Legacy cache directory (generation 1.5)
        File(context.cacheDir, "models"),
        // External media paths (generation 2)
        File("/sdcard/Android/media/$packageName/models"),
        File("/storage/emulated/0/Android/media/$packageName/models"),
        // Public downloads paths (generation 2)
        File("/sdcard/Download/$packageName/models"),
        File("/storage/emulated/0/Download/$packageName/models"),
    )
    val seen = mutableSetOf<String>()
    return candidates
        .map { candidate -> File(normalizeAbsolutePath(candidate.absolutePath)) }
        .filter { candidate -> seen.add(candidate.absolutePath) }
}

internal fun AndroidRuntimeProvisioningStore.parseVersionFromFileName(spec: ModelSpec, fileName: String): String? {
    val baseName = spec.fileName.substringBeforeLast('.', missingDelimiterValue = spec.fileName)
    val extension = spec.fileName.substringAfterLast('.', missingDelimiterValue = "gguf")

    val canonicalPrefix = "$baseName-"
    val canonicalSuffix = ".$extension"
    if (fileName.startsWith(canonicalPrefix) && fileName.endsWith(canonicalSuffix)) {
        val parsed = fileName.removePrefix(canonicalPrefix).removeSuffix(canonicalSuffix)
        if (parsed.isNotBlank()) {
            return parsed
        }
    }
    if (fileName.equals(spec.fileName, ignoreCase = true)) {
        return PROVISIONING_DISCOVERED_VERSION_FALLBACK
    }

    val legacyPrefix = "${sanitizeLegacyModelId(spec.modelId)}-"
    if (fileName.startsWith(legacyPrefix) && fileName.endsWith(".gguf")) {
        val parsed = fileName.removePrefix(legacyPrefix).removeSuffix(".gguf")
        if (parsed.isNotBlank()) {
            return parsed
        }
    }
    return null
}

internal fun AndroidRuntimeProvisioningStore.sanitizeLegacyModelId(modelId: String): String {
    return modelId.replace(PROVISIONING_MODEL_TOKEN_SANITIZE_REGEX, "_")
}
