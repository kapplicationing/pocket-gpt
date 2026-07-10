package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionArtifact
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.DefaultNormalizedModelCatalogRegistry
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelAdmissionPolicyTest {
    @Test
    fun `load is blocked when required artifact bundle is incomplete`() {
        val policy = DefaultModelAdmissionPolicy(
            signalsProvider = staticSignalsProvider(ModelEligibilitySignals.assumeSupported()),
            catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            launchPlanner = DefaultModelRuntimeLaunchPlanner(
                catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            ),
        )
        val modelDir = Files.createTempDirectory("admission-missing-mmproj")
        val primaryPath = modelDir.resolve("qwen.gguf").toFile().apply { writeText("primary") }.absolutePath

        val decision = policy.evaluate(
            action = ModelAdmissionAction.LOAD,
            subject = ModelVersionDescriptor(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = "q4",
                displayName = "Qwen",
                absolutePath = primaryPath,
                sha256 = "a".repeat(64),
                provenanceIssuer = "issuer",
                provenanceSignature = "sig",
                runtimeCompatibility = "android-arm64-v8a",
                fileSizeBytes = 123L,
                importedAtEpochMs = 1L,
                isActive = true,
                sourceKind = ModelSourceKind.BUILT_IN,
                artifacts = listOf(
                    InstalledArtifactDescriptor(
                        artifactId = "primary",
                        role = ModelArtifactRole.PRIMARY_GGUF,
                        fileName = "qwen.gguf",
                        absolutePath = primaryPath,
                    ),
                ),
            ).toAdmissionSubject(),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.eligibility.technicalDetail.orEmpty().contains("missing_artifacts"))
    }

    @Test
    fun `download is blocked when required artifact bundle is incomplete`() {
        val policy = DefaultModelAdmissionPolicy(
            signalsProvider = staticSignalsProvider(ModelEligibilitySignals.assumeSupported()),
            catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            launchPlanner = DefaultModelRuntimeLaunchPlanner(
                catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            ),
        )

        val decision = policy.evaluate(
            action = ModelAdmissionAction.DOWNLOAD,
            subject = ModelDistributionVersion(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = "q4_0",
                downloadUrl = "https://example.test/Qwen3.5-0.8B-Q4_0.gguf",
                expectedSha256 = "a".repeat(64),
                provenanceIssuer = "huggingface/unsloth",
                provenanceSignature = "b".repeat(64),
                runtimeCompatibility = "android-arm64-v8a",
                fileSizeBytes = 507_154_688L,
                verificationPolicy = DownloadVerificationPolicy.PROVENANCE_STRICT,
                sourceKind = ModelSourceKind.REMOTE_MANIFEST,
                artifacts = listOf(
                    ModelDistributionArtifact(
                        artifactId = "primary",
                        role = ModelArtifactRole.PRIMARY_GGUF,
                        fileName = "Qwen3.5-0.8B-Q4_0.gguf",
                        downloadUrl = "https://example.test/Qwen3.5-0.8B-Q4_0.gguf",
                        expectedSha256 = "a".repeat(64),
                        provenanceIssuer = "huggingface/unsloth",
                        provenanceSignature = "b".repeat(64),
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 507_154_688L,
                        verificationPolicy = DownloadVerificationPolicy.PROVENANCE_STRICT,
                    ),
                ),
            ).toAdmissionSubject(),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.eligibility.technicalDetail.orEmpty().contains("missing_artifacts=mmproj-F16.gguf"))
    }

    @Test
    fun `runtime domain exception uses missing artifact guidance`() {
        val error = ModelAdmissionDecision(
            action = ModelAdmissionAction.LOAD,
            subject = ModelAdmissionSubject(modelId = "qwen", version = "q4"),
            eligibility = ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.NONE,
                technicalDetail = "model=qwen|missing_artifacts=mmproj-F16.gguf",
                catalogVisible = true,
            ),
        ).asRuntimeDomainException().domainError

        assertEquals(
            "Required companion file is missing: mmproj-F16.gguf. Re-download or re-import the full model package.",
            error.userMessage,
        )
        assertTrue(error.technicalDetail.orEmpty().contains("missing_artifacts=mmproj-F16.gguf"))
    }

    @Test
    fun `lifecycle rejection maps missing artifacts to model file unavailable`() {
        val result = ModelAdmissionDecision(
            action = ModelAdmissionAction.LOAD,
            subject = ModelAdmissionSubject(modelId = "qwen", version = "q4"),
            eligibility = ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.NONE,
                technicalDetail = "model=qwen|missing_artifacts=mmproj-F16.gguf",
                catalogVisible = true,
            ),
        ).asLifecycleRejectedResult()

        assertEquals(false, result.success)
        assertEquals(ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE, result.errorCode)
        assertTrue(result.detail.orEmpty().contains("missing_artifacts=mmproj-F16.gguf"))
    }

    private fun staticSignalsProvider(signals: ModelEligibilitySignals): ModelEligibilitySignalsProvider {
        return object : ModelEligibilitySignalsProvider {
            override fun currentSignals(): ModelEligibilitySignals = signals
        }
    }
}
