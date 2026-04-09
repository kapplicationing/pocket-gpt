package com.pocketagent.inference

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.model.ArtifactBundleSpec
import com.pocketagent.core.model.ArtifactLocator
import com.pocketagent.core.model.ArtifactSpec
import com.pocketagent.core.model.CapabilityFlag
import com.pocketagent.core.model.CapabilityProfile
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.ModelVariantSpec
import com.pocketagent.core.model.NormalizedModelSpec
import com.pocketagent.core.model.NormalizedModelTier
import com.pocketagent.core.model.NormalizedRuntimeProfile
import com.pocketagent.core.model.ProductPolicyProfile
import com.pocketagent.core.model.PromptProfileRegistry
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.core.model.RuntimeBackendFamilyTag
import com.pocketagent.core.model.RuntimeRequirementProfile
import com.pocketagent.core.model.ThinkingStrategyId
import com.pocketagent.core.model.ToolCallStrategyId

enum class ModelTier {
    BASELINE,
    FAST,
    DEBUG,
}

enum class ModelFamily {
    SMOKE_ECHO,
    QWEN,
    SMOLLM3,
    PHI,
    GEMMA,
}

enum class ModelCapability {
    SHORT_TEXT,
    LONG_TEXT,
    REASONING,
    IMAGE,
}

enum class ModelRuntimeProfile {
    PROD,
    DEV_FAST,
}

data class ModelDescriptor(
    val modelId: String,
    val tier: ModelTier,
    val family: ModelFamily,
    val bridgeSupported: Boolean,
    val autoRoutingEnabled: Boolean,
    val speculativeDraftTargetFamilies: Set<ModelFamily> = setOf(family),
    val capabilities: Set<ModelCapability>,
    val minRamGb: Int,
    val qualityRank: Int,
    val speedRank: Int,
    val fallbackPriority: Int,
    val startupCandidate: Boolean,
    val startupRequired: Boolean,
    val defaultGetReadyProfiles: Set<ModelRuntimeProfile>,
    val envKeyToken: String,
    val templateFamily: PromptTemplateFamily = PromptTemplateFamily.CHATML,
    val interactionFeatures: Set<String> = emptySet(),
    val includeAutoRoutingMode: Boolean = false,
    val explicitRoutingModes: Set<RoutingMode> = emptySet(),
    val mmProjFileName: String? = null,
)

data class ModelLoadValidation(
    val accepted: Boolean,
    val code: String? = null,
    val detail: String? = null,
    val normalizedModelPath: String? = null,
)

object ModelCatalog : ModelSpecProvider {
    const val SMOKE_ECHO_120M = "smoke-echo-120m-q4"
    const val QWEN_3_5_0_8B_Q4 = "qwen3.5-0.8b-q4"
    const val QWEN_3_5_2B_Q4 = "qwen3.5-2b-q4"
    const val SMOLLM3_3B_Q4_K_M = "smollm3-3b-q4_k_m"
    const val SMOLLM3_3B_UD_IQ2_XXS = "smollm3-3b-ud-iq2_xxs"
    const val QWEN3_0_6B_Q4_K_M = "qwen3-0.6b-q4_k_m"
    const val PHI_4_MINI_Q4_K_M = "phi-4-mini-instruct-q4_k_m"
    const val GEMMA_4_E2B_Q4_K_M = "gemma-4-e2b-it-q4_k_m"
    const val BONSAI_1_7B_Q1_0_G128 = "bonsai-1.7b-q1_0_g128"
    const val BONSAI_4B_Q1_0_G128 = "bonsai-4b-q1_0_g128"
    const val BONSAI_8B_Q1_0_G128 = "bonsai-8b-q1_0_g128"

