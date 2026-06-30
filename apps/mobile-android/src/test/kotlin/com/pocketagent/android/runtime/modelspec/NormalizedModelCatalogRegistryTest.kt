package com.pocketagent.android.runtime.modelspec

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.CapabilityFlag
import com.pocketagent.core.model.ModelParameterProfile
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.core.model.SourceTrustPolicy
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.ModelInteractionRegistry
import com.pocketagent.runtime.SystemPromptStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NormalizedModelCatalogRegistryTest {
    @Test
    fun `manifest-only model can resolve interaction profile`() {
        val registry = DefaultNormalizedModelCatalogRegistry(
            bundledCatalogProvider = { emptyList() },
            manifestProvider = {
                ModelDistributionManifest(
                    models = listOf(
                        ModelDistributionModel(
                            modelId = "dynamic-gemma",
                            displayName = "Dynamic Gemma",
                            versions = listOf(
                                ModelDistributionVersion(
                                    modelId = "dynamic-gemma",
                                    version = "1.0.0",
                                    downloadUrl = "https://example.com/dynamic-gemma.gguf",
                                    expectedSha256 = "a".repeat(64),
                                    provenanceIssuer = "issuer",
                                    provenanceSignature = "sig",
                                    runtimeCompatibility = "android-arm64-v8a",
                                    fileSizeBytes = 1024L,
                                    sourceKind = ModelSourceKind.REMOTE_MANIFEST,
                                    promptProfileId = PromptTemplateFamily.GEMMA4.name,
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val profile = ModelInteractionRegistry(specProvider = registry).interactionProfileForModel("dynamic-gemma")

        assertEquals(PromptTemplateFamily.GEMMA4, profile.templateFamily)
        assertEquals(SystemPromptStrategy.NATIVE, profile.systemPromptStrategy)
        assertEquals("model", profile.roleNameOverrides[InteractionRole.ASSISTANT])
    }

    @Test
    fun `installed local variants merge with bundled specs without collisions`() {
        val registry = DefaultNormalizedModelCatalogRegistry(
            installedVersionsProvider = { modelId ->
                if (modelId == ModelCatalog.QWEN_3_5_0_8B_Q4) {
                    listOf(
                        installedVersion(modelId, "2024.06"),
                        installedVersion(modelId, "2024.09"),
                    )
                } else {
                    emptyList()
                }
            },
            knownModelIdsProvider = { setOf(ModelCatalog.QWEN_3_5_0_8B_Q4) },
        )

        val spec = registry.specFor(ModelCatalog.QWEN_3_5_0_8B_Q4)
        val variantIds = spec?.variants?.map { variant -> variant.variantId }.orEmpty()

        assertTrue(variantIds.contains("2024.06"))
        assertTrue(variantIds.contains("2024.09"))
        assertNotNull(registry.variantFor(ModelCatalog.QWEN_3_5_0_8B_Q4, "2024.09"))
    }

    @Test
    fun `unknown local imports default safely from prompt profile id`() {
        val registry = DefaultNormalizedModelCatalogRegistry(
            bundledCatalogProvider = { emptyList() },
            installedVersionsProvider = { modelId ->
                if (modelId == "local-phi") {
                    listOf(installedVersion(modelId, "local-v1", promptProfileId = PromptTemplateFamily.PHI.name))
                } else {
                    emptyList()
                }
            },
            knownModelIdsProvider = { setOf("local-phi") },
        )

        val spec = registry.specFor("local-phi")

        assertEquals(PromptTemplateFamily.PHI, spec?.promptProfile?.templateFamily)
        assertTrue(spec?.capabilities?.supports(CapabilityFlag.SHORT_TEXT) == true)
        assertEquals("local-v1", registry.variantFor("local-phi", "local-v1")?.variantId)
    }

    @Test
    fun `installed hugging face variants expose sidecar source and parameters`() {
        val sourceRef = ModelSourceRef(
            kind = ModelSourceKind.HUGGING_FACE,
            originId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            publisher = "owner",
            repository = "owner/repo",
            trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
            revision = "main",
            originUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
        )
        val parameters = ModelParameterProfile(
            architecture = "qwen3",
            quantization = "Q4_K_M",
            contextLength = 32768,
            layerCount = 28,
            embeddingSize = 1024,
        )
        val registry = DefaultNormalizedModelCatalogRegistry(
            installedVersionsProvider = { modelId ->
                if (modelId == ModelCatalog.QWEN3_0_6B_Q4_K_M) {
                    listOf(
                        installedVersion(
                            modelId = modelId,
                            version = "hf-model-aaaaaaaaaaaa",
                            displayName = "owner/repo / model.gguf",
                            sourceKind = ModelSourceKind.HUGGING_FACE,
                            sourceRef = sourceRef,
                            parameters = parameters,
                        ),
                    )
                } else {
                    emptyList()
                }
            },
            knownModelIdsProvider = { setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M) },
        )

        val variant = registry.variantFor(ModelCatalog.QWEN3_0_6B_Q4_K_M, "hf-model-aaaaaaaaaaaa")

        assertNotNull(variant)
        assertEquals("owner/repo / model.gguf", variant.displayName)
        assertEquals(ModelSourceKind.HUGGING_FACE, variant.source.kind)
        assertEquals("owner/repo", variant.source.repository)
        assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", variant.source.originUrl)
        assertEquals("qwen3", variant.parameters.architecture)
        assertEquals("Q4_K_M", variant.parameters.quantization)
        assertEquals(32768, variant.parameters.contextLength)
    }
}

private fun installedVersion(
    modelId: String,
    version: String,
    displayName: String = "$modelId $version",
    promptProfileId: String? = PromptTemplateFamily.CHATML.name,
    sourceKind: ModelSourceKind = ModelSourceKind.LOCAL_IMPORT,
    sourceRef: ModelSourceRef? = null,
    parameters: ModelParameterProfile = ModelParameterProfile(),
): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = modelId,
        version = version,
        displayName = displayName,
        absolutePath = "/tmp/$modelId-$version.gguf",
        sha256 = "b".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "signature",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 2048L,
        importedAtEpochMs = 1L,
        isActive = false,
        sourceKind = sourceKind,
        sourceRef = sourceRef,
        promptProfileId = promptProfileId,
        parameters = parameters,
    )
}
