package com.pocketagent.android.runtime.modelspec

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.CapabilityFlag
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.PromptTemplateFamily
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
}

private fun installedVersion(
    modelId: String,
    version: String,
    promptProfileId: String? = PromptTemplateFamily.CHATML.name,
): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = modelId,
        version = version,
        displayName = "$modelId $version",
        absolutePath = "/tmp/$modelId-$version.gguf",
        sha256 = "b".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "signature",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 2048L,
        importedAtEpochMs = 1L,
        isActive = false,
        sourceKind = ModelSourceKind.LOCAL_IMPORT,
        promptProfileId = promptProfileId,
    )
}