    private val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            modelId = SMOKE_ECHO_120M,
            tier = ModelTier.DEBUG,
            family = ModelFamily.SMOKE_ECHO,
            bridgeSupported = false,
            autoRoutingEnabled = false,
            capabilities = setOf(ModelCapability.SHORT_TEXT),
            minRamGb = 1,
            qualityRank = 0,
            speedRank = 5,
            fallbackPriority = 100,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOKE_ECHO_120M_Q4",
        ),
        ModelDescriptor(
            modelId = QWEN_3_5_0_8B_Q4,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
                ModelCapability.IMAGE,
            ),
            minRamGb = 6,
            qualityRank = 2,
            speedRank = 2,
            fallbackPriority = 10,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = setOf(ModelRuntimeProfile.PROD),
            envKeyToken = "QWEN_3_5_0_8B_Q4",
            interactionFeatures = setOf("TOOL_CALL_XML", "THINKING_TAGS"),
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.QWEN_0_8B),
            mmProjFileName = "qwen3.5-0.8b-mmproj-q8_0.gguf",
        ),
        ModelDescriptor(
            modelId = QWEN3_0_6B_Q4_K_M,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 4,
            qualityRank = 1,
            speedRank = 3,
            fallbackPriority = 12,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "QWEN3_0_6B_Q4_K_M",
            interactionFeatures = setOf("TOOL_CALL_XML", "THINKING_TAGS"),
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.QWEN3_0_6B),
        ),
        ModelDescriptor(
            modelId = QWEN_3_5_2B_Q4,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
                ModelCapability.IMAGE,
            ),
            minRamGb = 12,
            qualityRank = 3,
            speedRank = 1,
            fallbackPriority = 20,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "QWEN_3_5_2B_Q4",
            interactionFeatures = setOf("TOOL_CALL_XML", "THINKING_TAGS"),
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.QWEN_2B),
            mmProjFileName = "qwen3.5-2b-mmproj-q8_0.gguf",
        ),
        ModelDescriptor(
            modelId = SMOLLM3_3B_Q4_K_M,
            tier = ModelTier.BASELINE,
            family = ModelFamily.SMOLLM3,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 6,
            qualityRank = 4,
            speedRank = 0,
            fallbackPriority = 15,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOLLM3_3B_Q4_K_M",
            interactionFeatures = setOf("TOOL_CALL_XML", "THINKING_TAGS"),
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.SMOLLM3_3B),
        ),
        ModelDescriptor(
            modelId = SMOLLM3_3B_UD_IQ2_XXS,
            tier = ModelTier.DEBUG,
            family = ModelFamily.SMOLLM3,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(ModelCapability.SHORT_TEXT),
            minRamGb = 4,
            qualityRank = 0,
            speedRank = 3,
            fallbackPriority = 99,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOLLM3_3B_UD_IQ2_XXS",
        ),
        ModelDescriptor(
            modelId = PHI_4_MINI_Q4_K_M,
            tier = ModelTier.BASELINE,
            family = ModelFamily.PHI,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 8,
            qualityRank = 5,
            speedRank = -1,
            fallbackPriority = 18,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "PHI_4_MINI_INSTRUCT_Q4_K_M",
            templateFamily = PromptTemplateFamily.PHI,
            interactionFeatures = setOf("THINKING_TAGS"),
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.PHI_4_MINI),
        ),
        ModelDescriptor(
            modelId = GEMMA_4_E2B_Q4_K_M,
            tier = ModelTier.BASELINE,
            family = ModelFamily.GEMMA,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 6,
            qualityRank = 50,
            speedRank = 60,
            fallbackPriority = 22,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "GEMMA_4_E2B_IT_Q4_K_M",
            templateFamily = PromptTemplateFamily.GEMMA4,
            includeAutoRoutingMode = false,
            explicitRoutingModes = setOf(RoutingMode.GEMMA_4_E2B),
        ),
        ModelDescriptor(
            modelId = BONSAI_1_7B_Q1_0_G128,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 4,
            qualityRank = 5,
            speedRank = 0,
            fallbackPriority = 28,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "BONSAI_1_7B_Q1_0_G128",
            interactionFeatures = setOf("THINKING_TAGS"),
            includeAutoRoutingMode = false,
        ),
        ModelDescriptor(
            modelId = BONSAI_4B_Q1_0_G128,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 6,
            qualityRank = 6,
            speedRank = -1,
            fallbackPriority = 29,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "BONSAI_4B_Q1_0_G128",
            interactionFeatures = setOf("THINKING_TAGS"),
            includeAutoRoutingMode = false,
        ),
        ModelDescriptor(
            modelId = BONSAI_8B_Q1_0_G128,
            tier = ModelTier.BASELINE,
            family = ModelFamily.QWEN,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 8,
            qualityRank = 7,
            speedRank = -2,
            fallbackPriority = 30,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "BONSAI_8B_Q1_0_G128",
            interactionFeatures = setOf("THINKING_TAGS"),
            includeAutoRoutingMode = false,
            explicitRoutingModes = setOf(RoutingMode.BONSAI_8B),
        ),
    )

    private val descriptorsByModelId: Map<String, ModelDescriptor> = descriptors.associateBy { descriptor -> descriptor.modelId }
    private val descriptorsByEnvKeyToken: Map<String, ModelDescriptor> =
        descriptors.associateBy { descriptor -> descriptor.envKeyToken }
    private val normalizedSpecsByModelId: Map<String, NormalizedModelSpec> =
        descriptors.associate { descriptor -> descriptor.modelId to descriptor.toNormalizedModelSpec() }
    private val modelIdByRoutingMode: Map<RoutingMode, String> = buildMap {
        descriptors.forEach { descriptor ->
            descriptor.explicitRoutingModes.forEach { routingMode ->
                val previous = put(routingMode, descriptor.modelId)
                check(previous == null) {
                    "Duplicate explicit routing mode binding: $routingMode -> $previous and ${descriptor.modelId}"
                }
            }
        }
    }

    fun baselineModels(): List<String> = listOf(
        SMOKE_ECHO_120M,
        QWEN_3_5_0_8B_Q4,
        QWEN_3_5_2B_Q4,
    )

    fun fastTierModels(): List<String> = emptyList()

    fun modelDescriptors(): List<ModelDescriptor> = descriptors

    override fun allSpecs(): List<NormalizedModelSpec> = normalizedSpecsByModelId.values.toList()

    fun normalizedSpecs(): List<NormalizedModelSpec> = allSpecs()

    override fun specFor(modelId: String): NormalizedModelSpec? = normalizedSpecsByModelId[modelId]

    override fun variantFor(modelId: String, version: String?): ModelVariantSpec? = specFor(modelId)?.variant(version)

    fun normalizedSpecFor(modelId: String): NormalizedModelSpec? = specFor(modelId)

    fun descriptorFor(modelId: String): ModelDescriptor? = descriptorsByModelId[modelId]

    fun descriptorForEnvKeyToken(envKeyToken: String): ModelDescriptor? = descriptorsByEnvKeyToken[envKeyToken]

    fun modelIdForRoutingMode(mode: RoutingMode): String? = modelIdByRoutingMode[mode]

    fun routingModesForModel(modelId: String): Set<RoutingMode> {
        val descriptor = descriptorFor(modelId) ?: return emptySet()
        return buildSet {
            if (descriptor.includeAutoRoutingMode) {
                add(RoutingMode.AUTO)
            }
            addAll(descriptor.explicitRoutingModes)
        }
    }

    fun bridgeSupportedModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.bridgeSupported }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupCandidateModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.startupCandidate }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupRequiredModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.startupRequired }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupMinimumReadyCount(): Int = 1

    fun defaultGetReadyModelId(profile: ModelRuntimeProfile): String? {
        val directMatch = descriptors.firstOrNull { descriptor ->
            descriptor.defaultGetReadyProfiles.contains(profile)
        }?.modelId
        if (directMatch != null) {
            return directMatch
        }
        return descriptors.firstOrNull { descriptor ->
            descriptor.defaultGetReadyProfiles.contains(ModelRuntimeProfile.PROD)
        }?.modelId
    }

    fun preferredRuntimeOrderModelIds(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.bridgeSupported }
            .sortedBy { descriptor -> descriptor.fallbackPriority }
            .map { descriptor -> descriptor.modelId }
    }

    fun supportsTask(modelId: String, taskType: String): Boolean {
        val descriptor = descriptorFor(modelId) ?: return false
        val capability = capabilityForTask(taskType)
        return descriptor.capabilities.contains(capability)
    }

    fun isSpeculativeDraftCompatible(targetModelId: String, draftModelId: String): Boolean {
        val targetDescriptor = descriptorFor(targetModelId) ?: return false
        val draftDescriptor = descriptorFor(draftModelId) ?: return false
        return draftDescriptor.speculativeDraftTargetFamilies.contains(targetDescriptor.family)
    }

    fun autoRoutingCandidates(taskType: String): List<ModelDescriptor> {
        val capability = capabilityForTask(taskType)
        return descriptors
            .filter { descriptor -> descriptor.autoRoutingEnabled && descriptor.capabilities.contains(capability) }
    }

    fun validateBridgeLoad(
        modelId: String,
        modelPath: String?,
        supportedModels: Set<String> = bridgeSupportedModels().toSet(),
    ): ModelLoadValidation {
        if (!supportedModels.contains(modelId)) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_UNSUPPORTED",
                detail = "modelId=$modelId",
            )
        }
        val normalizedPath = modelPath?.trim().orEmpty()
        if (normalizedPath.isBlank()) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_PATH_MISSING",
                detail = "modelId=$modelId",
            )
        }
        if (!normalizedPath.endsWith(".gguf", ignoreCase = true)) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_PATH_INVALID",
                detail = "modelId=$modelId|path=$normalizedPath",
            )
        }
        return ModelLoadValidation(
            accepted = true,
            normalizedModelPath = normalizedPath,
        )
    }

    fun mmProjFileNameFor(modelId: String): String? = descriptorFor(modelId)?.mmProjFileName

    fun isVisionCapable(modelId: String): Boolean {
        val descriptor = descriptorFor(modelId) ?: return false
        return descriptor.capabilities.contains(ModelCapability.IMAGE) && descriptor.mmProjFileName != null
    }

    private fun capabilityForTask(taskType: String): ModelCapability {
        return when (taskType.trim().lowercase()) {
            "long_text" -> ModelCapability.LONG_TEXT
            "reasoning" -> ModelCapability.REASONING
            "image" -> ModelCapability.IMAGE
            else -> ModelCapability.SHORT_TEXT
        }
    }
}

