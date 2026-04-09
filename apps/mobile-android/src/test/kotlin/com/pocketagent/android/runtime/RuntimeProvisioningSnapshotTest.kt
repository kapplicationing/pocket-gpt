package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeProvisioningSnapshotTest {
    @Test
    fun `readiness is blocked when no required active model is provisioned`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                state(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M, activeVersion = null, absolutePath = null, sha = null),
                state(modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M, activeVersion = null, absolutePath = null, sha = null),
            ),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )

        assertEquals(ProvisioningReadiness.BLOCKED, snapshot.readiness)
        assertEquals(0, snapshot.verifiedActiveModelCount)
    }

    @Test
    fun `readiness is degraded when at least one required active model is provisioned`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                state(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M, activeVersion = "q4_k_m"),
                state(modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M, activeVersion = null, absolutePath = null, sha = null),
            ),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )

        assertEquals(ProvisioningReadiness.DEGRADED, snapshot.readiness)
        assertEquals(1, snapshot.verifiedActiveModelCount)
        assertEquals(setOf(ModelCatalog.QWEN3_1_7B_Q4_K_M), snapshot.missingRequiredModelIds)
    }

    @Test
    fun `readiness is ready when all required active models are provisioned`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                state(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M, activeVersion = "q4_k_m"),
                state(modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M, activeVersion = "q4_k_m"),
            ),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )

        assertEquals(ProvisioningReadiness.READY, snapshot.readiness)
        assertEquals(2, snapshot.verifiedActiveModelCount)
        assertEquals(emptySet(), snapshot.missingRequiredModelIds)
    }

    @Test
    fun `missing local file is treated as not provisioned`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                state(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M, activeVersion = "q4_k_m", localFileMissing = true),
                state(modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M, activeVersion = "q4_k_m"),
            ),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )

        assertEquals(ProvisioningReadiness.DEGRADED, snapshot.readiness)
        assertEquals(setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M), snapshot.missingRequiredModelIds)
    }

    @Test
    fun `recoverable corruption flag is true when snapshot has diagnostics`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(state(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M, activeVersion = null, absolutePath = null, sha = null)),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M),
            recoverableCorruptions = listOf(
                ProvisioningRecoverySignal(
                    code = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                    message = "Stored model metadata was corrupted.",
                    technicalDetail = "model=qwen",
                ),
            ),
        )

        assertTrue(snapshot.hasRecoverableCorruption)
    }

    @Test
    fun `snapshot stores root label and model path origin`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                state(
                    modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                    activeVersion = "q4_k_m",
                    pathOrigin = ModelPathOrigin.IMPORTED_EXTERNAL,
                    storageRootLabel = "App internal storage (/data/user/0/app/files)",
                ),
            ),
            storageSummary = emptyStorage(),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M),
            storageRootLabel = "App internal storage (/data/user/0/app/files)",
        )

        assertEquals("App internal storage (/data/user/0/app/files)", snapshot.storageRootLabel)
        assertEquals(ModelPathOrigin.IMPORTED_EXTERNAL, snapshot.models.single().pathOrigin)
    }

    @Test
    fun `pathOriginForVersion prefers version specific mapping`() {
        val model = state(
            modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            activeVersion = "managed-v1",
            pathOrigin = ModelPathOrigin.MANAGED,
            versionPathOrigins = mapOf(
                "managed-v1" to ModelPathOrigin.MANAGED,
                "external-v2" to ModelPathOrigin.IMPORTED_EXTERNAL,
            ),
        )

        assertEquals(ModelPathOrigin.MANAGED, model.pathOriginForVersion("managed-v1"))
        assertEquals(ModelPathOrigin.IMPORTED_EXTERNAL, model.pathOriginForVersion("external-v2"))
        assertEquals(ModelPathOrigin.MANAGED, model.pathOriginForVersion("missing-version"))
    }

    private fun state(
        modelId: String,
        activeVersion: String?,
        absolutePath: String? = "/tmp/$modelId.gguf",
        sha: String? = "sha-$modelId",
        pathOrigin: String = ModelPathOrigin.MANAGED,
        versionPathOrigins: Map<String, String> = emptyMap(),
        storageRootLabel: String? = null,
        localFileMissing: Boolean = false,
    ): ProvisionedModelState {
        return ProvisionedModelState(
            modelId = modelId,
            displayName = modelId,
            fileName = "$modelId.gguf",
            absolutePath = absolutePath,
            sha256 = sha,
            importedAtEpochMs = null,
            activeVersion = activeVersion,
            installedVersions = emptyList(),
            pathOrigin = pathOrigin,
            versionPathOrigins = versionPathOrigins,
            storageRootLabel = storageRootLabel,
            localFileMissing = localFileMissing,
        )
    }

    private fun emptyStorage(): StorageSummary {
        return StorageSummary(
            totalBytes = 0L,
            freeBytes = 0L,
            usedByModelsBytes = 0L,
            tempDownloadBytes = 0L,
        )
    }
}
