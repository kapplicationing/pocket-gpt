package com.pocketagent.android.runtime.modelspec

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelDistributionArtifact
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.ArtifactBundleSpec
import com.pocketagent.core.model.ArtifactLocator
import com.pocketagent.core.model.ArtifactSpec
import com.pocketagent.core.model.CapabilityFlag
import com.pocketagent.core.model.CapabilityProfile
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.ModelParameterProfile
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.ModelVariantSpec
import com.pocketagent.core.model.NormalizedModelSpec
import com.pocketagent.core.model.PromptProfileRegistry
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.core.model.RuntimeRequirementProfile
import com.pocketagent.core.model.SourceTrustPolicy
import com.pocketagent.inference.ModelCatalog

interface NormalizedModelCatalogRegistry : ModelSpecProvider {
    override fun allSpecs(): List<NormalizedModelSpec>

    /** Drop cached [allSpecs] after provisioning or manifest mutations (tests may call directly). */
    fun invalidateModelSpecCache() = Unit
}

class DefaultNormalizedModelCatalogRegistry(
    private val bundledCatalogProvider: () -> List<NormalizedModelSpec> = { ModelCatalog.normalizedSpecs() },
    private val manifestProvider: () -> ModelDistributionManifest? = { null },
    private val installedVersionsProvider: (String) -> List<ModelVersionDescriptor> = { emptyList() },
    private val knownModelIdsProvider: () -> Set<String> = { emptySet() },
) : NormalizedModelCatalogRegistry {
    private val cacheLock = Any()
    @Volatile
    private var cachedKey: String? = null
    @Volatile
    private var cachedSpecs: List<NormalizedModelSpec>? = null

    override fun invalidateModelSpecCache() {
        synchronized(cacheLock) {
            cachedKey = null
            cachedSpecs = null
        }
    }

    override fun allSpecs(): List<NormalizedModelSpec> {
        val key = computeCacheKey()
        synchronized(cacheLock) {
            if (key == cachedKey && cachedSpecs != null) {
                return cachedSpecs!!
            }
        }
        val bundled = bundledCatalogProvider().associateBy { spec -> spec.modelId }.toMutableMap()
        ManifestSourceAdapter().adapt(manifestProvider()?.models.orEmpty()).forEach { incoming ->
            bundled[incoming.modelId] = bundled[incoming.modelId]?.mergeWith(incoming) ?: incoming
        }
        val merged = bundled.toMutableMap()
        val knownIds = (merged.keys + knownModelIdsProvider()).toMutableSet()
        knownIds.forEach { modelId ->
            val imported = LocalImportSourceAdapter().adapt(modelId, installedVersionsProvider(modelId))
            if (imported != null) {
                merged[modelId] = merged[modelId]?.mergeWith(imported) ?: imported
            }
        }
        val built = merged.values.sortedBy { spec -> spec.productPolicy.fallbackPriority }
        synchronized(cacheLock) {
            cachedKey = key
            cachedSpecs = built
        }
        return built
    }

    private fun computeCacheKey(): String {
        val manifest = manifestProvider()
        val manifestKey = manifest?.models.orEmpty()
            .sortedBy { it.modelId }
            .joinToString("|") { model ->
                model.modelId + ":" + model.versions.joinToString(",") { v ->
                    v.version + ":" + v.expectedSha256
                }
            }
        val knownSorted = knownModelIdsProvider().sorted()
        val installedKey = knownSorted.joinToString(";") { modelId ->
            modelId + "=" + installedVersionsProvider(modelId)
                .sortedBy { it.version }
                .joinToString(",") { d ->
                    "${d.version}:${d.isActive}:${d.absolutePath}:${d.sha256}"
                }
        }
        return "$manifestKey###$installedKey"
    }
}

data class HuggingFaceModelRecord(
    val modelId: String,
    val displayName: String,
    val repository: String,
    val author: String? = null,
    val architecture: String? = null,
    val contextLength: Int? = null,
    val chatTemplateName: String? = null,
    val files: List<HuggingFaceFileRecord>,
)

data class HuggingFaceFileRecord(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val runtimeCompatibility: String? = null,
    val role: ModelArtifactRole = ModelArtifactRole.PRIMARY_GGUF,
)

