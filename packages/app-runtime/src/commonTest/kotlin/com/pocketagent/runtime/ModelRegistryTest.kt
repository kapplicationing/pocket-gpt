package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `default registry exposes expected runtime metadata`() {
        val registry = ModelRegistry.default()

        val metadata = registry.allMetadata().associateBy { it.modelId }
        val expectedModelIds = ModelCatalog.modelDescriptors()
            .filter { it.bridgeSupported || it.startupCandidate }
            .map { it.modelId }
            .toSet()
        assertEquals(
            expectedModelIds,
            metadata.keys,
            "registry must include all bridge-supported and startup-candidate models",
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.DEV_FAST),
        )
    }

    @Test
    fun `default startup policy keeps startup candidates with minimum one ready`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.PROD)

        assertEquals(
            ModelCatalog.startupCandidateModels().toSet(),
            policy.candidateModelIds.toSet(),
        )
        assertEquals(emptyList(), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `required startup models are reflected in startup policy`() {
        val registry = ModelRegistry(
            metadataByModelId = listOf(
                RuntimeModelMetadata(
                    modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                    templateFamily = PromptTemplateFamily.CHATML,
                    tier = RuntimeModelTier.BASELINE,
                    startupRequirement = StartupRequirement.REQUIRED,
                ),
                RuntimeModelMetadata(
                    modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                    templateFamily = PromptTemplateFamily.CHATML,
                    tier = RuntimeModelTier.BASELINE,
                    startupRequirement = StartupRequirement.OPTIONAL,
                ),
            ).associateBy { metadata -> metadata.modelId },
            startupMinimumReadyCount = 1,
        )

        val policy = registry.startupPolicy(profile = ModelRuntimeProfile.PROD)

        assertEquals(listOf(ModelCatalog.QWEN_3_5_0_8B_Q4), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `dev fast startup policy falls back to startup candidates when no fast tier models`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.DEV_FAST)

        assertEquals(
            ModelCatalog.startupCandidateModels().toSet(),
            policy.candidateModelIds.toSet(),
        )
        assertEquals(emptyList(), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `default registry routing modes are sourced from catalog metadata`() {
        val metadataByModelId = ModelRegistry.default().allMetadata().associateBy { metadata -> metadata.modelId }

        ModelCatalog.modelDescriptors()
            .filter { it.explicitRoutingModes.isNotEmpty() || it.includeAutoRoutingMode }
            .forEach { descriptor ->
                val expected = ModelCatalog.routingModesForModel(descriptor.modelId)
                val actual = metadataByModelId[descriptor.modelId]?.routingModes ?: emptySet()
                assertEquals(expected, actual, "routing modes mismatch for ${descriptor.modelId}")
            }
    }

    @Test
    fun `template families are driven by catalog templateFamily`() {
        val metadata = ModelRegistry.default().allMetadata()

        metadata.forEach { entry ->
            val descriptor = ModelCatalog.descriptorFor(entry.modelId)
            if (descriptor != null) {
                assertEquals(
                    descriptor.templateFamily,
                    entry.templateFamily,
                    "template family mismatch for ${entry.modelId}",
                )
            }
        }
    }
}