private fun ModelDescriptor.toNormalizedModelSpec(): NormalizedModelSpec {
    val descriptorModelId = this.modelId
    val promptProfile = PromptProfileRegistry.promptProfileFor(
        family = templateFamily,
        toolCallStrategy = interactionFeatures.toToolCallStrategy(),
        thinkingStrategy = interactionFeatures.toThinkingStrategy(),
    )
    val primaryVariantId = descriptorModelId.substringAfterLast('-')
        .takeIf { it.contains('_') || it.startsWith("q") }
        ?: descriptorModelId
    val primaryArtifact = ArtifactSpec(
        artifactId = "$descriptorModelId::$primaryVariantId::primary",
        role = ModelArtifactRole.PRIMARY_GGUF,
        locator = ArtifactLocator(fileName = "$descriptorModelId.gguf"),
    )
    val variantArtifacts = buildList {
        add(primaryArtifact)
        mmProjFileName?.let { mmproj ->
            add(
                ArtifactSpec(
                    artifactId = "$descriptorModelId::$primaryVariantId::mmproj",
                    role = ModelArtifactRole.MMPROJ,
                    required = capabilities.contains(ModelCapability.IMAGE),
                    locator = ArtifactLocator(fileName = mmproj),
                ),
            )
        }
    }
    return NormalizedModelSpec(
        modelId = descriptorModelId,
        displayName = descriptorModelId,
        family = family.name,
        promptProfile = promptProfile,
        capabilities = CapabilityProfile(flags = capabilities.toCapabilityFlags(interactionFeatures)),
        runtimeRequirements = RuntimeRequirementProfile(
            bridgeSupported = bridgeSupported,
            minRamGb = minRamGb,
            requiredBackendFamily = when {
                descriptorModelId.contains("q1_0_g128") -> RuntimeBackendFamilyTag.OPENCL
                else -> null
            },
            supportedBackendFamilies = when {
                bridgeSupported -> setOf(RuntimeBackendFamilyTag.CPU, RuntimeBackendFamilyTag.OPENCL)
                else -> setOf(RuntimeBackendFamilyTag.CPU)
            },
        ),
        productPolicy = ProductPolicyProfile(
            tier = tier.toNormalizedTier(),
            startupCandidate = startupCandidate,
            startupRequired = startupRequired,
            defaultForGetReadyProfiles = defaultGetReadyProfiles.mapTo(linkedSetOf()) { profile -> profile.toNormalizedProfile() },
            autoRoutingEligible = autoRoutingEnabled,
            routingModes = ModelCatalog.routingModesForModel(descriptorModelId),
            qualityRank = qualityRank,
            speedRank = speedRank,
            fallbackPriority = fallbackPriority,
        ),
        variants = listOf(
            ModelVariantSpec(
                variantId = primaryVariantId,
                artifactBundle = ArtifactBundleSpec(artifacts = variantArtifacts),
                source = ModelSourceRef(
                    kind = ModelSourceKind.BUILT_IN,
                    originId = envKeyToken,
                ),
            ),
        ),
        source = ModelSourceRef(
            kind = ModelSourceKind.BUILT_IN,
            originId = envKeyToken,
        ),
    )
}

