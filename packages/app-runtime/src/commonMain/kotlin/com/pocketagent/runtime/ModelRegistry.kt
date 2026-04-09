package com.pocketagent.runtime

import com.pocketagent.core.model.NormalizedModelTier
import com.pocketagent.core.model.NormalizedRuntimeProfile
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile

enum class RuntimeModelTier {
    BASELINE,
    FAST,
    DEBUG,
}

enum class StartupRequirement {
    NONE,
    OPTIONAL,
    REQUIRED,
}

data class RuntimeModelMetadata(
    val modelId: String,
    val templateFamily: PromptTemplateFamily,
    val tier: RuntimeModelTier,
    val startupRequirement: StartupRequirement = StartupRequirement.NONE,
    val defaultForGetReadyProfiles: Set<ModelRuntimeProfile> = emptySet(),
    val routingModes: Set<RoutingMode> = emptySet(),
)

data class StartupModelPolicy(
    val candidateModelIds: List<String>,
    val requiredModelIds: List<String>,
    val minimumReadyCount: Int,
)

class ModelRegistry(
    private val metadataByModelId: Map<String, RuntimeModelMetadata>,
    private val startupMinimumReadyCount: Int = 1,
) {
    fun metadataForModel(modelId: String): RuntimeModelMetadata? = metadataByModelId[modelId]

    fun allMetadata(): List<RuntimeModelMetadata> = metadataByModelId.values.toList()

    fun templateFamiliesByModelId(): Map<String, PromptTemplateFamily> {
        return metadataByModelId.mapValues { (_, metadata) -> metadata.templateFamily }
    }

    fun startupPolicy(
        profile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
    ): StartupModelPolicy {
        val startupModels = startupMetadataForProfile(profile)
        if (startupModels.isEmpty()) {
            return StartupModelPolicy(
                candidateModelIds = emptyList(),
                requiredModelIds = emptyList(),
                minimumReadyCount = 0,
            )
        }
        val candidateModelIds = startupModels.map { metadata -> metadata.modelId }
        val requiredModelIds = startupModels
            .filter { metadata -> metadata.startupRequirement == StartupRequirement.REQUIRED }
            .map { metadata -> metadata.modelId }
        val minimumReadyCount = startupMinimumReadyCount
            .coerceAtLeast(1)
            .coerceAtMost(candidateModelIds.size)
        return StartupModelPolicy(
            candidateModelIds = candidateModelIds,
            requiredModelIds = requiredModelIds,
            minimumReadyCount = minimumReadyCount,
        )
    }

    private fun startupMetadataForProfile(profile: ModelRuntimeProfile): List<RuntimeModelMetadata> {
        if (profile == ModelRuntimeProfile.DEV_FAST) {
            val fastTierMetadata = allMetadata()
                .filter { metadata -> metadata.tier == RuntimeModelTier.FAST }
            if (fastTierMetadata.isNotEmpty()) {
                return fastTierMetadata
            }
        }
        return allMetadata()
            .filter { metadata -> metadata.startupRequirement != StartupRequirement.NONE }
    }

    fun defaultGetReadyModelId(
        profile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
    ): String? {
        val directMatch = allMetadata().firstOrNull { metadata ->
            metadata.defaultForGetReadyProfiles.contains(profile)
        }?.modelId
        if (directMatch != null) {
            return directMatch
        }
        return allMetadata().firstOrNull { metadata ->
            metadata.defaultForGetReadyProfiles.contains(ModelRuntimeProfile.PROD)
        }?.modelId
    }

    companion object {
        fun default(specProvider: ModelSpecProvider = ModelCatalog): ModelRegistry {
            return ModelRegistry(
                metadataByModelId = defaultMetadata(specProvider).associateBy { metadata -> metadata.modelId },
                startupMinimumReadyCount = 1,
            )
        }

        fun defaultMetadata(specProvider: ModelSpecProvider = ModelCatalog): List<RuntimeModelMetadata> {
            return specProvider.allSpecs()
                .filter { spec ->
                    spec.runtimeRequirements.bridgeSupported || spec.productPolicy.startupCandidate
                }
                .map { spec ->
                    RuntimeModelMetadata(
                        modelId = spec.modelId,
                        templateFamily = spec.promptProfile.templateFamily,
                        tier = spec.productPolicy.tier.toRuntimeTier(),
                        startupRequirement = when {
                            spec.productPolicy.startupRequired -> StartupRequirement.REQUIRED
                            spec.productPolicy.startupCandidate -> StartupRequirement.OPTIONAL
                            else -> StartupRequirement.NONE
                        },
                        defaultForGetReadyProfiles = spec.productPolicy.defaultForGetReadyProfiles
                            .mapTo(linkedSetOf()) { profile -> profile.toRuntimeProfile() },
                        routingModes = spec.productPolicy.routingModes,
                    )
                }
        }
    }
}

private fun NormalizedModelTier.toRuntimeTier(): RuntimeModelTier {
    return when (this) {
        NormalizedModelTier.BASELINE -> RuntimeModelTier.BASELINE
        NormalizedModelTier.FAST -> RuntimeModelTier.FAST
        NormalizedModelTier.DEBUG -> RuntimeModelTier.DEBUG
    }
}

private fun NormalizedRuntimeProfile.toRuntimeProfile(): ModelRuntimeProfile {
    return when (this) {
        NormalizedRuntimeProfile.PROD -> ModelRuntimeProfile.PROD
        NormalizedRuntimeProfile.DEV_FAST -> ModelRuntimeProfile.DEV_FAST
    }
}
