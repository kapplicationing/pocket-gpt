package com.pocketagent.android.runtime.modelmanager

import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelParameterProfile
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef

data class ModelDistributionManifest(
    val models: List<ModelDistributionModel>,
    val source: ManifestSource = ManifestSource.BUNDLED,
    val syncedAtEpochMs: Long? = null,
    val lastError: String? = null,
)

enum class ManifestSource {
    BUNDLED,
    REMOTE,
    BUNDLED_AND_REMOTE,
}

data class ModelDistributionModel(
    val modelId: String,
    val displayName: String,
    val versions: List<ModelDistributionVersion>,
)

data class ModelDistributionArtifact(
    val artifactId: String,
    val role: ModelArtifactRole,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val required: Boolean = true,
    val verificationPolicy: DownloadVerificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
)

data class ModelDistributionVersion(
    val modelId: String,
    val version: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val verificationPolicy: DownloadVerificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
    val sourceKind: ModelSourceKind = ModelSourceKind.BUILT_IN,
    val promptProfileId: String? = null,
    val displayName: String? = null,
    val sourceRef: ModelSourceRef? = null,
    val artifacts: List<ModelDistributionArtifact> = listOf(
        ModelDistributionArtifact(
            artifactId = "$modelId::$version::primary",
            role = ModelArtifactRole.PRIMARY_GGUF,
            fileName = downloadUrl.substringAfterLast('/').ifBlank { "$modelId-$version.gguf" },
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = fileSizeBytes,
            verificationPolicy = verificationPolicy,
        ),
    ),
)

enum class DownloadNetworkPreference {
    ALLOW_METERED,
    UNMETERED_ONLY,
}

data class DownloadRequestOptions(
    val networkPreference: DownloadNetworkPreference = DownloadNetworkPreference.ALLOW_METERED,
    val userInitiated: Boolean = true,
)

data class DownloadPreferencesState(
    val wifiOnlyEnabled: Boolean = false,
    val largeDownloadCellularWarningAcknowledged: Boolean = false,
)

data class ModelVersionDescriptor(
    val modelId: String,
    val version: String,
    val displayName: String,
    val absolutePath: String,
    val sha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val importedAtEpochMs: Long,
    val isActive: Boolean,
    val sourceKind: ModelSourceKind = ModelSourceKind.LOCAL_IMPORT,
    val sourceRef: ModelSourceRef? = null,
    val artifacts: List<InstalledArtifactDescriptor> = listOf(
        InstalledArtifactDescriptor(
            artifactId = "$modelId::$version::primary",
            role = ModelArtifactRole.PRIMARY_GGUF,
            fileName = absolutePath.substringAfterLast('/').ifBlank { "$modelId-$version.gguf" },
            absolutePath = absolutePath,
            expectedSha256 = sha256,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = fileSizeBytes,
        ),
    ),
    val promptProfileId: String? = null,
    val parameters: ModelParameterProfile = ModelParameterProfile(),
)

data class InstalledArtifactDescriptor(
    val artifactId: String,
    val role: ModelArtifactRole,
    val fileName: String,
    val absolutePath: String? = null,
    val expectedSha256: String? = null,
    val runtimeCompatibility: String? = null,
    val fileSizeBytes: Long? = null,
    val required: Boolean = true,
)

enum class DownloadTaskStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED_INACTIVE,
    FAILED,
    COMPLETED,
    CANCELLED,
}

enum class DownloadVerificationPolicy {
    INTEGRITY_ONLY,
    PROVENANCE_STRICT,
    UNKNOWN;

    val enforcesProvenance: Boolean
        get() = this == PROVENANCE_STRICT
}

enum class DownloadProcessingStage {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    CORRUPT,
}

enum class DownloadFailureReason {
    NETWORK_UNAVAILABLE,
    NETWORK_ERROR,
    TIMEOUT,
    INSUFFICIENT_STORAGE,
    CHECKSUM_MISMATCH,
    PROVENANCE_MISMATCH,
    RUNTIME_INCOMPATIBLE,
    CANCELLED,
    UNKNOWN,
}