class ManifestSourceAdapter {
    fun adapt(models: List<com.pocketagent.android.runtime.modelmanager.ModelDistributionModel>): List<NormalizedModelSpec> {
        return models.map { model ->
            val promptFamily = promptFamilyFromProfileId(
                model.versions.firstNotNullOfOrNull { version -> version.promptProfileId },
            )
            NormalizedModelSpec(
                modelId = model.modelId,
                displayName = model.displayName,
                promptProfile = PromptProfileRegistry.promptProfileFor(
                    family = promptFamily,
                ),
                runtimeRequirements = RuntimeRequirementProfile(
                    runtimeCompatibilityTags = model.versions.flatMapTo(linkedSetOf()) { version ->
                        version.artifacts.mapNotNull { artifact -> artifact.runtimeCompatibility }
                    },
                ),
                variants = model.versions.map { version ->
                    ModelVariantSpec(
                        variantId = version.version,
                        artifactBundle = ArtifactBundleSpec(
                            artifacts = version.artifacts.map { artifact -> artifact.toCoreArtifact() },
                        ),
                        source = ModelSourceRef(
                            kind = version.sourceKind,
                            originId = model.modelId,
                            trustPolicy = when (version.verificationPolicy.enforcesProvenance) {
                                true -> SourceTrustPolicy.SIGNED_PROVENANCE
                                false -> SourceTrustPolicy.INTEGRITY_ONLY
                            },
                        ),
                    )
                },
                source = ModelSourceRef(
                    kind = ModelSourceKind.REMOTE_MANIFEST,
                    originId = model.modelId,
                ),
            )
        }
    }
}

class HuggingFaceSourceAdapter {
    fun adapt(models: List<HuggingFaceModelRecord>): List<NormalizedModelSpec> {
        return models.map { model ->
            val promptFamily = when (model.chatTemplateName?.trim()?.lowercase()) {
                "llama3" -> PromptTemplateFamily.LLAMA3
                "phi", "phi3", "phi4" -> PromptTemplateFamily.PHI
                "gemma", "gemma-it" -> PromptTemplateFamily.GEMMA
                else -> PromptTemplateFamily.CHATML
            }
            NormalizedModelSpec(
                modelId = model.modelId,
                displayName = model.displayName,
                family = model.architecture,
                promptProfile = PromptProfileRegistry.promptProfileFor(
                    family = promptFamily,
                ),
                capabilities = CapabilityProfile(
                    flags = setOf(CapabilityFlag.SHORT_TEXT) +
                        listOfNotNull(
                            model.contextLength?.takeIf { it >= 8192 }?.let { CapabilityFlag.LONG_CONTEXT },
                        ),
                ),
                variants = listOf(
                    ModelVariantSpec(
                        variantId = model.files.firstOrNull { file -> file.role == ModelArtifactRole.PRIMARY_GGUF }
                            ?.fileName
                            ?.substringBeforeLast('.')
                            ?: model.modelId,
                        artifactBundle = ArtifactBundleSpec(
                            artifacts = model.files.mapIndexed { index, file ->
                                ArtifactSpec(
                                    artifactId = "${model.modelId}::hf::$index",
                                    role = file.role,
                                    locator = ArtifactLocator(
                                        fileName = file.fileName,
                                        downloadUrl = file.downloadUrl,
                                    ),
                                    sha256 = file.sha256,
                                    fileSizeBytes = file.sizeBytes,
                                    runtimeCompatibility = file.runtimeCompatibility,
                                )
                            },
                        ),
                        parameters = ModelParameterProfile(
                            architecture = model.architecture,
                            contextLength = model.contextLength,
                        ),
                        source = ModelSourceRef(
                            kind = ModelSourceKind.HUGGING_FACE,
                            originId = model.modelId,
                            publisher = model.author,
                            repository = model.repository,
                            trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
                        ),
                    ),
                ),
                source = ModelSourceRef(
                    kind = ModelSourceKind.HUGGING_FACE,
                    originId = model.modelId,
                    publisher = model.author,
                    repository = model.repository,
                    trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
                ),
            )
        }
    }
}

