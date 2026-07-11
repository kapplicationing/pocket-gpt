package com.pocketagent.android.runtime

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelDownloadWorker
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry
import com.pocketagent.runtime.RuntimeConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

class AndroidRuntimeProvisioningStore(
    internal val context: Context,
) {
    internal val prefs = context.getSharedPreferences(PROVISIONING_PREFS_NAME, Context.MODE_PRIVATE)
    private val modelLocks: MutableMap<String, Any> = mutableMapOf()
    private val migrationCorruptionSignals: MutableList<ProvisioningRecoverySignal> = mutableListOf()
    internal val baselineModelIdSet = BASELINE_MODEL_SPECS.mapTo(linkedSetOf()) { it.modelId }
    private val runtimeProfile: ModelRuntimeProfile = ModelRuntimeProfile.PROD
    private val startupCandidateModelIds: Set<String> = ModelRegistry.default()
        .startupPolicy(profile = runtimeProfile)
        .candidateModelIds
        .toSet()
        .ifEmpty { baselineModelIdSet }
    @Volatile
    internal var migrationEnsured: Boolean = false
    // Storage summary is cached for short repeated UI reads, but every owning
    // mutation that can change model/download bytes explicitly invalidates it.
    @Volatile
    private var storageSummaryCache: StorageSummary? = null
    @Volatile
    private var storageSummaryCacheTimeMs: Long = 0L
    // Storage roots are process-lifetime OS values; Android storage volume changes
    // require process restart or explicit future invalidation.
    private val externalStorageRootsCache = AtomicReference<Array<File?>?>(null)
    private val managedStorageRootCache = AtomicReference<File?>(null)
    // Last-loaded model is prefs-backed; writes update this cache and removals clear it.
    @Volatile
    private var lastLoadedModelCacheResolved: Boolean = false
    @Volatile
    private var lastLoadedModelCache: LastLoadedModelRef? = null

    // Snapshot assembly stays cohesive so recovery signals and model state share one read boundary.
    @Suppress("CyclomaticComplexMethod")
    fun snapshot(): RuntimeProvisioningSnapshot {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.snapshot")
        ensureMigrated()
        val corruptionSignals = mutableListOf<ProvisioningRecoverySignal>()
        corruptionSignals += drainMigrationCorruptionSignals()
        val storageRoot = managedStorageRoot()
        val storageRootLabel = describeStorageRoot(storageRoot)
        val dynamicIdsResult = readDynamicModelIdsWithDiagnostics()
        dynamicIdsResult.signal?.let { corruptionSignals += it }
        val specs = allModelSpecs(dynamicIdsResult.ids)
        val models = specs.map { spec ->
            val versionResult = readInstalledVersionsWithDiagnostics(spec)
            versionResult.signal?.let { corruptionSignals += it }
            val versions = versionResult.versions
            val activeVersionKey = activeVersionKey(spec)
            val activeVersion = prefs.readOptional(activeVersionKey)
            val active = selectRuntimeConfigDescriptor(activeVersion, versions)
            if (active != null && active.version != activeVersion) {
                prefs.edit().putString(activeVersionKey, active.version).apply()
            }
            val resolvedActiveVersion = active?.version ?: activeVersion
            staleActiveVersionSignal(
                modelId = spec.modelId,
                activeVersion = activeVersion,
                installedVersions = versions,
            )?.let { signal -> corruptionSignals += signal }
            val activePath = active?.absolutePath?.trim().orEmpty()
            val activeFile = activePath.takeIf { it.isNotBlank() }?.let(::File)
            val localFileMissing = activeFile
                ?.let { file -> !file.exists() || !file.isFile }
                ?: false
            val fallbackAliasPath = if (localFileMissing && activePath.isNotBlank()) {
                resolveFallbackModelPath(spec = spec, version = active?.version, missingPath = activePath)
            } else {
                null
            }
            if (fallbackAliasPath != null) {
                corruptionSignals += ProvisioningRecoverySignal(
                    code = "MODEL_PATH_ALIAS_STALE",
                    message = "Model path alias is stale for ${spec.modelId}. " +
                        "Refresh runtime checks to re-link local files.",
                    technicalDetail = "model=${spec.modelId};missing_path=$activePath;fallback_path=$fallbackAliasPath",
                )
            }
            if (localFileMissing) {
                corruptionSignals += ProvisioningRecoverySignal(
                    code = "MODEL_LOCAL_FILE_MISSING",
                    message = "Active model file is missing for ${spec.modelId}. Re-download or import a local file.",
                    technicalDetail = "model=${spec.modelId};path=$activePath",
                )
            }
            if (active != null && !localFileMissing && activeFile != null) {
                corruptionSignals += activeMetadataSignals(
                    spec = spec,
                    activeVersion = active,
                    activeFile = activeFile,
                )
            }
            val versionPathOrigins = versions.associate { descriptor ->
                descriptor.version to resolvePathOriginForVersion(
                    version = descriptor.version,
                    absolutePath = descriptor.absolutePath,
                )
            }
            val pathOrigin = active?.let { descriptor ->
                versionPathOrigins[descriptor.version]
            } ?: resolvePathOriginForVersion(
                version = resolvedActiveVersion,
                absolutePath = activePath,
            )
            val resolvedPathOrigin = pathOrigin ?: ModelPathOrigin.MANAGED
            ProvisionedModelState(
                modelId = spec.modelId,
                displayName = spec.displayName,
                fileName = spec.fileName,
                absolutePath = activePath.ifBlank { null },
                sha256 = active?.sha256,
                importedAtEpochMs = active?.importedAtEpochMs,
                activeVersion = resolvedActiveVersion,
                installedVersions = versions,
                pathOrigin = resolvedPathOrigin,
                versionPathOrigins = versionPathOrigins,
                storageRootLabel = if (resolvedPathOrigin == ModelPathOrigin.MANAGED) storageRootLabel else null,
                localFileMissing = localFileMissing,
            )
        }
        return RuntimeProvisioningSnapshot(
            models = models,
            storageSummary = storageSummary(),
            requiredModelIds = startupCandidateModelIds,
            storageRootLabel = storageRootLabel,
            recoverableCorruptions = corruptionSignals.distinctBy { signal ->
                "${signal.code}:${signal.technicalDetail}"
            },
        )
    }

    // This transaction normalizes provider failures and rolls back files for every throwable, including cancellation.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun importModel(
        modelId: String,
        sourceUri: Uri,
        version: String = generatedVersion(prefix = "manual"),
    ): RuntimeModelImportResult {
        val spec = modelSpecFor(modelId)
        return withContext(Dispatchers.IO) {
            ensureMigrated()
            val importContext = currentCoroutineContext()
            val destinationDir = managedModelDirectory()
            val tempFile = File(
                destinationDir,
                "$PROVISIONING_IMPORT_TEMP_PREFIX${UUID.randomUUID()}$PROVISIONING_IMPORT_TEMP_SUFFIX",
            )
            val normalizedTempPath = normalizeAbsolutePath(tempFile.absolutePath)
            PROVISIONING_ACTIVE_IMPORT_PATHS += normalizedTempPath
            var normalizedDestinationPath: String? = null
            val digest = MessageDigest.getInstance("SHA-256")

            try {
                val copiedBytes = try {
                    openModelAssetDescriptor(modelId = modelId, sourceUri = sourceUri).use { descriptor ->
                        copyModelInputToTempFileCancellable(
                            input = descriptor.createInputStream(),
                            tempFile = tempFile,
                            digest = digest,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: RuntimeDomainException) {
                    throw error
                } catch (error: Exception) {
                    throw unreadableImportSource(modelId = modelId, sourceUri = sourceUri, cause = error)
                }
                importContext.ensureActive()
                val sha = digest.digest().toHex()
                ModelImportIdentityValidator.validateOrThrow(modelId = modelId, modelFile = tempFile)
                val destinationFile = importedModelDestinationFile(
                    destinationDir = destinationDir,
                    spec = spec,
                    sha = sha,
                )
                val destinationPath = normalizeAbsolutePath(destinationFile.absolutePath)
                normalizedDestinationPath = destinationPath
                PROVISIONING_ACTIVE_IMPORT_PATHS += destinationPath

                withModelLock(modelId) {
                    importContext.ensureActive()
                    ensureMigrated()
                    val previousEntries = readStoredVersionEntries(spec)
                    val publishedByThisImport = if (destinationFile.exists()) {
                        val existingSha = runCatching { sha256HexFromFile(destinationFile) }.getOrNull()
                        if (existingSha != sha) {
                            throw importPersistFailure(
                                modelId = modelId,
                                destinationFile = destinationFile,
                                detail = "content_address_collision_or_corruption",
                            )
                        }
                        tempFile.delete()
                        false
                    } else {
                        if (!publishImportedModel(tempFile = tempFile, destinationFile = destinationFile)) {
                            destinationFile.takeIf { it.exists() }?.delete()
                            throw importPersistFailure(modelId = modelId, destinationFile = destinationFile)
                        }
                        true
                    }

                    try {
                        val result = upsertInstalledVersion(
                            spec = spec,
                            version = version,
                            absolutePath = destinationFile.absolutePath,
                            sha = sha,
                            provenanceIssuer = PROVISIONING_DEFAULT_ISSUER,
                            provenanceSignature = sha256Hex(
                                "$PROVISIONING_DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray(),
                            ),
                            runtimeCompatibility = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
                            fileSizeBytes = copiedBytes,
                            makeActive = false,
                            durableCommit = true,
                        )
                        val remainingEntries = readStoredVersionEntries(spec)
                        previousEntries
                            .filter { previous ->
                                remainingEntries.none { current ->
                                    current.absolutePath == previous.absolutePath
                                }
                            }
                            .forEach { previous ->
                                deleteManagedFileIfOrphaned(
                                    absolutePath = previous.absolutePath,
                                    remainingEntries = remainingEntries,
                                )
                            }
                        result
                    } catch (error: AmbiguousImportMetadataException) {
                        throw error.failure
                    } catch (error: Throwable) {
                        if (publishedByThisImport) {
                            destinationFile.takeIf { it.exists() }?.delete()
                            metadataFileFor(destinationFile.absolutePath).takeIf { it.exists() }?.delete()
                        }
                        throw error
                    }
                }
            } finally {
                tempFile.takeIf { it.exists() }?.delete()
                PROVISIONING_ACTIVE_IMPORT_PATHS -= normalizedTempPath
                normalizedDestinationPath?.let(PROVISIONING_ACTIVE_IMPORT_PATHS::remove)
            }
        }
    }

    suspend fun seedModelFromAbsolutePath(
        modelId: String,
        absolutePath: String,
        version: String = generatedVersion(prefix = "seed"),
    ): RuntimeModelImportResult {
        val spec = modelSpecFor(modelId)
        return withContext(Dispatchers.IO) {
            withModelLock(modelId) {
                ensureMigrated()
                val file = File(absolutePath)
                require(file.exists() && file.isFile) {
                    "Model path does not exist: $absolutePath"
                }
                val sha = sha256HexFromFile(file)
                val result = upsertInstalledVersion(
                    spec = spec,
                    version = version,
                    absolutePath = file.absolutePath,
                    sha = sha,
                    provenanceIssuer = PROVISIONING_DEFAULT_ISSUER,
                    provenanceSignature = sha256Hex(
                        "$PROVISIONING_DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray(),
                    ),
                    runtimeCompatibility = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
                    fileSizeBytes = file.length().coerceAtLeast(0L),
                    makeActive = false,
                )
                result
            }
        }
    }

    fun installDownloadedModel(
        modelId: String,
        version: String,
        absolutePath: String,
        sha256: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        runtimeCompatibility: String,
        fileSizeBytes: Long,
        makeActive: Boolean = false,
        sourceKind: ModelSourceKind? = null,
        sourceRef: ModelSourceRef? = null,
        displayName: String? = null,
        promptProfileId: String? = null,
        installedArtifacts: List<InstalledArtifactDescriptor>? = null,
    ): RuntimeModelImportResult {
        val spec = modelSpecFor(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val normalizedIssuer = provenanceIssuer.ifBlank { PROVISIONING_DEFAULT_ISSUER }
            val result = upsertInstalledVersion(
                spec = spec,
                version = version,
                absolutePath = absolutePath,
                sha = sha256,
                provenanceIssuer = normalizedIssuer,
                provenanceSignature = provenanceSignature.ifBlank {
                    sha256Hex("$normalizedIssuer|${spec.modelId}|$sha256|v1".encodeToByteArray())
                },
                runtimeCompatibility = runtimeCompatibility.ifBlank { PROVISIONING_RUNTIME_COMPATIBILITY_TAG },
                fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
                makeActive = makeActive,
                sourceKind = sourceKind,
                sourceRef = sourceRef,
                displayName = displayName,
                promptProfileId = promptProfileId,
                installedArtifacts = installedArtifacts,
            )
            val mirrored = runCatching {
                mirrorInstalledModelToDownloads(
                    spec = spec,
                    version = result.version,
                    sourceFile = File(result.absolutePath),
                )
            }.getOrElse { error ->
                Log.w(
                    LOG_TAG,
                    "Failed to mirror downloaded model into Downloads for " +
                        "${spec.modelId}@${result.version}: ${error.message}",
                )
                false
            }
            if (!mirrored) {
                Log.w(
                    LOG_TAG,
                    "Skipping Downloads mirror for ${spec.modelId}@${result.version}; " +
                        "install remains available in managed storage.",
                )
            }
            invalidateStorageSummaryCache()
            result
        }
    }

    fun managedModelDirectory(): File {
        return File(managedStorageRoot(), PROVISIONING_MANAGED_MODELS_DIR_NAME).apply { mkdirs() }
    }

    fun managedDownloadWorkspaceDirectory(): File {
        return File(managedStorageRoot(), ModelDownloadWorker.DOWNLOAD_DIR).apply { mkdirs() }
    }

    fun destinationFileForVersion(modelId: String, version: String): File {
        val spec = modelSpecFor(modelId)
        return File(managedModelDirectory(), fileNameForVersion(spec, version))
    }

    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.listInstalledVersions")
        ensureMigrated()
        return readInstalledVersions(modelSpecFor(modelId))
    }

    fun recordLastLoadedModel(modelId: String, version: String) {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.recordLastLoadedModel")
        val normalizedModelId = modelId.trim()
        val normalizedVersion = version.trim()
        if (normalizedModelId.isBlank() || normalizedVersion.isBlank()) {
            return
        }
        prefs.edit()
            .putString(PROVISIONING_LAST_LOADED_MODEL_ID_KEY, normalizedModelId)
            .putString(PROVISIONING_LAST_LOADED_MODEL_VERSION_KEY, normalizedVersion)
            .apply()
        lastLoadedModelCache = LastLoadedModelRef(modelId = normalizedModelId, version = normalizedVersion)
        lastLoadedModelCacheResolved = true
    }

    fun clearLastLoadedModel() {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.clearLastLoadedModel")
        prefs.edit()
            .remove(PROVISIONING_LAST_LOADED_MODEL_ID_KEY)
            .remove(PROVISIONING_LAST_LOADED_MODEL_VERSION_KEY)
            .apply()
        lastLoadedModelCache = null
        lastLoadedModelCacheResolved = true
    }

    internal fun lastLoadedModel(): LastLoadedModelRef? {
        if (lastLoadedModelCacheResolved) {
            return lastLoadedModelCache
        }
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.lastLoadedModel")
        ensureMigrated()
        val modelId = prefs.readOptional(PROVISIONING_LAST_LOADED_MODEL_ID_KEY) ?: run {
            lastLoadedModelCache = null
            lastLoadedModelCacheResolved = true
            return null
        }
        val version = prefs.readOptional(PROVISIONING_LAST_LOADED_MODEL_VERSION_KEY) ?: run {
            lastLoadedModelCache = null
            lastLoadedModelCacheResolved = true
            return null
        }
        val installed = runCatching { readInstalledVersions(modelSpecFor(modelId)) }
            .getOrElse {
                clearLastLoadedModel()
                return null
            }
        val matched = installed.firstOrNull { descriptor -> descriptor.version == version } ?: run {
            clearLastLoadedModel()
            return null
        }
        val file = File(matched.absolutePath)
        if (!file.exists() || !file.isFile) {
            clearLastLoadedModel()
            return null
        }
        return LastLoadedModelRef(modelId = modelId, version = version).also { ref ->
            lastLoadedModelCache = ref
            lastLoadedModelCacheResolved = true
        }
    }

    fun setActiveVersion(modelId: String, version: String): Boolean {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.setActiveVersion")
        val spec = modelSpecFor(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val versions = readInstalledVersions(spec)
            versions.firstOrNull { it.version == version } ?: return@withModelLock false
            prefs.edit()
                .putString(activeVersionKey(spec), version)
                .apply()
            true
        }
    }

    fun clearActiveVersion(modelId: String): Boolean {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.clearActiveVersion")
        val spec = modelSpecFor(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val activeVersion = prefs.readOptional(activeVersionKey(spec)) ?: return@withModelLock false
            val versions = readInstalledVersions(spec)
            if (versions.none { descriptor -> descriptor.version == activeVersion }) {
                prefs.edit()
                    .remove(activeVersionKey(spec))
                    .apply()
                return@withModelLock true
            }
            prefs.edit()
                .remove(activeVersionKey(spec))
                .apply()
            true
        }
    }

    fun removeVersion(modelId: String, version: String): Boolean {
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.removeVersion")
        val spec = modelSpecFor(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val versions = readStoredVersionEntries(spec).toMutableList()
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            if (activeVersion == version) {
                return@withModelLock false
            }
            val target = versions.firstOrNull { it.version == version } ?: return@withModelLock false
            val installedDescriptors = readInstalledVersions(spec)
            val targetDescriptor = installedDescriptors.firstOrNull { descriptor -> descriptor.version == version }
            versions.removeIf { it.version == version }
            writeStoredVersionEntries(spec, versions)
            if (prefs.readOptional(activeVersionKey(spec)) == version) {
                prefs.edit().remove(activeVersionKey(spec)).apply()
            }
            if (lastLoadedModelCache?.modelId == modelId && lastLoadedModelCache?.version == version) {
                lastLoadedModelCache = null
                lastLoadedModelCacheResolved = false
            }
            deleteManagedFileIfOrphaned(
                absolutePath = target.absolutePath,
                remainingEntries = versions,
            )
            targetDescriptor?.let { descriptor ->
                deleteInstalledArtifactsIfOrphaned(
                    removedArtifacts = descriptor.artifacts
                        .filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF },
                    removedPrimaryPath = target.absolutePath,
                )
            }
            val lastLoaded = lastLoadedModel()
            if (lastLoaded != null && lastLoaded.modelId == modelId && lastLoaded.version == version) {
                clearLastLoadedModel()
            }
            if (versions.isEmpty() && !isBaselineModel(modelId)) {
                unregisterDynamicModelId(modelId)
            }
            invalidateStorageSummaryCache()
            true
        }
    }

    private fun deleteInstalledArtifactsIfOrphaned(
        removedArtifacts: List<com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor>,
        removedPrimaryPath: String,
    ) {
        if (removedArtifacts.isEmpty()) {
            return
        }
        val remainingArtifactPaths = allModelSpecs()
            .flatMap { candidateSpec -> readInstalledVersions(candidateSpec) }
            .flatMap { descriptor ->
                descriptor.artifacts
                    .mapNotNull { artifact -> artifact.absolutePath }
            }
            .filter { path -> path != removedPrimaryPath }
            .toSet()
        removedArtifacts
            .mapNotNull { artifact -> artifact.absolutePath?.takeIf { it.isNotBlank() } }
            .filterNot { path -> remainingArtifactPaths.contains(path) }
            .forEach { path ->
                val file = File(path)
                if (isManagedModelFile(file)) {
                    file.takeIf { it.exists() && it.isFile }?.delete()
                }
            }
    }

    fun storageSummary(): StorageSummary {
        val now = System.currentTimeMillis()
        storageSummaryCache?.let { cached ->
            if (now - storageSummaryCacheTimeMs < 2_000L) return cached
        }
        MainThreadGuard.assertNotMainThread("AndroidRuntimeProvisioningStore.storageSummary")
        ensureMigrated()
        val storageRoot = managedStorageRoot()
        val usedByModels = allModelSpecs()
            .flatMap { spec -> readInstalledVersions(spec) }
            .flatMap { descriptor ->
                descriptor.artifacts.map { artifact ->
                    val absolutePath = artifact.absolutePath
                    absolutePath to (
                        artifact.fileSizeBytes
                            ?: absolutePath?.let { path -> File(path).takeIf { it.exists() && it.isFile }?.length() }
                            ?: 0L
                        )
                }
            }
            .filter { (path, _) -> !path.isNullOrBlank() }
            .associate { (path, size) -> path!! to size.coerceAtLeast(0L) }
            .values
            .sum()
        val tempDownloadDir = managedDownloadWorkspaceDirectory()
        val tempBytes = tempDownloadDir
            .takeIf { it.exists() && it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.sumOf { it.length().coerceAtLeast(0L) }
            ?: 0L
        val result = StorageSummary(
            totalBytes = storageRoot.totalSpace.coerceAtLeast(0L),
            freeBytes = storageRoot.usableSpace.coerceAtLeast(0L),
            usedByModelsBytes = usedByModels,
            tempDownloadBytes = tempBytes,
        )
        storageSummaryCache = result
        storageSummaryCacheTimeMs = System.currentTimeMillis()
        return result
    }

    fun runtimeConfig(): RuntimeConfig {
        ensureMigrated()
        val payloadByModelId = mutableMapOf<String, ByteArray>()
        val filePathByModelId = mutableMapOf<String, String>()
        val shaByModelId = mutableMapOf<String, String>()
        val issuerByModelId = mutableMapOf<String, String>()
        val signatureByModelId = mutableMapOf<String, String>()

        allModelSpecs().forEach { spec ->
            val versions = readInstalledVersions(spec)
            val activeVersionKey = activeVersionKey(spec)
            val activeVersion = prefs.readOptional(activeVersionKey)
            val active = selectRuntimeConfigDescriptor(activeVersion, versions)
            if (active != null && active.version != activeVersion) {
                prefs.edit().putString(activeVersionKey, active.version).apply()
            }

            val path = active?.absolutePath?.trim().orEmpty()
            val sha = active?.sha256?.takeIf { it.isNotBlank() }.orEmpty()
            val issuer = active?.provenanceIssuer?.takeIf { it.isNotBlank() } ?: PROVISIONING_DEFAULT_ISSUER
            val signature = active?.provenanceSignature?.takeIf { it.isNotBlank() }.orEmpty()

            filePathByModelId[spec.modelId] = path
            shaByModelId[spec.modelId] = sha
            issuerByModelId[spec.modelId] = issuer
            signatureByModelId[spec.modelId] = signature
        }

        return RuntimeConfig(
            artifactPayloadByModelId = payloadByModelId,
            artifactFilePathByModelId = filePathByModelId,
            artifactSha256ByModelId = shaByModelId,
            artifactProvenanceIssuerByModelId = issuerByModelId,
            artifactProvenanceSignatureByModelId = signatureByModelId,
            runtimeCompatibilityTag = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
            requireNativeRuntimeForStartupChecks = true,
            prefixCacheEnabled = true,
            prefixCacheStrict = false,
            responseCacheTtlSec = 0L,
            responseCacheMaxEntries = 0,
            streamContractV2Enabled = true,
            modelRuntimeProfile = runtimeProfile,
        )
    }

    fun resolveMmProjPath(modelId: String): String? {
        val activeVersion = listInstalledVersions(modelId)
            .firstOrNull { descriptor -> descriptor.isActive }
        val mmProjArtifact = activeVersion
            ?.artifacts
            ?.firstOrNull { artifact -> artifact.role == ModelArtifactRole.MMPROJ }
        val explicitPath = mmProjArtifact?.absolutePath?.takeIf { candidate ->
            val file = File(candidate)
            file.exists() && file.isFile
        }
        if (explicitPath != null) {
            return explicitPath
        }
        val declaredFileName = mmProjArtifact?.fileName
            ?.takeIf { it.isNotBlank() }
            ?: ModelCatalog.mmProjFileNameFor(modelId)
            ?: return null
        return resolveBundledArtifactPath(declaredFileName)
    }

    fun expectedRuntimeCompatibilityTag(): String = PROVISIONING_RUNTIME_COMPATIBILITY_TAG

    internal fun artifactSearchDirectories(anchorPath: String? = null): List<File> {
        val directories = linkedMapOf<String, File>()

        fun register(dir: File?) {
            val resolved = dir?.takeIf { candidate -> candidate.path.isNotBlank() } ?: return
            directories.putIfAbsent(normalizeAbsolutePath(resolved.absolutePath), resolved)
        }

        anchorPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> register(File(path).parentFile) }
        register(managedModelDirectory())
        context.getExternalFilesDir(null)?.let { root ->
            register(File(root, PROVISIONING_MANAGED_MODELS_DIR_NAME))
        }
        appOwnedArtifactDirectories().forEach(::register)
        return directories.values.toList()
    }

    internal fun appOwnedArtifactDirectories(): List<File> {
        val packageName = context.packageName
        return listOf(
            File("/storage/emulated/0/Android/media/$packageName/models"),
            File("/storage/emulated/0/Download/$packageName/models"),
            File("/sdcard/Android/media/$packageName/models"),
            File("/sdcard/Download/$packageName/models"),
        )
    }

    // This is the single transactional metadata writer; named arguments keep its persistence contract auditable.
    @Suppress("LongParameterList")
    private fun upsertInstalledVersion(
        spec: ModelSpec,
        version: String,
        absolutePath: String,
        sha: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        runtimeCompatibility: String,
        fileSizeBytes: Long,
        makeActive: Boolean,
        sourceKind: ModelSourceKind? = null,
        sourceRef: ModelSourceRef? = null,
        displayName: String? = null,
        promptProfileId: String? = null,
        installedArtifacts: List<InstalledArtifactDescriptor>? = null,
        durableCommit: Boolean = false,
    ): RuntimeModelImportResult {
        val entries = readStoredVersionEntries(spec).toMutableList()
        val previousVersionsRaw = prefs.getString(versionsKey(spec), null)
        val previousActiveVersion = prefs.readOptional(activeVersionKey(spec))
        val now = System.currentTimeMillis()
        val sanitizedVersion = sanitizeVersion(version)
        val normalizedPath = normalizeAbsolutePath(absolutePath)
        val entry = StoredVersionEntry(
            version = sanitizedVersion,
            absolutePath = normalizedPath,
            sha256 = sha,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
            importedAtEpochMs = now,
        )
        // Keep one canonical entry per source path so repeated stage seeding does not create unbounded duplicates.
        entries.removeAll { it.version == sanitizedVersion || it.absolutePath == normalizedPath }
        entries.add(entry)
        val shouldBecomeActive = makeActive || previousActiveVersion.isNullOrBlank()
        val editor = prefs.edit().putString(versionsKey(spec), encodeStoredVersions(entries).toString())
        if (shouldBecomeActive) {
            editor.putString(activeVersionKey(spec), sanitizedVersion)
        }
        if (durableCommit) {
            val commitResult = commitOrRollback(
                commit = editor::commit,
                rollback = {
                    val rollbackEditor = prefs.edit()
                    if (previousVersionsRaw == null) {
                        rollbackEditor.remove(versionsKey(spec))
                    } else {
                        rollbackEditor.putString(versionsKey(spec), previousVersionsRaw)
                    }
                    if (previousActiveVersion == null) {
                        rollbackEditor.remove(activeVersionKey(spec))
                    } else {
                        rollbackEditor.putString(activeVersionKey(spec), previousActiveVersion)
                    }
                    rollbackEditor.commit()
                },
            )
            if (!commitResult.committed) {
                val failure = importPersistFailure(
                    modelId = spec.modelId,
                    destinationFile = File(normalizedPath),
                    detail = "metadata_commit_failed",
                )
                if (!commitResult.rollbackSucceeded) {
                    throw AmbiguousImportMetadataException(failure = failure)
                }
                throw failure
            }
        } else {
            editor.apply()
        }
        writeManagedMetadataIfApplicable(
            modelId = spec.modelId,
            entry = entry,
            displayName = displayName,
            sourceKind = sourceKind,
            sourceRef = sourceRef,
            promptProfileId = promptProfileId,
            installedArtifacts = installedArtifacts,
        )
        invalidateStorageSummaryCache()
        val isActive = prefs.readOptional(activeVersionKey(spec)) == sanitizedVersion
        return RuntimeModelImportResult(
            modelId = spec.modelId,
            version = sanitizedVersion,
            absolutePath = normalizedPath,
            sha256 = sha,
            copiedBytes = fileSizeBytes.coerceAtLeast(0L),
            isActive = isActive,
        )
    }

    private fun invalidateStorageSummaryCache() {
        storageSummaryCache = null
        storageSummaryCacheTimeMs = 0L
    }

    private fun readInstalledVersions(spec: ModelSpec): List<ModelVersionDescriptor> {
        return readInstalledVersionsWithDiagnostics(spec).versions
    }

    private fun resolveBundledArtifactPath(fileName: String): String? {
        if (fileName.isBlank()) {
            return null
        }
        return artifactSearchDirectories()
            .asSequence()
            .map { directory -> File(directory, fileName) }
            .firstOrNull { candidate -> candidate.exists() && candidate.isFile }
            ?.absolutePath
    }

    private fun readInstalledVersionsWithDiagnostics(spec: ModelSpec): VersionReadResult {
        val reconciled = reconcileStoredVersionEntries(spec)
        return VersionReadResult(
            versions = reconciled.entries
                .sortedByDescending { it.importedAtEpochMs }
                .map { entry ->
                    val sidecar = readSidecarMetadata(entry.absolutePath)
                    val sourceKind = sidecar?.sourceKind
                        ?: if (isManagedModelFile(File(entry.absolutePath))) {
                            ModelCatalog.normalizedSpecFor(spec.modelId)?.source?.kind ?: ModelSourceKind.BUILT_IN
                        } else {
                            ModelSourceKind.LOCAL_IMPORT
                        }
                    val installedArtifacts = sidecar?.artifacts?.takeIf { artifacts -> artifacts.isNotEmpty() }
                        ?: listOf(
                            InstalledArtifactDescriptor(
                                artifactId = "${spec.modelId}::${entry.version}::primary",
                                role = ModelArtifactRole.PRIMARY_GGUF,
                                fileName = entry.absolutePath.substringAfterLast('/').ifBlank {
                                    "${spec.modelId}-${entry.version}.gguf"
                                },
                                absolutePath = entry.absolutePath,
                                expectedSha256 = entry.sha256,
                                runtimeCompatibility = entry.runtimeCompatibility,
                                fileSizeBytes = entry.fileSizeBytes,
                            ),
                        )
                    ModelVersionDescriptor(
                        modelId = spec.modelId,
                        version = entry.version,
                        displayName = sidecar?.displayName ?: spec.displayName,
                        absolutePath = entry.absolutePath,
                        sha256 = entry.sha256,
                        provenanceIssuer = entry.provenanceIssuer,
                        provenanceSignature = entry.provenanceSignature,
                        runtimeCompatibility = entry.runtimeCompatibility,
                        fileSizeBytes = entry.fileSizeBytes,
                        importedAtEpochMs = entry.importedAtEpochMs,
                        isActive = entry.version == prefs.readOptional(activeVersionKey(spec)),
                        sourceKind = sourceKind,
                        sourceRef = sidecar?.sourceRef,
                        artifacts = installedArtifacts,
                        promptProfileId = sidecar?.promptProfileId,
                        parameters = sidecar?.parameters?.toModelParameterProfile()
                            ?: com.pocketagent.core.model.ModelParameterProfile(),
                    )
                },
            signal = reconciled.signal,
        )
    }

    private fun reconcileStoredVersionEntries(spec: ModelSpec): StoredEntriesReadResult {
        val readResult = readStoredVersionEntriesWithDiagnostics(spec)
        val original = readResult.entries
        if (original.isEmpty()) {
            return readResult
        }

        var changed = false
        val deduped = mutableListOf<StoredVersionEntry>()
        val seenVersions = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()

        original.forEach { entry ->
            if (!seenVersions.add(entry.version) || !seenPaths.add(entry.absolutePath)) {
                changed = true
                return@forEach
            }
            deduped += entry
        }

        val activeVersionKey = activeVersionKey(spec)
        val activeVersion = prefs.readOptional(activeVersionKey)
        if (activeVersion != null && deduped.none { it.version == activeVersion }) {
            changed = true
            if (deduped.isEmpty()) {
                prefs.edit().remove(activeVersionKey).apply()
            } else {
                val replacement = deduped.maxByOrNull { it.importedAtEpochMs }?.version
                prefs.edit().putString(activeVersionKey, replacement).apply()
            }
        }
        val repairedActive = selectRuntimeConfigEntry(
            activeVersion = prefs.readOptional(activeVersionKey),
            entries = deduped,
        )
        val currentActive = prefs.readOptional(activeVersionKey)
        if (repairedActive != null && repairedActive.version != currentActive) {
            changed = true
            prefs.edit().putString(activeVersionKey, repairedActive.version).apply()
        }

        if (changed) {
            writeStoredVersionEntries(spec, deduped)
        }
        return readResult.copy(entries = deduped)
    }

    private fun resolveFallbackModelPath(spec: ModelSpec, version: String?, missingPath: String): String? {
        val packageName = context.packageName
        val candidateFileNames = buildList {
            File(missingPath).name.takeIf { it.isNotBlank() }?.let { add(it) }
            version?.trim()?.takeIf { it.isNotBlank() }?.let { add(fileNameForVersion(spec, it)) }
            add(spec.fileName)
        }.distinct()
        // Generation 1: internal filesDir (oldest path scheme)
        val gen1Candidates = candidateFileNames.flatMap { fileName ->
            listOf(
                "${context.filesDir.absolutePath}/${PROVISIONING_LEGACY_MODEL_DIR_NAME}/$fileName",
                "${context.filesDir.absolutePath}/models/$fileName",
                "${context.cacheDir.absolutePath}/models/$fileName",
            )
        }
        // Generation 2: external media and downloads
        val gen2Candidates = candidateFileNames.flatMap { fileName ->
            listOf(
                "/storage/emulated/0/Android/media/$packageName/models/$fileName",
                "/storage/emulated/0/Download/$packageName/models/$fileName",
                "/sdcard/Android/media/$packageName/models/$fileName",
                "/sdcard/Download/$packageName/models/$fileName",
            )
        }
        // Generation 3: current managed storage root
        val gen3Candidates = candidateFileNames.map { fileName ->
            "${managedModelDirectory().absolutePath}/$fileName"
        }
        // Alias-based candidates (swap between media and download directories)
        val aliasCandidates = listOf(
            missingPath.replace("/Android/media/", "/Download/"),
            missingPath.replace("/storage/emulated/0/Android/media/", "/storage/emulated/0/Download/"),
            missingPath.replace("/sdcard/Android/media/", "/sdcard/Download/"),
            missingPath.replace("/Download/", "/Android/media/"),
        )

        return (gen3Candidates + gen2Candidates + gen1Candidates + aliasCandidates)
            .asSequence()
            .map { candidate -> candidate.trim() }
            .filter { candidate -> candidate.isNotEmpty() }
            .map { candidate -> normalizeAbsolutePath(candidate) }
            .filter { candidate -> candidate != normalizeAbsolutePath(missingPath) }
            .firstOrNull { candidate ->
                val file = File(candidate)
                file.exists() && file.isFile
            }
    }

    internal fun runPathAliasMigrationLocked() {
        var updatedAny = false
        allModelSpecs().forEach { spec ->
            updatedAny = migratePathAliasesForSpec(spec) || updatedAny
        }
        if (updatedAny || !prefs.getBoolean(PROVISIONING_PATH_ALIAS_MIGRATION_DONE_KEY, false)) {
            prefs.edit().putBoolean(PROVISIONING_PATH_ALIAS_MIGRATION_DONE_KEY, true).apply()
        }
    }

    internal fun migratePathAliasesForSpec(spec: ModelSpec): Boolean {
        val entries = readStoredVersionEntries(spec)
        if (entries.isEmpty()) {
            return false
        }
        val managedDir = managedModelDirectory()
        var changed = false
        val migrated = entries.map { entry ->
            val currentFile = File(entry.absolutePath)
            if (currentFile.exists() && currentFile.isFile) {
                // Existing app-owned external/media or imported files remain valid in place.
                // Only relocate older app-private legacy cache/files paths.
                if (!isManagedModelFile(currentFile) && shouldRelocateExistingModelFile(currentFile)) {
                    val targetFile = File(managedDir, currentFile.name)
                    if (!targetFile.exists()) {
                        val relocated = runCatching {
                            currentFile.copyTo(targetFile, overwrite = false)
                            true
                        }.getOrElse { false }
                        if (relocated && targetFile.exists() && targetFile.length() == currentFile.length()) {
                            changed = true
                            Log.i(
                                LOG_TAG,
                                "Relocated ${spec.modelId} from legacy path ${entry.absolutePath} " +
                                    "to ${targetFile.absolutePath}",
                            )
                            return@map entry.copy(
                                absolutePath = normalizeAbsolutePath(targetFile.absolutePath),
                                fileSizeBytes = targetFile.length().coerceAtLeast(0L),
                            )
                        }
                    }
                }
                entry
            } else {
                val fallbackPath = resolveFallbackModelPath(
                    spec = spec,
                    version = entry.version,
                    missingPath = entry.absolutePath,
                ) ?: return@map entry
                changed = true
                val fallbackFile = File(fallbackPath)
                entry.copy(
                    absolutePath = normalizeAbsolutePath(fallbackFile.absolutePath),
                    fileSizeBytes = fallbackFile.length().coerceAtLeast(0L),
                )
            }
        }
        if (changed) {
            writeStoredVersionEntries(spec, migrated)
        }
        return changed
    }

    internal fun shouldRelocateExistingModelFile(file: File): Boolean {
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val legacyCandidates = listOf(
            File(context.filesDir, PROVISIONING_LEGACY_MODEL_DIR_NAME),
            File(context.filesDir, "models"),
            File(context.cacheDir, "models"),
        )
        return legacyCandidates.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
        }
    }

    internal fun readStoredVersionEntries(spec: ModelSpec): List<StoredVersionEntry> {
        return readStoredVersionEntriesWithDiagnostics(spec).entries
    }

    internal fun readStoredVersionEntriesWithDiagnostics(spec: ModelSpec): StoredEntriesReadResult {
        val raw = prefs.getString(versionsKey(spec), null).orEmpty().trim()
        if (raw.isEmpty()) {
            return StoredEntriesReadResult(entries = emptyList())
        }
        return runCatching {
            val array = JSONArray(raw)
            val decoded = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    decodeStoredVersion(item)?.let { add(it) }
                }
            }
            val dropped = array.length() - decoded.size
            if (dropped > 0) {
                val corruptionDetail = "model=${spec.modelId};dropped_rows=$dropped"
                backupCorruptPayload(
                    key = versionsKey(spec),
                    raw = raw,
                    code = "PROVISIONING_VERSIONS_ROW_CORRUPT",
                    detail = corruptionDetail,
                )
                if (decoded.isEmpty()) {
                    recoverStoredEntriesFromDiscovery(
                        spec = spec,
                        corruptionCode = "PROVISIONING_VERSIONS_ROW_CORRUPT",
                        corruptionDetail = corruptionDetail,
                    )?.let { recovered ->
                        return recovered
                    }
                }
                writeStoredVersionEntries(spec, decoded)
                StoredEntriesReadResult(
                    entries = decoded,
                    signal = ProvisioningRecoverySignal(
                        code = "PROVISIONING_VERSIONS_ROW_CORRUPT",
                        message = "Some stored model versions were invalid and were removed for ${spec.modelId}.",
                        technicalDetail = corruptionDetail,
                    ),
                )
            } else {
                if (decoded.isEmpty()) {
                    val emptyDetail = "model=${spec.modelId};source=empty_versions_array"
                    recoverStoredEntriesFromDiscovery(
                        spec = spec,
                        corruptionCode = "PROVISIONING_VERSIONS_EMPTY",
                        corruptionDetail = emptyDetail,
                    )?.let { recovered ->
                        return recovered
                    }
                    val activeKey = activeVersionKey(spec)
                    if (!prefs.readOptional(activeKey).isNullOrBlank()) {
                        prefs.edit().remove(activeKey).apply()
                        return StoredEntriesReadResult(
                            entries = emptyList(),
                            signal = ProvisioningRecoverySignal(
                                code = "PROVISIONING_ACTIVE_VERSION_ORPHANED",
                                message = "Active model version metadata was orphaned for ${spec.modelId} " +
                                    "and has been reset.",
                                technicalDetail = emptyDetail,
                            ),
                        )
                    }
                }
                StoredEntriesReadResult(entries = decoded)
            }
        }.getOrElse { error ->
            val corruptionDetail = "model=${spec.modelId};error=${error.message ?: "json_parse_failed"}"
            backupCorruptPayload(
                key = versionsKey(spec),
                raw = raw,
                code = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                detail = corruptionDetail,
            )
            recoverStoredEntriesFromDiscovery(
                spec = spec,
                corruptionCode = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                corruptionDetail = corruptionDetail,
            )?.let { recovered ->
                return recovered
            }
            prefs.edit()
                .putString(versionsKey(spec), "[]")
                .remove(activeVersionKey(spec))
                .apply()
            val signal = ProvisioningRecoverySignal(
                code = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                message = "Stored model metadata was corrupted for ${spec.modelId} and has been reset.",
                technicalDetail = corruptionDetail,
            )
            StoredEntriesReadResult(entries = emptyList(), signal = signal)
        }
    }

    internal fun recoverStoredEntriesFromDiscovery(
        spec: ModelSpec,
        corruptionCode: String,
        corruptionDetail: String,
    ): StoredEntriesReadResult? {
        val recoveredEntries = discoverPersistedEntries(spec)
        if (recoveredEntries.isEmpty()) {
            return null
        }
        writeStoredVersionEntries(spec, recoveredEntries)
        val latestVersion = recoveredEntries.maxByOrNull { it.importedAtEpochMs }?.version
        prefs.edit().putString(activeVersionKey(spec), latestVersion).apply()
        val signal = ProvisioningRecoverySignal(
            code = "PROVISIONING_VERSIONS_RECOVERED_FROM_DISCOVERY",
            message = "Stored model metadata was corrupted for ${spec.modelId} and rebuilt from local model files.",
            technicalDetail = "model=${spec.modelId};source=$corruptionCode;" +
                "recovered=${recoveredEntries.size};$corruptionDetail",
        )
        recordMigrationCorruption(signal)
        return StoredEntriesReadResult(
            entries = recoveredEntries,
            signal = signal,
        )
    }

    internal fun writeStoredVersionEntries(spec: ModelSpec, entries: List<StoredVersionEntry>) {
        prefs.edit().putString(versionsKey(spec), encodeStoredVersions(entries).toString()).apply()
    }

    internal fun normalizeAbsolutePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        return runCatching { File(trimmed).canonicalPath }
            .getOrElse { File(trimmed).absolutePath }
    }

    private fun deleteManagedFileIfOrphaned(
        absolutePath: String,
        remainingEntries: List<StoredVersionEntry>,
    ) {
        if (absolutePath.isBlank()) {
            return
        }
        if (remainingEntries.any { it.absolutePath == absolutePath }) {
            return
        }
        val target = File(absolutePath)
        if (!isManagedModelFile(target)) {
            return
        }
        target.takeIf { it.exists() }?.delete()
        metadataFileFor(target.absolutePath).takeIf { it.exists() }?.delete()
    }

    internal fun isManagedModelFile(file: File): Boolean {
        val managedRuntimeDir = managedModelDirectory()
        val managedDownloadDir = managedDownloadWorkspaceDirectory()
        val legacyRuntimeDir = File(context.filesDir, PROVISIONING_LEGACY_MODEL_DIR_NAME)
        val legacyDownloadDir = File(context.filesDir, ModelDownloadWorker.DOWNLOAD_DIR)
        return isPathWithin(file, managedRuntimeDir) ||
            isPathWithin(file, managedDownloadDir) ||
            isPathWithin(file, legacyRuntimeDir) ||
            isPathWithin(file, legacyDownloadDir)
    }

    private fun isPathWithin(candidate: File, root: File): Boolean {
        val candidatePath = runCatching { candidate.canonicalPath }.getOrNull() ?: return false
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    }

    private fun resolvePathOriginForVersion(
        version: String?,
        absolutePath: String,
    ): String {
        if (version == PROVISIONING_DISCOVERED_VERSION_FALLBACK) {
            return ModelPathOrigin.DISCOVERED_RECOVERED
        }
        if (absolutePath.isBlank()) {
            return ModelPathOrigin.MANAGED
        }
        return if (isManagedModelFile(File(absolutePath))) {
            ModelPathOrigin.MANAGED
        } else {
            ModelPathOrigin.IMPORTED_EXTERNAL
        }
    }

    private fun activeMetadataSignals(
        spec: ModelSpec,
        activeVersion: ModelVersionDescriptor,
        activeFile: File,
    ): List<ProvisioningRecoverySignal> {
        val signals = mutableListOf<ProvisioningRecoverySignal>()
        val sha = activeVersion.sha256.trim()
        if (!SHA256_HEX_REGEX.matches(sha)) {
            signals += ProvisioningRecoverySignal(
                code = "MODEL_METADATA_SHA_INVALID",
                message = "Model metadata is inconsistent for ${spec.modelId}. " +
                    "Re-import or re-download to refresh integrity metadata.",
                technicalDetail = "model=${spec.modelId};version=${activeVersion.version};sha=$sha",
            )
        }
        val actualSize = activeFile.length().coerceAtLeast(0L)
        val declaredSize = activeVersion.fileSizeBytes.coerceAtLeast(0L)
        if (declaredSize > 0L && actualSize > 0L && declaredSize != actualSize) {
            signals += ProvisioningRecoverySignal(
                code = "MODEL_METADATA_SIZE_MISMATCH",
                message = "Model file size does not match stored metadata for ${spec.modelId}. " +
                    "Re-import or re-download to repair.",
                technicalDetail = "model=${spec.modelId};version=${activeVersion.version};" +
                    "declared=$declaredSize;actual=$actualSize",
            )
        }
        if (activeVersion.runtimeCompatibility != PROVISIONING_RUNTIME_COMPATIBILITY_TAG) {
            signals += ProvisioningRecoverySignal(
                code = "MODEL_METADATA_RUNTIME_MISMATCH",
                message = "Model runtime compatibility metadata is outdated for ${spec.modelId}. " +
                    "Use a compatible download.",
                technicalDetail = "model=${spec.modelId};version=${activeVersion.version};" +
                    "stored_runtime=${activeVersion.runtimeCompatibility};" +
                    "expected=$PROVISIONING_RUNTIME_COMPATIBILITY_TAG",
            )
        }
        if (activeVersion.provenanceIssuer.isBlank() || activeVersion.provenanceSignature.isBlank()) {
            signals += ProvisioningRecoverySignal(
                code = "MODEL_METADATA_PROVENANCE_MISSING",
                message = "Model provenance metadata is incomplete for ${spec.modelId}. " +
                    "Re-download verified artifacts.",
                technicalDetail = "model=${spec.modelId};version=${activeVersion.version};" +
                    "issuer_blank=${activeVersion.provenanceIssuer.isBlank()};" +
                    "signature_blank=${activeVersion.provenanceSignature.isBlank()}",
            )
        }
        return signals
    }

    private fun describeStorageRoot(root: File): String {
        val isExternalMedia = externalStorageRoots()
            .asSequence()
            .filterNotNull()
            .any { candidate -> isPathWithin(root, candidate) || isPathWithin(candidate, root) }
        val bucket = if (isExternalMedia) {
            "External app media"
        } else {
            "App internal storage"
        }
        return "$bucket (${root.absolutePath})"
    }

    internal fun sanitizeVersion(raw: String): String {
        return raw
            .trim()
            .ifEmpty { generatedVersion(prefix = "v") }
            .replace(PROVISIONING_MODEL_TOKEN_SANITIZE_REGEX, "-")
            .lowercase(Locale.US)
    }

    internal fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(PROVISIONING_COPY_BUFFER_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    internal fun backupCorruptPayload(
        key: String,
        raw: String,
        code: String,
        detail: String,
    ) {
        prefs.edit()
            .putString("$PROVISIONING_CORRUPT_BACKUP_PREFIX.$key.payload", raw)
            .putString("$PROVISIONING_CORRUPT_BACKUP_PREFIX.$key.code", code)
            .putString("$PROVISIONING_CORRUPT_BACKUP_PREFIX.$key.detail", detail)
            .putLong("$PROVISIONING_CORRUPT_BACKUP_PREFIX.$key.saved_at", System.currentTimeMillis())
            .apply()
    }

    internal fun recordMigrationCorruption(signal: ProvisioningRecoverySignal) {
        synchronized(PROVISIONING_MIGRATION_SIGNAL_LOCK) {
            migrationCorruptionSignals += signal
        }
    }

    private fun drainMigrationCorruptionSignals(): List<ProvisioningRecoverySignal> {
        synchronized(PROVISIONING_MIGRATION_SIGNAL_LOCK) {
            if (migrationCorruptionSignals.isEmpty()) {
                return emptyList()
            }
            val drained = migrationCorruptionSignals.toList()
            migrationCorruptionSignals.clear()
            return drained
        }
    }

    private fun managedStorageRoot(): File {
        managedStorageRootCache.get()?.let { cachedRoot ->
            return cachedRoot
        }
        val externalRoot = externalStorageRoots()
            .asSequence()
            .filterNotNull()
            .firstOrNull { candidate ->
                runCatching {
                    if (!candidate.exists()) {
                        candidate.mkdirs()
                    }
                    candidate.exists() && candidate.isDirectory && candidate.canWrite()
                }.getOrDefault(false)
            }
            ?: error(
                "External model storage is unavailable; " +
                    "app-private storage is not a supported canonical cache path.",
            )
        managedStorageRootCache.compareAndSet(null, externalRoot)
        return managedStorageRootCache.get() ?: externalRoot
    }

    private fun externalStorageRoots(): Array<File?> {
        externalStorageRootsCache.get()?.let { roots ->
            return roots
        }
        val computed = arrayOf(context.getExternalFilesDir(null))
        externalStorageRootsCache.compareAndSet(null, computed)
        return externalStorageRootsCache.get() ?: computed
    }

    private fun mirrorInstalledModelToDownloads(
        spec: ModelSpec,
        version: String,
        sourceFile: File,
    ): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return false
        }
        val fileName = fileNameForVersion(spec = spec, version = version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mirrorToPublicDownloadsViaMediaStore(fileName = fileName, sourceFile = sourceFile)) {
                return true
            }
        }
        return mirrorToLegacyDownloadsPath(fileName = fileName, sourceFile = sourceFile)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun mirrorToPublicDownloadsViaMediaStore(fileName: String, sourceFile: File): Boolean {
        val resolver = context.contentResolver
        val relativePath = "$DOWNLOADS_ROOT_DIR/${context.packageName}/models/"
        val existingUri = findDownloadsMediaStoreUri(fileName = fileName, relativePath = relativePath)
        var insertedUri: Uri? = null
        val targetUri = existingUri ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.SIZE, sourceFile.length().coerceAtLeast(0L))
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        )?.also { uri -> insertedUri = uri } ?: return false

        return runCatching {
            val outputStream = resolver.openOutputStream(targetUri, "w")
            if (outputStream == null) {
                error("Unable to open destination stream for Downloads mirror.")
            }
            outputStream.use { output ->
                sourceFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(PROVISIONING_COPY_BUFFER_SIZE_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            resolver.update(
                targetUri,
                ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    put(MediaStore.Downloads.SIZE, sourceFile.length().coerceAtLeast(0L))
                },
                null,
                null,
            )
            true
        }.getOrElse {
            insertedUri?.let { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
            false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun findDownloadsMediaStoreUri(fileName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relativePath)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val id = cursor.getLong(idIndex)
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun mirrorToLegacyDownloadsPath(fileName: String, sourceFile: File): Boolean {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(DOWNLOADS_ROOT_DIR) ?: return false
        val modelDir = File(downloadsRoot, "${context.packageName}/models")
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            return false
        }
        val destinationFile = File(modelDir, fileName)
        val tempFile = File(modelDir, ".${fileName}.tmp")
        return runCatching {
            sourceFile.inputStream().buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(PROVISIONING_COPY_BUFFER_SIZE_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            tempFile.renameTo(destinationFile)
        }.getOrElse {
            tempFile.takeIf { it.exists() }?.delete()
            false
        }
    }

    private fun lockForModel(modelId: String): Any {
        return synchronized(modelLocks) {
            modelLocks.getOrPut(modelId) { Any() }
        }
    }

    private fun publishImportedModel(tempFile: File, destinationFile: File): Boolean {
        return try {
            Files.move(
                tempFile.toPath(),
                destinationFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(
                    tempFile.toPath(),
                    destinationFile.toPath(),
                )
            }.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    // Content providers may throw platform-specific errors; propagate any throwable through this cancellable bridge.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun openModelAssetDescriptor(modelId: String, sourceUri: Uri): AssetFileDescriptor {
        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                val descriptor = context.contentResolver.openAssetFileDescriptor(
                    sourceUri,
                    "r",
                    cancellationSignal,
                )
                if (descriptor == null) {
                    continuation.resumeWithException(
                        unreadableImportSource(modelId = modelId, sourceUri = sourceUri),
                    )
                } else if (continuation.isActive) {
                    continuation.resume(descriptor) { descriptor.close() }
                } else {
                    descriptor.close()
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    private fun importedModelDestinationFile(
        destinationDir: File,
        spec: ModelSpec,
        sha: String,
    ): File {
        val modelToken = sha256Hex(spec.modelId.encodeToByteArray()).take(12)
        return File(destinationDir, "pocketgpt-import-$modelToken-$sha.gguf")
    }

    private fun importPersistFailure(
        modelId: String,
        destinationFile: File,
        detail: String = "publish_failed",
    ): RuntimeDomainException {
        return RuntimeDomainException(
            domainError = RuntimeDomainError(
                code = RuntimeErrorCodes.PROVISIONING_IMPORT_PERSIST_FAILED,
                userMessage = "Unable to save the imported model file.",
                technicalDetail = "model=$modelId;destination=${destinationFile.absolutePath};detail=$detail",
            ),
        )
    }

    private class AmbiguousImportMetadataException(
        val failure: RuntimeDomainException,
    ) : RuntimeException(failure)

    private fun unreadableImportSource(
        modelId: String,
        sourceUri: Uri,
        cause: Throwable? = null,
    ): RuntimeDomainException {
        return RuntimeDomainException(
            domainError = RuntimeDomainError(
                code = RuntimeErrorCodes.PROVISIONING_IMPORT_SOURCE_UNREADABLE,
                userMessage = "Unable to read the selected model file.",
                technicalDetail = "model=$modelId;source_uri=$sourceUri",
            ),
            cause = cause,
        )
    }

    private fun <T> withModelLock(modelId: String, block: () -> T): T {
        val lock = lockForModel(modelId)
        return synchronized(lock) {
            block()
        }
    }

    companion object {
        private const val LOG_TAG = "RuntimeProvisioningStore"
        private val DOWNLOADS_ROOT_DIR = Environment.DIRECTORY_DOWNLOADS
        private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")

        internal fun baselineModelIdsForTesting(): Set<String> {
            return provisioningBaselineModelIdsForTesting()
        }

        internal fun legacyPathKeyForTesting(modelId: String): String? {
            return provisioningLegacyPathKeyForTesting(modelId)
        }
    }
}

internal fun staleActiveVersionSignal(
    modelId: String,
    activeVersion: String?,
    installedVersions: List<ModelVersionDescriptor>,
): ProvisioningRecoverySignal? {
    val normalizedActiveVersion = activeVersion?.trim().orEmpty()
    if (normalizedActiveVersion.isEmpty()) {
        return null
    }
    if (installedVersions.any { descriptor -> descriptor.version == normalizedActiveVersion }) {
        return null
    }
    return ProvisioningRecoverySignal(
        code = "MODEL_ACTIVE_VERSION_STALE",
        message = "Active model version pointer is stale for $modelId. Re-activate an installed version.",
        technicalDetail = "model=$modelId;active_version=$normalizedActiveVersion;installed_versions=" +
            installedVersions.joinToString(separator = ",") { version -> version.version },
    )
}

internal fun selectRuntimeConfigDescriptor(
    activeVersion: String?,
    versions: List<ModelVersionDescriptor>,
): ModelVersionDescriptor? {
    val normalizedActiveVersion = activeVersion?.trim().orEmpty()
    return versions.firstOrNull { descriptor ->
        descriptor.version == normalizedActiveVersion && descriptor.isRuntimeConfigEligible()
    } ?: versions
        .filter { descriptor -> descriptor.isRuntimeConfigEligible() }
        .maxByOrNull { descriptor -> descriptor.importedAtEpochMs }
}

internal fun selectRuntimeConfigEntry(
    activeVersion: String?,
    entries: List<StoredVersionEntry>,
): StoredVersionEntry? {
    val normalizedActiveVersion = activeVersion?.trim().orEmpty()
    return entries.firstOrNull { entry ->
        entry.version == normalizedActiveVersion && entry.isRuntimeConfigEligible()
    } ?: entries
        .filter { entry -> entry.isRuntimeConfigEligible() }
        .maxByOrNull { entry -> entry.importedAtEpochMs }
}

private fun ModelVersionDescriptor.isRuntimeConfigEligible(): Boolean {
    val file = File(absolutePath.trim())
    return file.exists() &&
        file.isFile &&
        sha256.isNotBlank() &&
        provenanceIssuer.isNotBlank() &&
        provenanceSignature.isNotBlank() &&
        runtimeCompatibility == PROVISIONING_RUNTIME_COMPATIBILITY_TAG
}

private fun StoredVersionEntry.isRuntimeConfigEligible(): Boolean {
    val file = File(absolutePath.trim())
    return file.exists() &&
        file.isFile &&
        sha256.isNotBlank() &&
        provenanceIssuer.isNotBlank() &&
        provenanceSignature.isNotBlank() &&
        runtimeCompatibility == PROVISIONING_RUNTIME_COMPATIBILITY_TAG
}
