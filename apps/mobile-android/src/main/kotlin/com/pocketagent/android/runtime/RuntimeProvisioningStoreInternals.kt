package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.inference.ModelCatalog
import java.util.Locale

internal const val PROVISIONING_PREFS_NAME = "pocketagent_runtime_models"
internal const val PROVISIONING_LEGACY_MODEL_DIR_NAME = "runtime-models"
internal const val PROVISIONING_DEFAULT_ISSUER = "internal-release"
internal const val PROVISIONING_RUNTIME_COMPATIBILITY_TAG = "android-arm64-v8a"
internal const val PROVISIONING_COPY_BUFFER_SIZE_BYTES = 1024 * 1024
internal const val PROVISIONING_INITIAL_MIGRATION_VERSION = "1.0.0-initial"
internal const val PROVISIONING_DISCOVERED_VERSION_FALLBACK = "discovered"
internal const val PROVISIONING_DYNAMIC_MODEL_IDS_KEY = "dynamic_model_ids_json"
internal const val PROVISIONING_PATH_ALIAS_MIGRATION_DONE_KEY = "path_alias_migration_done_v1"
internal const val PROVISIONING_CORRUPT_BACKUP_PREFIX = "runtime_provisioning_corrupt_backup"
internal const val PROVISIONING_MANAGED_MODELS_DIR_NAME = "models"
internal const val PROVISIONING_METADATA_SUFFIX = ".meta.json"
internal const val PROVISIONING_LAST_LOADED_MODEL_ID_KEY = "runtime_last_loaded_model_id"
internal const val PROVISIONING_LAST_LOADED_MODEL_VERSION_KEY = "runtime_last_loaded_model_version"

internal val PROVISIONING_MIGRATION_LOCK = Any()
internal val PROVISIONING_MIGRATION_SIGNAL_LOCK = Any()
internal val PROVISIONING_MODEL_TOKEN_SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]")

internal data class StoredVersionEntry(
    val version: String,
    val absolutePath: String,
    val sha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val importedAtEpochMs: Long,
)

internal data class ModelSpec(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val prefTag: String,
    val pathKey: String,
    val shaKey: String,
    val issuerKey: String,
    val signatureKey: String,
    val importedAtKey: String,
)

internal data class StoredEntriesReadResult(
    val entries: List<StoredVersionEntry>,
    val signal: ProvisioningRecoverySignal? = null,
)

internal data class DynamicModelIdsReadResult(
    val ids: Set<String>,
    val signal: ProvisioningRecoverySignal? = null,
)

internal data class VersionReadResult(
    val versions: List<ModelVersionDescriptor>,
    val signal: ProvisioningRecoverySignal? = null,
)

internal data class LastLoadedModelRef(
    val modelId: String,
    val version: String,
)

private data class LegacySpecOverride(
    val displayName: String,
    val fileName: String,
    val prefTag: String,
    val pathKey: String,
    val shaKey: String,
    val issuerKey: String,
    val signatureKey: String,
    val importedAtKey: String,
)

private val LEGACY_BASELINE_OVERRIDES: Map<String, LegacySpecOverride> = mapOf(
    ModelCatalog.QWEN_3_5_0_8B_Q4 to LegacySpecOverride(
        displayName = "Qwen 3.5 0.8B (Q4)",
        fileName = "qwen3.5-0.8b-q4.gguf",
        prefTag = "0_8b",
        pathKey = "model_0_8b_path",
        shaKey = "model_0_8b_sha256",
        issuerKey = "model_0_8b_issuer",
        signatureKey = "model_0_8b_signature",
        importedAtKey = "model_0_8b_imported_at",
    ),
)

internal val BASELINE_MODEL_SPECS: List<ModelSpec> = ModelCatalog.modelDescriptors()
    .asSequence()
    .filter { descriptor -> descriptor.bridgeSupported || descriptor.startupCandidate }
    .map { descriptor ->
        val modelId = descriptor.modelId
        val legacy = LEGACY_BASELINE_OVERRIDES[modelId]
        if (legacy != null) {
            return@map ModelSpec(
                modelId = modelId,
                displayName = legacy.displayName,
                fileName = legacy.fileName,
                prefTag = legacy.prefTag,
                pathKey = legacy.pathKey,
                shaKey = legacy.shaKey,
                issuerKey = legacy.issuerKey,
                signatureKey = legacy.signatureKey,
                importedAtKey = legacy.importedAtKey,
            )
        }
        val derivedPrefTag = "cat_${descriptor.envKeyToken.lowercase(Locale.US)}"
        val displayName = descriptor.modelId
        val fileName = "${descriptor.modelId}.gguf"
        ModelSpec(
            modelId = modelId,
            displayName = displayName,
            fileName = fileName,
            prefTag = derivedPrefTag,
            pathKey = "legacy_path_$derivedPrefTag",
            shaKey = "legacy_sha_$derivedPrefTag",
            issuerKey = "legacy_issuer_$derivedPrefTag",
            signatureKey = "legacy_signature_$derivedPrefTag",
            importedAtKey = "legacy_imported_at_$derivedPrefTag",
        )
    }
    .sortedBy { spec -> spec.modelId }
    .toList()

internal fun provisioningBaselineModelIdsForTesting(): Set<String> {
    return BASELINE_MODEL_SPECS.mapTo(linkedSetOf()) { spec -> spec.modelId }
}

internal fun provisioningLegacyPathKeyForTesting(modelId: String): String? {
    return BASELINE_MODEL_SPECS.firstOrNull { spec -> spec.modelId == modelId }?.pathKey
}