class LocalImportSourceAdapter {
    fun adapt(modelId: String, installed: List<ModelVersionDescriptor>): NormalizedModelSpec? {
        if (installed.isEmpty()) {
            return null
        }
        val builtIn = ModelCatalog.normalizedSpecFor(modelId)
        return NormalizedModelSpec(
            modelId = modelId,
            displayName = builtIn?.displayName ?: installed.first().displayName,
            family = builtIn?.family,
            promptProfile = builtIn?.promptProfile ?: PromptProfileRegistry.promptProfileFor(
                family = promptFamilyFromProfileId(installed.first().promptProfileId),
            ),
            capabilities = builtIn?.capabilities ?: CapabilityProfile(flags = setOf(CapabilityFlag.SHORT_TEXT)),
            runtimeRequirements = builtIn?.runtimeRequirements ?: RuntimeRequirementProfile(
                runtimeCompatibilityTags = installed.mapTo(linkedSetOf()) { descriptor -> descriptor.runtimeCompatibility },
            ),
            productPolicy = builtIn?.productPolicy ?: com.pocketagent.core.model.ProductPolicyProfile(),
            variants = installed.map { version ->
                ModelVariantSpec(
                    variantId = version.version,
                    displayName = version.displayName,
                    artifactBundle = ArtifactBundleSpec(
                        artifacts = version.artifacts.map { artifact -> artifact.toCoreArtifact() },
                    ),
                    source = ModelSourceRef(
                        kind = version.sourceKind,
                        originId = version.version,
                    ),
                )
            },
            source = builtIn?.source ?: ModelSourceRef(
                kind = ModelSourceKind.LOCAL_IMPORT,
                originId = modelId,
            ),
        )
    }
}

private fun NormalizedModelSpec.mergeWith(other: NormalizedModelSpec): NormalizedModelSpec {
    val mergedVariants = (variants + other.variants)
        .associateBy { variant -> variant.variantId }
        .values
        .toList()
    return copy(
        displayName = if (displayName == modelId && other.displayName.isNotBlank()) other.displayName else displayName,
        family = family ?: other.family,
        promptProfile = if (promptProfile.profileId == "manifest-unknown") other.promptProfile else promptProfile,
        capabilities = if (capabilities.flags.isEmpty()) other.capabilities else capabilities,
        runtimeRequirements = runtimeRequirements.mergeWith(other.runtimeRequirements),
        productPolicy = if (productPolicy.fallbackPriority == 0) other.productPolicy else productPolicy,
        variants = mergedVariants,
    )
}

private fun RuntimeRequirementProfile.mergeWith(other: RuntimeRequirementProfile): RuntimeRequirementProfile {
    return copy(
        bridgeSupported = bridgeSupported || other.bridgeSupported,
        minRamGb = minRamGb ?: other.minRamGb,
        minStorageBytes = minStorageBytes ?: other.minStorageBytes,
        requiredBackendFamily = requiredBackendFamily ?: other.requiredBackendFamily,
        supportedBackendFamilies = (supportedBackendFamilies + other.supportedBackendFamilies).toSet(),
        runtimeCompatibilityTags = (runtimeCompatibilityTags + other.runtimeCompatibilityTags).toSet(),
        preferredContextTokens = preferredContextTokens ?: other.preferredContextTokens,
        preferredBatchSize = preferredBatchSize ?: other.preferredBatchSize,
        preferredThreadCount = preferredThreadCount ?: other.preferredThreadCount,
    )
}

private fun ModelDistributionArtifact.toCoreArtifact(): ArtifactSpec {
    return ArtifactSpec(
        artifactId = artifactId,
        role = role,
        required = required,
        locator = ArtifactLocator(
            fileName = fileName,
            downloadUrl = downloadUrl,
        ),
        fileSizeBytes = fileSizeBytes,
        sha256 = expectedSha256,
        runtimeCompatibility = runtimeCompatibility,
        provenanceIssuer = provenanceIssuer,
        provenanceSignature = provenanceSignature,
    )
}

private fun InstalledArtifactDescriptor.toCoreArtifact(): ArtifactSpec {
    return ArtifactSpec(
        artifactId = artifactId,
        role = role,
        required = required,
        locator = ArtifactLocator(
            fileName = fileName,
            absolutePath = absolutePath,
        ),
        fileSizeBytes = fileSizeBytes,
        sha256 = expectedSha256,
        runtimeCompatibility = runtimeCompatibility,
    )
}

private fun promptFamilyFromProfileId(promptProfileId: String?): PromptTemplateFamily {
    val normalized = promptProfileId?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return PromptTemplateFamily.CHATML
    }
    return PromptTemplateFamily.entries.firstOrNull { family ->
        family.name.equals(normalized, ignoreCase = true)
    } ?: when (normalized.lowercase()) {
        "chatml-default", "manifest-unknown", "local-import-unknown" -> PromptTemplateFamily.CHATML
        "llama3-default" -> PromptTemplateFamily.LLAMA3
        "phi-default" -> PromptTemplateFamily.PHI
        "gemma2-it-legacy" -> PromptTemplateFamily.GEMMA
        "gemma4-e2b" -> PromptTemplateFamily.GEMMA4
        else -> PromptTemplateFamily.CHATML
    }
}