enum class DownloadArtifactTaskStatus {
    PENDING,
    DOWNLOADING,
    VERIFIED,
    INSTALLED,
    FAILED,
}

data class DownloadArtifactTaskState(
    val artifactId: String,
    val role: ModelArtifactRole,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val verificationPolicy: DownloadVerificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val required: Boolean = true,
    val progressBytes: Long = 0L,
    val totalBytes: Long = fileSizeBytes.coerceAtLeast(0L),
    val resumeEtag: String? = null,
    val resumeLastModified: String? = null,
    val verifiedSha256: String? = null,
    val stagedFileName: String? = null,
    val installedAbsolutePath: String? = null,
    val status: DownloadArtifactTaskStatus = DownloadArtifactTaskStatus.PENDING,
    val failureReason: DownloadFailureReason? = null,
)

data class DownloadTaskState(
    val taskId: String,
    val modelId: String,
    val version: String,
    val sourceKind: ModelSourceKind = ModelSourceKind.BUILT_IN,
    val displayName: String? = null,
    val sourceRef: ModelSourceRef? = null,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val verificationPolicy: DownloadVerificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
    val runtimeCompatibility: String,
    val promptProfileId: String? = null,
    val processingStage: DownloadProcessingStage = DownloadProcessingStage.DOWNLOADING,
    val status: DownloadTaskStatus,
    val progressBytes: Long,
    val totalBytes: Long,
    val resumeEtag: String? = null,
    val resumeLastModified: String? = null,
    val queueOrder: Long = 0L,
    val networkPreference: DownloadNetworkPreference = DownloadNetworkPreference.ALLOW_METERED,
    val downloadSpeedBps: Long? = null,
    val etaSeconds: Long? = null,
    val lastProgressEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
    val failureReason: DownloadFailureReason? = null,
    val message: String? = null,
    val artifactStates: List<DownloadArtifactTaskState> = listOf(
        DownloadArtifactTaskState(
            artifactId = "$modelId::$version::primary",
            role = ModelArtifactRole.PRIMARY_GGUF,
            fileName = downloadUrl.substringAfterLast('/').ifBlank { "$modelId-$version.gguf" },
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            verificationPolicy = verificationPolicy,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = totalBytes.coerceAtLeast(0L),
            progressBytes = progressBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(progressBytes.coerceAtLeast(0L)),
            resumeEtag = resumeEtag,
            resumeLastModified = resumeLastModified,
            status = when {
                status == DownloadTaskStatus.FAILED -> DownloadArtifactTaskStatus.FAILED
                status == DownloadTaskStatus.VERIFYING -> DownloadArtifactTaskStatus.VERIFIED
                status == DownloadTaskStatus.DOWNLOADING -> DownloadArtifactTaskStatus.DOWNLOADING
                (status == DownloadTaskStatus.COMPLETED || status == DownloadTaskStatus.INSTALLED_INACTIVE) &&
                    failureReason == null -> DownloadArtifactTaskStatus.INSTALLED
                else -> DownloadArtifactTaskStatus.PENDING
            },
            failureReason = failureReason,
        ),
    ),
    val activeArtifactId: String? = null,
) {
    val progressPercent: Int
        get() {
            if (totalBytes <= 0L) {
                if (progressBytes <= 0L) {
                    return 0
                }
                if (processingStage != DownloadProcessingStage.DOWNLOADING || status == DownloadTaskStatus.FAILED) {
                    return 100
                }
                return 0
            }
            return ((progressBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
        }

    val terminal: Boolean
        get() = status == DownloadTaskStatus.FAILED ||
            status == DownloadTaskStatus.CANCELLED ||
            status == DownloadTaskStatus.COMPLETED ||
            status == DownloadTaskStatus.INSTALLED_INACTIVE

    val hasThroughputEstimate: Boolean
        get() = (downloadSpeedBps ?: 0L) > 0L && (etaSeconds ?: -1L) >= 0L
}

fun ModelDistributionVersion.bundleTotalBytes(): Long {
    return artifacts.sumOf { artifact -> artifact.fileSizeBytes.coerceAtLeast(0L) }
}

data class StorageSummary(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedByModelsBytes: Long,
    val tempDownloadBytes: Long,
)
