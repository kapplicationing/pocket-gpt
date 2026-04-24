package com.pocketagent.android.runtime

import android.content.SharedPreferences
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary

data class ProvisionedModelState(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String?,
    val sha256: String?,
    val importedAtEpochMs: Long?,
    val activeVersion: String?,
    val installedVersions: List<ModelVersionDescriptor>,
    val pathOrigin: String = ModelPathOrigin.MANAGED,
    val versionPathOrigins: Map<String, String> = emptyMap(),
    val storageRootLabel: String? = null,
    val localFileMissing: Boolean = false,
) {
    val isProvisioned: Boolean
        get() = !localFileMissing && !absolutePath.isNullOrBlank() && !sha256.isNullOrBlank()

    fun pathOriginForVersion(version: String): String {
        return versionPathOrigins[version] ?: pathOrigin
    }
}

data class ProvisioningRecoverySignal(
    val code: String,
    val message: String,
    val technicalDetail: String,
)

data class RuntimeProvisioningSnapshot(
    val models: List<ProvisionedModelState>,
    val storageSummary: StorageSummary,
    val requiredModelIds: Set<String>,
    val storageRootLabel: String? = null,
    val recoverableCorruptions: List<ProvisioningRecoverySignal> = emptyList(),
) {
    companion object {
        fun empty(): RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot(
            models = emptyList(),
            storageSummary = StorageSummary(
                totalBytes = 0L,
                freeBytes = 0L,
                usedByModelsBytes = 0L,
                tempDownloadBytes = 0L,
            ),
            requiredModelIds = emptySet(),
        )
    }

    val verifiedActiveModelCount: Int
        get() = models.count { it.modelId in requiredModelIds && it.isProvisioned && !it.activeVersion.isNullOrBlank() }

    val missingRequiredModelIds: Set<String>
        get() = requiredModelIds.filterNot { modelId ->
            models.any { it.modelId == modelId && it.isProvisioned && !it.activeVersion.isNullOrBlank() }
        }.toSet()

    val readiness: ProvisioningReadiness
        get() = when {
            verifiedActiveModelCount <= 0 -> ProvisioningReadiness.BLOCKED
            missingRequiredModelIds.isEmpty() -> ProvisioningReadiness.READY
            else -> ProvisioningReadiness.DEGRADED
        }

    val allRequiredModelsProvisioned: Boolean
        get() = models.filter { it.modelId in requiredModelIds }.all { it.isProvisioned }

    val hasRecoverableCorruption: Boolean
        get() = recoverableCorruptions.isNotEmpty()
}

object ModelPathOrigin {
    const val MANAGED = "managed"
    const val IMPORTED_EXTERNAL = "imported_external"
    const val DISCOVERED_RECOVERED = "discovered_recovered"
}

enum class ProvisioningReadiness {
    READY,
    DEGRADED,
    BLOCKED,
}

data class RuntimeModelImportResult(
    val modelId: String,
    val version: String,
    val absolutePath: String,
    val sha256: String,
    val copiedBytes: Long,
    val isActive: Boolean,
)

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun SharedPreferences.readOptional(key: String): String? {
    return getString(key, null)?.trim()?.takeIf { value -> value.isNotEmpty() }
}

internal fun SharedPreferences.readOptionalLong(key: String): Long? {
    return if (contains(key)) getLong(key, 0L) else null
}
