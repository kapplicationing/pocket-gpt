package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.core.model.ModelArtifactRole
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

internal enum class DownloadExecutionOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal enum class DownloadStopDisposition {
    MARK_CANCELLED,
    RESCHEDULE,
}

internal interface DownloadExecutionHost {
    suspend fun updateNotification(taskId: String, modelId: String, percent: Int)

    fun isStopped(): Boolean

    fun stopDisposition(): DownloadStopDisposition
}

internal data class DownloadResult(
    val downloadedBytes: Long,
    val computedTotalBytes: Long,
    val sha256Hex: String?,
    val resumeEtag: String?,
    val resumeLastModified: String?,
)

internal data class TransferMetrics(
    val downloadSpeedBps: Long?,
    val etaSeconds: Long?,
    val lastProgressEpochMs: Long?,
)

internal data class ResumeTransferBaseline(
    val existingBytes: Long,
    val metricBytes: Long,
    val metricEpochMs: Long,
    val truncatePartialFile: Boolean,
)

internal data class VerifiedBundleArtifact(
    val state: DownloadArtifactTaskState,
    val stagedFile: File,
    val sha256: String,
)

internal class ModelDownloadExecutor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provisioningStore = AndroidRuntimeProvisioningStore(appContext)

    suspend fun execute(
        taskId: String,
        host: DownloadExecutionHost,
        network: Network? = null,
        retryAllowed: Boolean = false,
    ): DownloadExecutionOutcome = withContext(Dispatchers.IO) {
        val task = ModelDownloadTaskStateStore.get(appContext, taskId)
            ?: return@withContext DownloadExecutionOutcome.FAILURE
        var artifactStates = task.artifactStates
        if (task.status == DownloadTaskStatus.PAUSED) {
            return@withContext DownloadExecutionOutcome.SUCCESS
        }
        if (!hasNetworkConnection()) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                message = "No network connection available.",
                artifactStates = artifactStates,
                activeArtifactId = artifactStates.firstOrNull()?.artifactId,
            )
            return@withContext if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        }

        val workspaceDir = taskWorkspaceDirectory(taskId).apply { mkdirs() }

        try {
            host.updateNotification(taskId = taskId, modelId = task.modelId, percent = 0)
            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = DownloadTaskStatus.DOWNLOADING,
                progressBytes = artifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) },
                totalBytes = artifactStates.sumOf { artifact -> artifact.totalBytes.coerceAtLeast(0L) },
                processingStage = DownloadProcessingStage.DOWNLOADING,
                verificationPolicy = task.verificationPolicy,
                message = "Downloading",
                artifactStates = artifactStates,
                activeArtifactId = artifactStates.firstOrNull()?.artifactId,
                sourceKind = task.sourceKind,
            )
            val runtimeTag = provisioningStore.expectedRuntimeCompatibilityTag()
            val verifiedArtifacts = mutableListOf<VerifiedBundleArtifact>()
            artifactStates.forEachIndexed { index, artifact ->
                val partFile = artifactPartFile(workspaceDir, artifact)
                val stagedFile = artifactVerifiedFile(workspaceDir, artifact)
                val alreadyVerified = artifact.status == DownloadArtifactTaskStatus.VERIFIED &&
                    stagedFile.exists() &&
                    stagedFile.isFile
                if (alreadyVerified) {
                    verifiedArtifacts += VerifiedBundleArtifact(
                        state = artifact,
                        stagedFile = stagedFile,
                        sha256 = artifact.verifiedSha256 ?: sha256HexFromFile(stagedFile) { host.isStopped() },
                    )
                    return@forEachIndexed
                }

                artifactStates = artifactStates.updateArtifact(
                    artifactId = artifact.artifactId,
                    transform = { current ->
                        current.copy(
                            status = DownloadArtifactTaskStatus.DOWNLOADING,
                            failureReason = null,
                        )
                    },
                )
                updateState(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    status = DownloadTaskStatus.DOWNLOADING,
                    progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                    totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                    processingStage = DownloadProcessingStage.DOWNLOADING,
                    verificationPolicy = task.verificationPolicy,
                    message = "Downloading ${artifact.fileName} (${index + 1}/${artifactStates.size})",
                    artifactStates = artifactStates,
                    activeArtifactId = artifact.artifactId,
                    sourceKind = task.sourceKind,
                )
                val result: DownloadResult
                try {
                    result = downloadArtifactToPartial(
                        task = task,
                        artifact = artifactStates.first { current -> current.artifactId == artifact.artifactId },
                        partFile = partFile,
                        host = host,
                        network = network,
                        onProgress = { downloaded, expectedTotal, transferMetrics ->
                            artifactStates = artifactStates.updateArtifact(
                                artifactId = artifact.artifactId,
                                transform = { current ->
                                    current.copy(
                                        progressBytes = downloaded,
                                        totalBytes = expectedTotal.coerceAtLeast(downloaded),
                                        status = DownloadArtifactTaskStatus.DOWNLOADING,
                                    )
                                },
                            )
                            val percent = if (artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) } <= 0L) {
                                0
                            } else {
                                (
                                    artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) } * 100L /
                                        artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) }
                                    ).toInt()
                            }
                            host.updateNotification(taskId = taskId, modelId = task.modelId, percent = percent.coerceIn(0, 100))
                            updateState(
                                taskId = taskId,
                                modelId = task.modelId,
                                version = task.version,
                                status = DownloadTaskStatus.DOWNLOADING,
                                progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                                totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                                processingStage = DownloadProcessingStage.DOWNLOADING,
                                verificationPolicy = task.verificationPolicy,
                                message = "Downloading ${artifact.fileName}",
                                transferMetrics = transferMetrics,
                                artifactStates = artifactStates,
                                activeArtifactId = artifact.artifactId,
                                sourceKind = task.sourceKind,
                            )
                        },
                        onResumeMetadata = { resumeEtag, resumeLastModified ->
                            artifactStates = artifactStates.updateArtifact(
                                artifactId = artifact.artifactId,
                                transform = { current ->
                                    current.copy(
                                        resumeEtag = resumeEtag,
                                        resumeLastModified = resumeLastModified,
                                    )
                                },
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!artifact.required) {
                        partFile.delete()
                        artifactStates = artifactStates.markArtifactFailed(
                            artifactId = artifact.artifactId,
                            reason = when (e) {
                                is SocketTimeoutException -> DownloadFailureReason.TIMEOUT
                                is InsufficientStorageException -> DownloadFailureReason.INSUFFICIENT_STORAGE
                                else -> DownloadFailureReason.NETWORK_ERROR
                            },
                        )
                        Log.w(LOG_TAG, "Skipping optional artifact ${artifact.fileName}: ${e.message}")
                        return@forEachIndexed
                    }
                    throw e
                }
                val verificationTotalBytes = result.computedTotalBytes.takeIf { it > 0L } ?: result.downloadedBytes
                updateState(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    status = DownloadTaskStatus.VERIFYING,
                    progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                    totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                    processingStage = DownloadProcessingStage.VERIFYING,
                    verificationPolicy = task.verificationPolicy,
                    message = "Verifying ${artifact.fileName}",
                    clearTransferMetrics = true,
                    artifactStates = artifactStates,
                    activeArtifactId = artifact.artifactId,
                    sourceKind = task.sourceKind,
                )
                val sha = result.sha256Hex ?: sha256HexFromFile(partFile) { host.isStopped() }
                if (!sha.equals(artifact.expectedSha256, ignoreCase = true)) {
                    partFile.delete()
                    artifactStates = artifactStates.markArtifactFailed(
                        artifactId = artifact.artifactId,
                        reason = DownloadFailureReason.CHECKSUM_MISMATCH,
                    )
                    if (!artifact.required) {
                        Log.w(LOG_TAG, "Skipping optional artifact ${artifact.fileName}: checksum mismatch.")
                        return@forEachIndexed
                    }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.CHECKSUM_MISMATCH,
                        processingStage = DownloadProcessingStage.VERIFYING,
                        progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                        message = "Checksum verification failed for ${artifact.fileName}.",
                        artifactStates = artifactStates,
                        activeArtifactId = artifact.artifactId,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
                if (artifact.runtimeCompatibility.isNotBlank() && artifact.runtimeCompatibility != runtimeTag) {
                    partFile.delete()
                    artifactStates = artifactStates.markArtifactFailed(
                        artifactId = artifact.artifactId,
                        reason = DownloadFailureReason.RUNTIME_INCOMPATIBLE,
                    )
                    if (!artifact.required) {
                        Log.w(LOG_TAG, "Skipping optional artifact ${artifact.fileName}: runtime incompatible.")
                        return@forEachIndexed
                    }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.RUNTIME_INCOMPATIBLE,
                        processingStage = DownloadProcessingStage.VERIFYING,
                        progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                        message = "Runtime compatibility mismatch for ${artifact.fileName}.",
                        artifactStates = artifactStates,
                        activeArtifactId = artifact.artifactId,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
                if (artifact.verificationPolicy.enforcesProvenance && !verifyProvenanceSignature(
                        issuer = artifact.provenanceIssuer,
                        modelId = task.modelId,
                        sha = sha,
                        expectedSignature = artifact.provenanceSignature,
                    )
                ) {
                    partFile.delete()
                    artifactStates = artifactStates.markArtifactFailed(
                        artifactId = artifact.artifactId,
                        reason = DownloadFailureReason.PROVENANCE_MISMATCH,
                    )
                    if (!artifact.required) {
                        Log.w(LOG_TAG, "Skipping optional artifact ${artifact.fileName}: provenance mismatch.")
                        return@forEachIndexed
                    }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.PROVENANCE_MISMATCH,
                        processingStage = DownloadProcessingStage.VERIFYING,
                        progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                        message = "Provenance signature mismatch for ${artifact.fileName}.",
                        artifactStates = artifactStates,
                        activeArtifactId = artifact.artifactId,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
                if (!ModelInstallIo.replaceWithAtomicMove(source = partFile, destination = stagedFile)) {
                    artifactStates = artifactStates.markArtifactFailed(
                        artifactId = artifact.artifactId,
                        reason = DownloadFailureReason.UNKNOWN,
                    )
                    if (!artifact.required) {
                        Log.w(LOG_TAG, "Skipping optional artifact ${artifact.fileName}: staging failed.")
                        return@forEachIndexed
                    }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.UNKNOWN,
                        processingStage = DownloadProcessingStage.VERIFYING,
                        progressBytes = artifactStates.sumOf { current -> current.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { current -> current.totalBytes.coerceAtLeast(0L) },
                        message = "Failed to stage ${artifact.fileName}.",
                        artifactStates = artifactStates,
                        activeArtifactId = artifact.artifactId,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
                artifactStates = artifactStates.updateArtifact(
                    artifactId = artifact.artifactId,
                    transform = { current ->
                        current.copy(
                            progressBytes = verificationTotalBytes,
                            totalBytes = verificationTotalBytes,
                            resumeEtag = null,
                            resumeLastModified = null,
                            verifiedSha256 = sha,
                            stagedFileName = stagedFile.name,
                            status = DownloadArtifactTaskStatus.VERIFIED,
                            failureReason = null,
                        )
                    },
                )
                verifiedArtifacts += VerifiedBundleArtifact(
                    state = artifactStates.first { current -> current.artifactId == artifact.artifactId },
                    stagedFile = stagedFile,
                    sha256 = sha,
                )
            }

            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = artifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) },
                totalBytes = artifactStates.sumOf { artifact -> artifact.totalBytes.coerceAtLeast(0L) },
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = task.verificationPolicy,
                message = "Installing bundle",
                clearTransferMetrics = true,
                artifactStates = artifactStates,
                activeArtifactId = artifactStates.firstOrNull { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }?.artifactId
                    ?: artifactStates.firstOrNull()?.artifactId,
                sourceKind = task.sourceKind,
            )
            val installOrder = verifiedArtifacts.sortedBy { verified ->
                if (verified.state.role == ModelArtifactRole.PRIMARY_GGUF) 1 else 0
            }
            val installedArtifacts = mutableListOf<InstalledArtifactDescriptor>()
            val installedFiles = mutableListOf<File>()
            installOrder.forEach { verified ->
                val destinationFile = destinationFileForArtifact(
                    modelId = task.modelId,
                    version = task.version,
                    artifact = verified.state,
                )
                if (!ModelInstallIo.replaceWithAtomicMove(source = verified.stagedFile, destination = destinationFile)) {
                    installedFiles.forEach { file -> file.takeIf { it.exists() && it.isFile }?.delete() }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.UNKNOWN,
                        processingStage = DownloadProcessingStage.INSTALLING,
                        progressBytes = artifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { artifact -> artifact.totalBytes.coerceAtLeast(0L) },
                        message = "Failed to install ${verified.state.fileName}.",
                        artifactStates = artifactStates,
                        activeArtifactId = verified.state.artifactId,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
                installedFiles += destinationFile
                installedArtifacts += InstalledArtifactDescriptor(
                    artifactId = verified.state.artifactId,
                    role = verified.state.role,
                    fileName = destinationFile.name,
                    absolutePath = destinationFile.absolutePath,
                    expectedSha256 = verified.sha256,
                    runtimeCompatibility = verified.state.runtimeCompatibility,
                    fileSizeBytes = destinationFile.length().coerceAtLeast(0L),
                    required = verified.state.required,
                )
                artifactStates = artifactStates.updateArtifact(
                    artifactId = verified.state.artifactId,
                    transform = { current ->
                        current.copy(
                            progressBytes = current.totalBytes.coerceAtLeast(0L),
                            stagedFileName = null,
                            installedAbsolutePath = destinationFile.absolutePath,
                            status = DownloadArtifactTaskStatus.INSTALLED,
                        )
                    },
                )
            }
            val primaryArtifact = installedArtifacts.firstOrNull { artifact -> artifact.role == ModelArtifactRole.PRIMARY_GGUF }
                ?: run {
                    installedFiles.forEach { file -> file.takeIf { it.exists() && it.isFile }?.delete() }
                    fail(
                        taskId = taskId,
                        modelId = task.modelId,
                        version = task.version,
                        reason = DownloadFailureReason.UNKNOWN,
                        processingStage = DownloadProcessingStage.INSTALLING,
                        progressBytes = artifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) },
                        totalBytes = artifactStates.sumOf { artifact -> artifact.totalBytes.coerceAtLeast(0L) },
                        message = "Bundle install is missing the primary GGUF artifact.",
                        artifactStates = artifactStates,
                    )
                    return@withContext DownloadExecutionOutcome.FAILURE
                }
            val primaryVerified = verifiedArtifacts.first { verified -> verified.state.artifactId == primaryArtifact.artifactId }
            val installResult = runCatching {
                provisioningStore.installDownloadedModel(
                    modelId = task.modelId,
                    version = task.version,
                    absolutePath = primaryArtifact.absolutePath.orEmpty(),
                    sha256 = primaryVerified.sha256,
                    provenanceIssuer = primaryVerified.state.provenanceIssuer.ifBlank { "internal-release" },
                    provenanceSignature = primaryVerified.state.provenanceSignature.ifBlank {
                        sha256Hex("${primaryVerified.state.provenanceIssuer.ifBlank { "internal-release" }}|${task.modelId}|${primaryVerified.sha256}|v1".encodeToByteArray())
                    },
                    runtimeCompatibility = runtimeTag,
                    fileSizeBytes = primaryArtifact.fileSizeBytes ?: 0L,
                    makeActive = primaryVerified.state.verificationPolicy.enforcesProvenance,
                    sourceKind = task.sourceKind,
                    sourceRef = task.sourceRef,
                    displayName = task.displayName,
                    promptProfileId = task.promptProfileId,
                    installedArtifacts = installedArtifacts,
                )
            }.getOrElse { error ->
                installedFiles.forEach { file -> file.takeIf { it.exists() && it.isFile }?.delete() }
                throw error
            }

            cleanupWorkspace(workspaceDir)
            val installedSize = installedArtifacts.sumOf { artifact -> artifact.fileSizeBytes?.coerceAtLeast(0L) ?: 0L }
            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = if (installResult.isActive) {
                    DownloadTaskStatus.COMPLETED
                } else {
                    DownloadTaskStatus.INSTALLED_INACTIVE
                },
                progressBytes = installedSize,
                totalBytes = installedSize,
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = task.verificationPolicy,
                message = if (installResult.isActive) {
                    "Bundle downloaded, verified, and active."
                } else {
                    "Bundle downloaded and verified. Activation pending."
                },
                clearTransferMetrics = true,
                artifactStates = artifactStates,
                activeArtifactId = null,
                sourceKind = task.sourceKind,
            )
            DownloadExecutionOutcome.SUCCESS
        } catch (timeout: SocketTimeoutException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.TIMEOUT,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = "Download timed out.",
                artifactStates = artifactStates,
                activeArtifactId = currentActiveArtifactId(artifactStates),
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        } catch (storageError: InsufficientStorageException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.INSUFFICIENT_STORAGE,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = storageError.message ?: "Not enough storage to continue the download.",
                artifactStates = artifactStates,
                activeArtifactId = currentActiveArtifactId(artifactStates),
            )
            DownloadExecutionOutcome.FAILURE
        } catch (cancellation: CancellationException) {
            if (host.isStopped()) {
                val current = ModelDownloadTaskStateStore.get(appContext, taskId)
                if (
                    current?.status == DownloadTaskStatus.PAUSED ||
                    current?.status == DownloadTaskStatus.CANCELLED
                ) {
                    return@withContext DownloadExecutionOutcome.SUCCESS
                }
                return@withContext when (host.stopDisposition()) {
                    DownloadStopDisposition.MARK_CANCELLED -> {
                        fail(
                            taskId = taskId,
                            modelId = task.modelId,
                            version = task.version,
                            reason = DownloadFailureReason.CANCELLED,
                            processingStage = current?.processingStage ?: DownloadProcessingStage.DOWNLOADING,
                            message = "Download cancelled.",
                            artifactStates = artifactStates,
                            activeArtifactId = currentActiveArtifactId(artifactStates),
                        )
                        DownloadExecutionOutcome.FAILURE
                    }

                    DownloadStopDisposition.RESCHEDULE -> {
                        updateState(
                            taskId = taskId,
                            modelId = task.modelId,
                            version = task.version,
                            status = DownloadTaskStatus.QUEUED,
                            progressBytes = current?.progressBytes
                                ?: artifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) },
                            totalBytes = current?.totalBytes ?: task.totalBytes,
                            processingStage = DownloadProcessingStage.DOWNLOADING,
                            verificationPolicy = current?.verificationPolicy ?: task.verificationPolicy,
                            message = "Download interrupted. Rescheduling.",
                            clearTransferMetrics = true,
                            artifactStates = artifactStates,
                            activeArtifactId = currentActiveArtifactId(artifactStates),
                            sourceKind = task.sourceKind,
                        )
                        DownloadExecutionOutcome.RETRY
                    }
                }
            }
            throw cancellation
        } catch (error: IOException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.NETWORK_ERROR,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = error.message ?: "Download failed.",
                artifactStates = artifactStates,
                activeArtifactId = currentActiveArtifactId(artifactStates),
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        } catch (error: RuntimeException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.UNKNOWN,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = error.message ?: "Download failed unexpectedly.",
                artifactStates = artifactStates,
                activeArtifactId = currentActiveArtifactId(artifactStates),
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        }
    }

    private fun taskWorkspaceDirectory(taskId: String): File {
        return File(provisioningStore.managedDownloadWorkspaceDirectory(), taskId)
    }

    private fun artifactPartFile(workspaceDir: File, artifact: DownloadArtifactTaskState): File {
        return File(workspaceDir, "${safeArtifactToken(artifact)}.part")
    }

    private fun artifactVerifiedFile(workspaceDir: File, artifact: DownloadArtifactTaskState): File {
        val stagedName = artifact.stagedFileName?.takeIf { it.isNotBlank() } ?: "${safeArtifactToken(artifact)}.verified"
        return File(workspaceDir, stagedName)
    }

    private fun cleanupWorkspace(workspaceDir: File) {
        workspaceDir.deleteRecursively()
    }

    private fun destinationFileForArtifact(
        modelId: String,
        version: String,
        artifact: DownloadArtifactTaskState,
    ): File {
        if (artifact.role == ModelArtifactRole.PRIMARY_GGUF) {
            return provisioningStore.destinationFileForVersion(modelId = modelId, version = version)
        }
        val ext = artifact.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        val base = buildString {
            append(modelId.replace(Regex("[^a-zA-Z0-9._-]"), "-"))
            append("-")
            append(version.replace(Regex("[^a-zA-Z0-9._-]"), "-"))
            append("-")
            append(artifact.role.name.lowercase())
            append("-")
            append(sha256Hex("${modelId}|${version}|${artifact.artifactId}|${artifact.fileName}".encodeToByteArray()).take(12))
        }
        return File(provisioningStore.managedModelDirectory(), "$base$ext")
    }

    private fun safeArtifactToken(artifact: DownloadArtifactTaskState): String {
        val basis = artifact.artifactId.ifBlank { artifact.fileName }
        return basis.replace(Regex("[^a-zA-Z0-9._-]"), "-")
    }

    private fun List<DownloadArtifactTaskState>.updateArtifact(
        artifactId: String,
        transform: (DownloadArtifactTaskState) -> DownloadArtifactTaskState,
    ): List<DownloadArtifactTaskState> {
        return map { artifact ->
            if (artifact.artifactId == artifactId) transform(artifact) else artifact
        }
    }

    private fun List<DownloadArtifactTaskState>.markArtifactFailed(
        artifactId: String,
        reason: DownloadFailureReason,
    ): List<DownloadArtifactTaskState> {
        return updateArtifact(artifactId) { artifact ->
            artifact.copy(
                status = DownloadArtifactTaskStatus.FAILED,
                failureReason = reason,
            )
        }
    }

    private fun currentActiveArtifactId(states: List<DownloadArtifactTaskState>): String? {
        return states.firstOrNull { artifact ->
            artifact.status == DownloadArtifactTaskStatus.DOWNLOADING ||
                artifact.status == DownloadArtifactTaskStatus.FAILED
        }?.artifactId ?: states.firstOrNull { artifact ->
            artifact.status == DownloadArtifactTaskStatus.PENDING
        }?.artifactId
    }

    private suspend fun downloadArtifactToPartial(
        task: DownloadTaskState,
        artifact: DownloadArtifactTaskState,
        partFile: File,
        host: DownloadExecutionHost,
        network: Network?,
        onProgress: suspend (downloaded: Long, expectedTotal: Long, transferMetrics: TransferMetrics) -> Unit,
        onResumeMetadata: (resumeEtag: String?, resumeLastModified: String?) -> Unit,
    ): DownloadResult {
        var existingBytes = partFile.length().coerceAtLeast(0L)
        var lastMetricBytes = artifact.progressBytes.coerceAtLeast(0L).coerceAtMost(existingBytes)
        var lastMetricEpochMs = task.lastProgressEpochMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        var smoothedSpeedBps = task.downloadSpeedBps
        var resumeEtag = artifact.resumeEtag
        var resumeLastModified = artifact.resumeLastModified
        var resumeHeaderValue = resolveIfRangeHeader(artifact)
        if (existingBytes > 0L && resumeHeaderValue == null) {
            partFile.delete()
            existingBytes = 0L
            lastMetricBytes = 0L
            lastMetricEpochMs = System.currentTimeMillis()
            resumeEtag = null
            resumeLastModified = null
            onResumeMetadata(null, null)
        }

        val requestBuilder = Request.Builder().get().url(artifact.downloadUrl)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
            requestBuilder.header("If-Range", resumeHeaderValue!!)
        }
        DownloadHttpClient.forNetwork(network).newCall(requestBuilder.build()).execute().use { response ->
            val responseCode = response.code
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Download server returned HTTP $responseCode")
            }
            val baseline = resolveResumeTransferBaseline(
                responseCode = responseCode,
                existingBytes = existingBytes,
                metricBytes = lastMetricBytes,
                metricEpochMs = lastMetricEpochMs,
                nowEpochMs = System.currentTimeMillis(),
            )
            if (baseline.truncatePartialFile) {
                partFile.delete()
                resumeEtag = null
                resumeLastModified = null
                onResumeMetadata(null, null)
            }
            existingBytes = baseline.existingBytes
            lastMetricBytes = baseline.metricBytes
            lastMetricEpochMs = baseline.metricEpochMs

            val capturedStrongEtag = response.header("ETag")?.trim()?.takeIf(::isStrongEtag)
            val capturedLastModified = response.header("Last-Modified")?.trim()?.takeIf { capturedStrongEtag == null && !it.isNullOrBlank() }
            if (capturedStrongEtag != null || capturedLastModified != null) {
                resumeEtag = capturedStrongEtag
                resumeLastModified = capturedLastModified
                onResumeMetadata(resumeEtag, resumeLastModified)
            }
            val responseBody = response.body ?: throw IOException("Download response body was empty.")
            val bodyLength = responseBody.contentLength().coerceAtLeast(0L)
            val expectedTotal = when {
                artifact.totalBytes > 0L -> artifact.totalBytes
                responseCode == HttpURLConnection.HTTP_PARTIAL -> existingBytes + bodyLength
                else -> bodyLength
            }
            val bodyStream = responseBody.byteStream()
            val digest = if (existingBytes == 0L) MessageDigest.getInstance("SHA-256") else null
            val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            bodyStream.use { input ->
                FileOutputStream(partFile, append).use { fileOutput ->
                    BufferedOutputStream(fileOutput, COPY_BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var downloaded = existingBytes
                        var bytesSinceUpdate = 0L
                        var lastBytesReceivedEpochMs = System.currentTimeMillis()
                        var slowWindowStartEpochMs = lastBytesReceivedEpochMs
                        var slowWindowStartBytes = downloaded
                        while (true) {
                            if (host.isStopped()) {
                                throw CancellationException("Download execution stopped")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            val nowEpochMs = System.currentTimeMillis()
                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            downloaded += read
                            bytesSinceUpdate += read
                            lastBytesReceivedEpochMs = nowEpochMs

                            if (nowEpochMs - slowWindowStartEpochMs >= SLOW_DRIP_WINDOW_MS) {
                                if (downloaded - slowWindowStartBytes < SLOW_DRIP_MIN_PROGRESS_BYTES) {
                                    throw SocketTimeoutException("Download throughput fell below the slow-drip threshold.")
                                }
                                slowWindowStartEpochMs = nowEpochMs
                                slowWindowStartBytes = downloaded
                            }

                            if (nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS) {
                                ensureStorageCapacity(partFile = partFile, downloaded = downloaded, expectedTotal = expectedTotal)
                            }

                            val shouldFlushProgress = bytesSinceUpdate >= PROGRESS_UPDATE_STEP_BYTES &&
                                nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS
                            val shouldFlushTimed = nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS
                            val shouldFlushCompletion = expectedTotal > 0L && downloaded >= expectedTotal
                            if (shouldFlushProgress || shouldFlushTimed || shouldFlushCompletion) {
                                val percent = if (expectedTotal <= 0L) 0 else ((downloaded * 100L) / expectedTotal).toInt()
                                val transferMetrics = calculateTransferMetrics(
                                    previousBytes = lastMetricBytes,
                                    previousEpochMs = lastMetricEpochMs,
                                    currentBytes = downloaded,
                                    currentEpochMs = nowEpochMs,
                                    totalBytes = expectedTotal,
                                    previousSmoothedSpeedBps = smoothedSpeedBps,
                                )
                                onProgress(downloaded, expectedTotal, transferMetrics)
                                lastMetricBytes = downloaded
                                lastMetricEpochMs = nowEpochMs
                                smoothedSpeedBps = transferMetrics.downloadSpeedBps
                                bytesSinceUpdate = 0L
                            }

                            if (System.currentTimeMillis() - lastBytesReceivedEpochMs > HARD_STALL_TIMEOUT_MS) {
                                throw SocketTimeoutException("Download stalled.")
                            }
                        }
                        output.flush()
                        runCatching { fileOutput.fd.sync() }
                        if (expectedTotal > 0L && downloaded != expectedTotal) {
                            throw IOException("Download truncated. Expected $expectedTotal bytes, received $downloaded bytes.")
                        }
                        ensureStorageCapacity(partFile = partFile, downloaded = downloaded, expectedTotal = expectedTotal)
                        return DownloadResult(
                            downloadedBytes = downloaded,
                            computedTotalBytes = expectedTotal,
                            sha256Hex = digest?.digest()?.joinToString(separator = "") { "%02x".format(it) },
                            resumeEtag = resumeEtag,
                            resumeLastModified = resumeLastModified,
                        )
                    }
                }
            }
        }
    }

    private fun verifyProvenanceSignature(
        issuer: String,
        modelId: String,
        sha: String,
        expectedSignature: String,
    ): Boolean {
        if (expectedSignature.isBlank()) {
            return false
        }
        val normalizedIssuer = issuer.ifBlank { "internal-release" }
        val computed = sha256Hex("$normalizedIssuer|$modelId|$sha|v1".encodeToByteArray())
        return expectedSignature.equals(computed, ignoreCase = true)
    }

    private fun updateState(
        taskId: String,
        modelId: String,
        version: String,
        status: DownloadTaskStatus,
        progressBytes: Long,
        totalBytes: Long,
        processingStage: DownloadProcessingStage,
        verificationPolicy: DownloadVerificationPolicy,
        message: String,
        reason: DownloadFailureReason? = null,
        transferMetrics: TransferMetrics? = null,
        clearTransferMetrics: Boolean = false,
        artifactStates: List<DownloadArtifactTaskState>? = null,
        activeArtifactId: String? = null,
        sourceKind: com.pocketagent.core.model.ModelSourceKind? = null,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId)
        val resolvedArtifactStates = artifactStates ?: previous?.artifactStates.orEmpty()
        val resolvedProgressBytes = if (resolvedArtifactStates.isNotEmpty()) {
            resolvedArtifactStates.sumOf { artifact -> artifact.progressBytes.coerceAtLeast(0L) }
        } else {
            progressBytes.coerceAtLeast(0L)
        }
        val resolvedTotalBytes = if (resolvedArtifactStates.isNotEmpty()) {
            resolvedArtifactStates.sumOf { artifact -> artifact.totalBytes.coerceAtLeast(0L) }
                .coerceAtLeast(resolvedProgressBytes)
        } else {
            totalBytes.coerceAtLeast(0L).coerceAtLeast(progressBytes.coerceAtLeast(0L))
        }
        val next = (previous ?: DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            sourceKind = sourceKind ?: com.pocketagent.core.model.ModelSourceKind.BUILT_IN,
            downloadUrl = "",
            expectedSha256 = "",
            provenanceIssuer = "",
            provenanceSignature = "",
            verificationPolicy = verificationPolicy,
            runtimeCompatibility = "",
            promptProfileId = null,
            processingStage = processingStage,
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            totalBytes = 0L,
            updatedAtEpochMs = System.currentTimeMillis(),
        )).copy(
            status = status,
            sourceKind = sourceKind ?: previous?.sourceKind ?: com.pocketagent.core.model.ModelSourceKind.BUILT_IN,
            progressBytes = resolvedProgressBytes,
            totalBytes = resolvedTotalBytes,
            downloadSpeedBps = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.downloadSpeedBps
                else -> previous?.downloadSpeedBps
            },
            etaSeconds = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.etaSeconds
                else -> previous?.etaSeconds
            },
            lastProgressEpochMs = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.lastProgressEpochMs
                else -> previous?.lastProgressEpochMs
            },
            processingStage = processingStage,
            verificationPolicy = verificationPolicy,
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = reason,
            message = message,
            artifactStates = resolvedArtifactStates,
            activeArtifactId = activeArtifactId,
        )
        ModelDownloadTaskStateStore.upsert(appContext, next)
    }

    private fun fail(
        taskId: String,
        modelId: String,
        version: String,
        reason: DownloadFailureReason,
        processingStage: DownloadProcessingStage,
        message: String,
        progressBytes: Long? = null,
        totalBytes: Long? = null,
        artifactStates: List<DownloadArtifactTaskState>? = null,
        activeArtifactId: String? = null,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId)
        val resolvedProgress = progressBytes
            ?: previous?.progressBytes
            ?: 0L
        val resolvedTotal = (totalBytes
            ?: previous?.totalBytes
            ?: 0L)
            .coerceAtLeast(0L)
            .coerceAtLeast(resolvedProgress.coerceAtLeast(0L))
        updateState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            status = DownloadTaskStatus.FAILED,
            progressBytes = resolvedProgress,
            totalBytes = resolvedTotal,
            processingStage = processingStage,
            verificationPolicy = previous?.verificationPolicy
                ?: DownloadVerificationPolicy.INTEGRITY_ONLY,
            message = message,
            reason = reason,
            clearTransferMetrics = true,
            artifactStates = artifactStates,
            activeArtifactId = activeArtifactId,
            sourceKind = previous?.sourceKind,
        )
    }

    private fun ensureStorageCapacity(
        partFile: File,
        downloaded: Long,
        expectedTotal: Long,
    ) {
        val directory = partFile.parentFile ?: return
        val remainingBytes = (expectedTotal - downloaded).coerceAtLeast(0L)
        val requiredBytes = remainingBytes + STORAGE_SAFETY_HEADROOM_BYTES
        if (expectedTotal > 0L && directory.usableSpace < requiredBytes) {
            throw InsufficientStorageException(
                "Not enough free space to finish the download. Required ${requiredBytes}B, available ${directory.usableSpace}B.",
            )
        }
    }

    private fun hasNetworkConnection(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sha256HexFromFile(file: File, isCancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesHashed = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                if (isCancelled()) {
                    throw CancellationException("Verification cancelled")
                }
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
                bytesHashed += read
                if (bytesHashed >= VERIFICATION_CANCEL_CHECK_STEP_BYTES) {
                    bytesHashed = 0L
                    if (isCancelled()) {
                        throw CancellationException("Verification cancelled")
                    }
                }
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun resolveIfRangeHeader(artifact: DownloadArtifactTaskState): String? {
        val strongEtag = artifact.resumeEtag?.trim()?.takeIf(::isStrongEtag)
        if (strongEtag != null) {
            return strongEtag
        }
        return artifact.resumeLastModified?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isStrongEtag(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() && !trimmed.startsWith("W/")
    }

    private class InsufficientStorageException(
        message: String,
    ) : IOException(message)

    companion object {
        private const val LOG_TAG = "ModelDownloadExecutor"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_STEP_BYTES = 1024 * 256
        private const val MIN_PROGRESS_INTERVAL_MS = 500L
        private const val VERIFICATION_CANCEL_CHECK_STEP_BYTES = 10L * 1024L * 1024L
        private const val HARD_STALL_TIMEOUT_MS = 30_000L
        private const val SLOW_DRIP_WINDOW_MS = 2 * 60 * 1000L
        private const val SLOW_DRIP_MIN_PROGRESS_BYTES = 512L * 1024L
        private const val STORAGE_SAFETY_HEADROOM_BYTES = 32L * 1024L * 1024L
    }
}

internal fun resolveResumeTransferBaseline(
    responseCode: Int,
    existingBytes: Long,
    metricBytes: Long,
    metricEpochMs: Long,
    nowEpochMs: Long,
): ResumeTransferBaseline {
    val safeNow = nowEpochMs.takeIf { it > 0L } ?: 1L
    val safeExisting = existingBytes.coerceAtLeast(0L)
    if (responseCode == HttpURLConnection.HTTP_OK && safeExisting > 0L) {
        return ResumeTransferBaseline(
            existingBytes = 0L,
            metricBytes = 0L,
            metricEpochMs = safeNow,
            truncatePartialFile = true,
        )
    }
    return ResumeTransferBaseline(
        existingBytes = safeExisting,
        metricBytes = metricBytes.coerceAtLeast(0L).coerceAtMost(safeExisting),
        metricEpochMs = metricEpochMs.takeIf { it > 0L } ?: safeNow,
        truncatePartialFile = false,
    )
}

internal fun calculateTransferMetrics(
    previousBytes: Long,
    previousEpochMs: Long,
    currentBytes: Long,
    currentEpochMs: Long,
    totalBytes: Long,
    previousSmoothedSpeedBps: Long? = null,
): TransferMetrics {
    val safeCurrentBytes = currentBytes.coerceAtLeast(previousBytes.coerceAtLeast(0L))
    val deltaBytes = (safeCurrentBytes - previousBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
    val elapsedMs = (currentEpochMs - previousEpochMs).coerceAtLeast(1L)
    val rawSpeedBps = if (deltaBytes > 0L) {
        ((deltaBytes * 1000L) / elapsedMs).coerceAtLeast(1L)
    } else {
        null
    }
    val speedBps = when {
        rawSpeedBps == null -> previousSmoothedSpeedBps
        previousSmoothedSpeedBps == null -> rawSpeedBps
        else -> ((EMA_ALPHA * rawSpeedBps) + ((1.0 - EMA_ALPHA) * previousSmoothedSpeedBps))
            .toLong()
            .coerceAtLeast(1L)
    }
    val etaSeconds = if ((speedBps ?: 0L) > 0L && totalBytes > 0L) {
        ((totalBytes - safeCurrentBytes).coerceAtLeast(0L) / (speedBps ?: 1L))
            .coerceAtLeast(0L)
    } else {
        null
    }
    return TransferMetrics(
        downloadSpeedBps = speedBps,
        etaSeconds = etaSeconds,
        lastProgressEpochMs = currentEpochMs.takeIf { it > 0L },
    )
}

private const val EMA_ALPHA = 0.2
