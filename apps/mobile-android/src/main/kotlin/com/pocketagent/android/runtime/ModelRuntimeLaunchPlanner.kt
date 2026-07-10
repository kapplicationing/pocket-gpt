package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.NormalizedModelCatalogRegistry
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelVariantSpec
import com.pocketagent.nativebridge.ModelRuntimeFormatHint
import com.pocketagent.nativebridge.ModelRuntimeFormatProbeInput
import com.pocketagent.nativebridge.ModelRuntimeFormats
import java.io.File

data class ModelRuntimeLaunchPlan(
    val modelId: String,
    val version: String? = null,
    val formatHint: ModelRuntimeFormatHint,
    val sourceKind: String? = null,
    val promptProfileId: String? = null,
    val missingRequiredArtifacts: List<String> = emptyList(),
    val multimodalProjectorPath: String? = null,
    val recommendedContextTokens: Int? = null,
    val diagnostics: List<String> = emptyList(),
) {
    val loadBlocked: Boolean
        get() = missingRequiredArtifacts.isNotEmpty()
}

interface ModelRuntimeLaunchPlanner {
    fun planInstalledModel(descriptor: ModelVersionDescriptor): ModelRuntimeLaunchPlan

    fun planDistributionVersion(version: ModelDistributionVersion): ModelRuntimeLaunchPlan
}

class DefaultModelRuntimeLaunchPlanner(
    private val catalogRegistry: NormalizedModelCatalogRegistry,
) : ModelRuntimeLaunchPlanner {
    override fun planInstalledModel(descriptor: ModelVersionDescriptor): ModelRuntimeLaunchPlan {
        val spec = catalogRegistry.specFor(descriptor.modelId)
        val variant = resolveVariant(descriptor.modelId, descriptor.version)
        val resolvedArtifacts = descriptor.artifacts
        val sideArtifactsByRole = resolvedArtifacts.groupBy { artifact -> artifact.role }
        val primaryModelFile = File(descriptor.absolutePath)
        val formatHint = ModelRuntimeFormats.infer(
            ModelRuntimeFormatProbeInput(
                modelId = descriptor.modelId,
                modelVersion = descriptor.version,
                modelPath = descriptor.absolutePath,
                declaredQuantization = variant?.parameters?.quantization,
            ),
        )
        val requiredArtifacts = variant?.artifactBundle?.requiredArtifacts()
            ?.filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }
            .orEmpty()
        val missingRequiredArtifacts = requiredArtifacts
            .filter { required ->
                val matchingArtifactPresent = sideArtifactsByRole[required.role]
                    .orEmpty()
                    .any { artifact ->
                        val artifactPath = resolveInstalledArtifactPath(
                            primaryModelFile = primaryModelFile,
                            artifact = artifact,
                        ) ?: return@any false
                        val requiredFileName = required.locator.fileName?.trim().orEmpty()
                        artifactPath.exists() &&
                            (requiredFileName.isBlank() || artifactPath.name == requiredFileName)
                    }
                !matchingArtifactPresent
            }
            .map { artifact -> artifact.locator.fileName ?: artifact.role.name.lowercase() }
        val projectorPath = sideArtifactsByRole[ModelArtifactRole.MMPROJ]
            .orEmpty()
            .mapNotNull { artifact ->
                resolveInstalledArtifactPath(
                    primaryModelFile = primaryModelFile,
                    artifact = artifact,
                )?.takeIf(File::exists)?.absolutePath
            }
            .firstOrNull()
        return ModelRuntimeLaunchPlan(
            modelId = descriptor.modelId,
            version = descriptor.version,
            formatHint = formatHint,
            sourceKind = descriptor.sourceKind.name,
            promptProfileId = descriptor.promptProfileId ?: spec?.promptProfile?.profileId,
            missingRequiredArtifacts = missingRequiredArtifacts,
            multimodalProjectorPath = projectorPath,
            recommendedContextTokens = variant?.parameters?.contextLength ?: spec?.runtimeRequirements?.preferredContextTokens,
            diagnostics = buildList {
                add("source=${descriptor.sourceKind.name.lowercase()}")
                formatHint.normalizedToken?.let { token -> add("format=$token") }
                if (projectorPath != null) {
                    add("mmproj=present")
                }
                if (missingRequiredArtifacts.isNotEmpty()) {
                    add("missing=${missingRequiredArtifacts.joinToString(",")}")
                }
            },
        )
    }

    override fun planDistributionVersion(version: ModelDistributionVersion): ModelRuntimeLaunchPlan {
        val spec = catalogRegistry.specFor(version.modelId)
        val variant = resolveVariant(version.modelId, version.version)
        val primaryArtifact = version.artifacts.firstOrNull { artifact -> artifact.role == ModelArtifactRole.PRIMARY_GGUF }
            ?: version.artifacts.first()
        val formatHint = ModelRuntimeFormats.infer(
            ModelRuntimeFormatProbeInput(
                modelId = version.modelId,
                modelVersion = version.version,
                modelPath = primaryArtifact.fileName,
                declaredQuantization = variant?.parameters?.quantization,
            ),
        )
        val requiredArtifacts = variant?.artifactBundle?.requiredArtifacts()
            ?.filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }
            .orEmpty()
        val declaredArtifactsByRole = version.artifacts.groupBy { artifact -> artifact.role }
        val missingRequiredArtifacts = requiredArtifacts
            .filter { required ->
                val requiredFileName = required.locator.fileName?.trim().orEmpty()
                declaredArtifactsByRole[required.role]
                    .orEmpty()
                    .none { artifact ->
                        requiredFileName.isBlank() || artifact.fileName == requiredFileName
                    }
            }
            .map { artifact -> artifact.locator.fileName ?: artifact.role.name.lowercase() }
        return ModelRuntimeLaunchPlan(
            modelId = version.modelId,
            version = version.version,
            formatHint = formatHint,
            sourceKind = version.sourceKind.name,
            promptProfileId = version.promptProfileId ?: spec?.promptProfile?.profileId,
            missingRequiredArtifacts = missingRequiredArtifacts,
            recommendedContextTokens = variant?.parameters?.contextLength ?: spec?.runtimeRequirements?.preferredContextTokens,
            diagnostics = buildList {
                add("source=${version.sourceKind.name.lowercase()}")
                formatHint.normalizedToken?.let { token -> add("format=$token") }
                if (missingRequiredArtifacts.isNotEmpty()) {
                    add("missing=${missingRequiredArtifacts.joinToString(",")}")
                }
            },
        )
    }

    private fun resolveVariant(
        modelId: String,
        version: String?,
    ): ModelVariantSpec? {
        return catalogRegistry.variantFor(modelId, version)
            ?: catalogRegistry.specFor(modelId)?.variants?.singleOrNull()
    }

    private fun resolveInstalledArtifactPath(
        primaryModelFile: File,
        artifact: InstalledArtifactDescriptor,
    ): File? {
        artifact.absolutePath
            ?.takeIf { path -> path.isNotBlank() }
            ?.let(::File)
            ?.let { file -> if (file.exists()) return file else null }
        if (artifact.fileName.isBlank()) {
            return null
        }
        val artifactFileName = File(artifact.fileName)
        if (artifactFileName.isAbsolute && artifactFileName.exists()) {
            return artifactFileName
        }
        val sibling = primaryModelFile.parentFile?.resolve(artifact.fileName)
        return sibling?.takeIf(File::exists)
    }
}
