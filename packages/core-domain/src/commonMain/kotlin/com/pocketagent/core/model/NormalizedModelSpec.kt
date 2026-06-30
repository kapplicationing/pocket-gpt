package com.pocketagent.core.model

import com.pocketagent.core.RoutingMode

enum class ModelSourceKind {
    BUILT_IN,
    REMOTE_MANIFEST,
    HUGGING_FACE,
    LOCAL_IMPORT,
    REMOTE_SERVER,
    UNKNOWN,
}

enum class SourceTrustPolicy {
    UNKNOWN,
    INTEGRITY_ONLY,
    SIGNED_PROVENANCE,
    TRUSTED_PUBLISHER,
    PRIVATE_AUTH_REQUIRED,
}

enum class ModelArtifactRole {
    PRIMARY_GGUF,
    MMPROJ,
    DRAFT_MODEL,
    TOKENIZER,
    ADAPTER,
    AUXILIARY,
}

enum class PromptTemplateFamily {
    CHATML,
    LLAMA3,
    PHI,
    GEMMA,
    GEMMA4,
}

enum class SystemPromptHandling {
    NATIVE,
    PREPEND_TO_USER,
    UNSUPPORTED,
}

enum class ToolCallStrategyId {
    NONE,
    XML_TAGS,
    OPENAI_JSON,
}

enum class ThinkingStrategyId {
    NONE,
    THINK_TAGS,
    NATIVE_REASONING,
}

enum class CapabilityFlag {
    SHORT_TEXT,
    LONG_TEXT,
    REASONING,
    IMAGE,
    AUDIO,
    VIDEO,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    MULTILINGUAL,
    LONG_CONTEXT,
}

enum class RuntimeBackendFamilyTag {
    CPU,
    OPENCL,
    HEXAGON,
    VULKAN,
    REMOTE,
}

enum class NormalizedModelTier {
    BASELINE,
    FAST,
    DEBUG,
}

enum class NormalizedRuntimeProfile {
    PROD,
    DEV_FAST,
}

data class ModelSourceRef(
    val kind: ModelSourceKind,
    val originId: String? = null,
    val publisher: String? = null,
    val repository: String? = null,
    val trustPolicy: SourceTrustPolicy = SourceTrustPolicy.UNKNOWN,
    val revision: String? = null,
    val originUrl: String? = null,
)

data class ArtifactLocator(
    val fileName: String? = null,
    val absolutePath: String? = null,
    val downloadUrl: String? = null,
)

data class ArtifactSpec(
    val artifactId: String,
    val role: ModelArtifactRole,
    val required: Boolean = true,
    val locator: ArtifactLocator = ArtifactLocator(),
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    val runtimeCompatibility: String? = null,
    val provenanceIssuer: String? = null,
    val provenanceSignature: String? = null,
)

data class ArtifactBundleSpec(
    val artifacts: List<ArtifactSpec> = emptyList(),
) {
    fun primaryArtifact(): ArtifactSpec? = artifacts.firstOrNull { it.role == ModelArtifactRole.PRIMARY_GGUF }

    fun requiredArtifacts(): List<ArtifactSpec> = artifacts.filter { it.required }

    fun artifactsForRole(role: ModelArtifactRole): List<ArtifactSpec> = artifacts.filter { it.role == role }
}

data class PromptProfile(
    val profileId: String,
    val templateFamily: PromptTemplateFamily,
    val systemPromptHandling: SystemPromptHandling = SystemPromptHandling.NATIVE,
    val toolCallStrategy: ToolCallStrategyId = ToolCallStrategyId.NONE,
    val thinkingStrategy: ThinkingStrategyId = ThinkingStrategyId.NONE,
    val assistantRoleName: String = "assistant",
    val stopSequences: List<String> = emptyList(),
)

data class CapabilityProfile(
    val flags: Set<CapabilityFlag> = emptySet(),
) {
    fun supports(flag: CapabilityFlag): Boolean = flags.contains(flag)
}

data class ModelParameterProfile(
    val architecture: String? = null,
    val quantization: String? = null,
    val quantizationVersion: Int? = null,
    val contextLength: Int? = null,
    val slidingWindow: Int? = null,
    val layerCount: Int? = null,
    val embeddingSize: Int? = null,
    val headCount: Int? = null,
    val headCountKv: Int? = null,
    val vocabularySize: Int? = null,
    val imageTokenBudget: Int? = null,
)

data class RuntimeRequirementProfile(
    val bridgeSupported: Boolean = false,
    val minRamGb: Int? = null,
    val minStorageBytes: Long? = null,
    val requiredBackendFamily: RuntimeBackendFamilyTag? = null,
    val supportedBackendFamilies: Set<RuntimeBackendFamilyTag> = emptySet(),
    val runtimeCompatibilityTags: Set<String> = emptySet(),
    val preferredContextTokens: Int? = null,
    val preferredBatchSize: Int? = null,
    val preferredThreadCount: Int? = null,
)

data class ProductPolicyProfile(
    val tier: NormalizedModelTier = NormalizedModelTier.BASELINE,
    val startupCandidate: Boolean = false,
    val startupRequired: Boolean = false,
    val defaultForGetReadyProfiles: Set<NormalizedRuntimeProfile> = emptySet(),
    val autoRoutingEligible: Boolean = false,
    val routingModes: Set<RoutingMode> = emptySet(),
    val qualityRank: Int = 0,
    val speedRank: Int = 0,
    val fallbackPriority: Int = 0,
)

data class ModelVariantSpec(
    val variantId: String,
    val displayName: String = variantId,
    val artifactBundle: ArtifactBundleSpec = ArtifactBundleSpec(),
    val parameters: ModelParameterProfile = ModelParameterProfile(),
    val source: ModelSourceRef = ModelSourceRef(kind = ModelSourceKind.UNKNOWN),
)

data class NormalizedModelSpec(
    val modelId: String,
    val displayName: String,
    val family: String? = null,
    val promptProfile: PromptProfile,
    val capabilities: CapabilityProfile = CapabilityProfile(),
    val runtimeRequirements: RuntimeRequirementProfile = RuntimeRequirementProfile(),
    val productPolicy: ProductPolicyProfile = ProductPolicyProfile(),
    val variants: List<ModelVariantSpec> = emptyList(),
    val source: ModelSourceRef = ModelSourceRef(kind = ModelSourceKind.UNKNOWN),
) {
    fun variant(version: String?): ModelVariantSpec? {
        val normalizedVersion = version?.trim().orEmpty()
        if (normalizedVersion.isEmpty()) {
            return variants.firstOrNull()
        }
        return variants.firstOrNull { candidate -> candidate.variantId == normalizedVersion }
    }
}
