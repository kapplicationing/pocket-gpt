package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogEligibilityEvaluatorTest {
    private val evaluator = DefaultModelCatalogEligibilityEvaluator()

    @Test
    fun `enabled catalog release stays supported`() {
        val snapshot = evaluator.evaluate(
            manifest = manifestFor(
                manifestVersion(
                    modelId = "qwen3-0.6b-q4_k_m",
                    version = "q4_k_m",
                    runtimeCompatibility = "android-arm64-v8a",
                ),
            ),
            snapshot = null,
            signals = ModelEligibilitySignals.assumeSupported(),
        )

        val eligibility = snapshot.eligibilityFor("qwen3-0.6b-q4_k_m", "q4_k_m")
        assertEquals(ModelSupportLevel.SUPPORTED, eligibility.supportLevel)
        assertTrue(eligibility.catalogVisible)
        assertTrue(eligibility.downloadAllowed)
        assertTrue(eligibility.loadAllowed)
    }

    @Test
    fun `runtime compatibility mismatch blocks catalog release`() {
        val snapshot = evaluator.evaluate(
            manifest = manifestFor(
                manifestVersion(
                    modelId = "qwen3-0.6b-q4_k_m",
                    version = "q4_k_m",
                    runtimeCompatibility = "other-runtime",
                ),
            ),
            snapshot = null,
            signals = ModelEligibilitySignals.assumeSupported(),
        )

        val eligibility = snapshot.eligibilityFor("qwen3-0.6b-q4_k_m", "q4_k_m")
        assertEquals(ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH, eligibility.reason)
        assertFalse(eligibility.downloadAllowed)
        assertFalse(eligibility.loadAllowed)
    }

    @Test
    fun `installed versions also receive compatibility decisions`() {
        val modelId = "qwen3-0.6b-q4_k_m"
        val version = "q4_k_m"
        val snapshot = evaluator.evaluate(
            manifest = ModelDistributionManifest(models = emptyList()),
            snapshot = RuntimeProvisioningSnapshot(
                models = listOf(
                    ProvisionedModelState(
                        modelId = modelId,
                        displayName = "Qwen",
                        fileName = "qwen.gguf",
                        absolutePath = "/tmp/qwen.gguf",
                        sha256 = "a".repeat(64),
                        importedAtEpochMs = 1L,
                        activeVersion = version,
                        installedVersions = listOf(
                            ModelVersionDescriptor(
                                modelId = modelId,
                                version = version,
                                displayName = "Qwen",
                                absolutePath = "/tmp/qwen.gguf",
                                sha256 = "a".repeat(64),
                                provenanceIssuer = "issuer",
                                provenanceSignature = "sig",
                                runtimeCompatibility = "other-runtime",
                                fileSizeBytes = 1L,
                                importedAtEpochMs = 1L,
                                isActive = true,
                            ),
                        ),
                    ),
                ),
                storageSummary = StorageSummary(
                    totalBytes = 1L,
                    freeBytes = 1L,
                    usedByModelsBytes = 1L,
                    tempDownloadBytes = 0L,
                ),
                requiredModelIds = emptySet(),
            ),
            signals = ModelEligibilitySignals.assumeSupported(),
        )

        val eligibility = snapshot.eligibilityFor(modelId, version)
        assertEquals(ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH, eligibility.reason)
        assertFalse(eligibility.loadAllowed)
    }

    private fun manifestFor(version: ModelDistributionVersion): ModelDistributionManifest {
        return ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = version.modelId,
                    displayName = version.modelId,
                    versions = listOf(version),
                ),
            ),
        )
    }

    private fun manifestVersion(
        modelId: String,
        version: String,
        runtimeCompatibility: String,
    ): ModelDistributionVersion {
        return ModelDistributionVersion(
            modelId = modelId,
            version = version,
            downloadUrl = "https://example.test/$modelId/$version.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = 1L,
        )
    }
}