private fun Set<ModelCapability>.toCapabilityFlags(interactionFeatures: Set<String>): Set<CapabilityFlag> {
    return buildSet {
        this@toCapabilityFlags.forEach { capability ->
            when (capability) {
                ModelCapability.SHORT_TEXT -> add(CapabilityFlag.SHORT_TEXT)
                ModelCapability.LONG_TEXT -> add(CapabilityFlag.LONG_TEXT)
                ModelCapability.REASONING -> add(CapabilityFlag.REASONING)
                ModelCapability.IMAGE -> add(CapabilityFlag.IMAGE)
            }
        }
        if (interactionFeatures.contains("TOOL_CALL_XML")) {
            add(CapabilityFlag.TOOL_CALLING)
            add(CapabilityFlag.STRUCTURED_OUTPUT)
        }
        if (this@toCapabilityFlags.contains(ModelCapability.LONG_TEXT)) {
            add(CapabilityFlag.LONG_CONTEXT)
        }
    }
}

private fun Set<String>.toToolCallStrategy(): ToolCallStrategyId {
    return if (contains("TOOL_CALL_XML")) {
        ToolCallStrategyId.XML_TAGS
    } else {
        ToolCallStrategyId.NONE
    }
}

private fun Set<String>.toThinkingStrategy(
): ThinkingStrategyId {
    return if (contains("THINKING_TAGS")) ThinkingStrategyId.THINK_TAGS else ThinkingStrategyId.NONE
}

private fun ModelTier.toNormalizedTier(): NormalizedModelTier {
    return when (this) {
        ModelTier.BASELINE -> NormalizedModelTier.BASELINE
        ModelTier.FAST -> NormalizedModelTier.FAST
        ModelTier.DEBUG -> NormalizedModelTier.DEBUG
    }
}

private fun ModelRuntimeProfile.toNormalizedProfile(): NormalizedRuntimeProfile {
    return when (this) {
        ModelRuntimeProfile.PROD -> NormalizedRuntimeProfile.PROD
        ModelRuntimeProfile.DEV_FAST -> NormalizedRuntimeProfile.DEV_FAST
    }
}
