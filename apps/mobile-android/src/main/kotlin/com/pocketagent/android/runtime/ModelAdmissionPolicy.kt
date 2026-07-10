package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.NormalizedModelCatalogRegistry
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

enum class ModelAdmissionAction {
    DOWNLOAD,
    IMPORT,
    ACTIVATE,
    LOAD,
}

data class ModelAdmissionSubject(
    val modelId: String,
    val version: String? = null,
    val runtimeCompatibility: String = "",
    val installedDescriptor: ModelVersionDescriptor? = null,
    val distributionVersion: ModelDistributionVersion? = null,
)

data class ModelAdmissionDecision(
    val action: ModelAdmissionAction,
    val subject: ModelAdmissionSubject,
    val eligibility: ModelVersionEligibility,
) {
    val allowed: Boolean
        get() = when (action) {
            ModelAdmissionAction.DOWNLOAD,
            ModelAdmissionAction.IMPORT,
            -> eligibility.downloadAllowed

            ModelAdmissionAction.ACTIVATE,
            ModelAdmissionAction.LOAD,
            -> eligibility.loadAllowed
        }

    fun asLifecycleRejectedResult(): RuntimeModelLifecycleCommandResult {
        val technicalDetail = buildTechnicalDetail()
        val missingArtifacts = missingRequiredArtifactsFromTechnicalDetail(technicalDetail)
        return RuntimeModelLifecycleCommandResult.rejected(
            code = if (missingArtifacts.isNullOrEmpty()) {
                ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE
            } else {
                ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE
            },
            detail = technicalDetail,
        )
    }

    fun asRuntimeDomainException(): RuntimeDomainException {
        val technicalDetail = buildTechnicalDetail()
        val missingArtifacts = missingRequiredArtifactsFromTechnicalDetail(technicalDetail)
        return RuntimeDomainException(
            domainError = RuntimeDomainError(
                code = RuntimeErrorCodes.MODEL_ADMISSION_BLOCKED,
                userMessage = if (missingArtifacts.isNullOrEmpty()) {
                    userMessageFor(eligibility.reason)
                } else {
                    missingRequiredArtifactsUserMessage(missingArtifacts)
                },
                technicalDetail = technicalDetail,
            ),
        )
    }

    private fun buildTechnicalDetail(): String {
        return buildString {
            append("action=").append(action.name.lowercase())
            append("|model=").append(subject.modelId)
            subject.version?.takeIf { it.isNotBlank() }?.let { version ->
                append("|version=").append(version)
            }
            subject.runtimeCompatibility.takeIf { it.isNotBlank() }?.let { runtimeCompatibility ->
                append("|runtime_compatibility=").append(runtimeCompatibility)
            }
            append("|reason=").append(eligibility.reason.name.lowercase())
            eligibility.technicalDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                append("|eligibility=").append(detail)
            }
        }
    }
}

interface ModelAdmissionPolicy {
    fun evaluate(
        action: ModelAdmissionAction,
        subject: ModelAdmissionSubject,
    ): ModelAdmissionDecision

    fun requireAllowed(
        action: ModelAdmissionAction,
        subject: ModelAdmissionSubject,
    ) {
        val decision = evaluate(action = action, subject = subject)
        if (!decision.allowed) {
            throw decision.asRuntimeDomainException()
        }
    }
}

class DefaultModelAdmissionPolicy(
    private val signalsProvider: ModelEligibilitySignalsProvider,
    private val eligibilityEvaluator: ModelCatalogEligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
    private val catalogRegistry: NormalizedModelCatalogRegistry? = null,
    private val launchPlanner: ModelRuntimeLaunchPlanner? = null,
    private val rules: List<ModelAdmissionRule> = defaultModelAdmissionRules(),
) : ModelAdmissionPolicy {
    override fun evaluate(
        action: ModelAdmissionAction,
        subject: ModelAdmissionSubject,
    ): ModelAdmissionDecision {
        val signals = signalsProvider.currentSignals()
        val baseEligibility = eligibilityEvaluator.evaluateCandidate(
            candidate = ModelEligibilityCandidate(
                modelId = subject.modelId,
                version = subject.version,
                runtimeCompatibility = subject.runtimeCompatibility,
            ),
            signals = signals,
        )
        val launchPlan = when {
            subject.installedDescriptor != null && launchPlanner != null ->
                launchPlanner.planInstalledModel(subject.installedDescriptor)
            subject.distributionVersion != null && launchPlanner != null ->
                launchPlanner.planDistributionVersion(subject.distributionVersion)
            else -> null
        }
        val specEligibility = ModelAdmissionContext(
            action = action,
            subject = subject,
            signals = signals,
            launchPlan = launchPlan,
            catalogRegistry = catalogRegistry,
        ).let { context ->
            rules.firstNotNullOfOrNull { rule -> rule.evaluate(context) }
        }
        return ModelAdmissionDecision(
            action = action,
            subject = subject,
            eligibility = when {
                !baseEligibility.loadAllowed && action != ModelAdmissionAction.DOWNLOAD && action != ModelAdmissionAction.IMPORT ->
                    baseEligibility
                !baseEligibility.downloadAllowed && (action == ModelAdmissionAction.DOWNLOAD || action == ModelAdmissionAction.IMPORT) ->
                    baseEligibility
                specEligibility != null -> specEligibility
                else -> baseEligibility
            },
        )
    }
}

internal fun ModelDistributionVersion.toAdmissionSubject(): ModelAdmissionSubject {
    return ModelAdmissionSubject(
        modelId = modelId,
        version = version,
        runtimeCompatibility = runtimeCompatibility,
        distributionVersion = this,
    )
}

internal fun ModelVersionDescriptor.toAdmissionSubject(): ModelAdmissionSubject {
    return ModelAdmissionSubject(
        modelId = modelId,
        version = version,
        runtimeCompatibility = runtimeCompatibility,
        installedDescriptor = this,
    )
}

data class ModelAdmissionContext(
    val action: ModelAdmissionAction,
    val subject: ModelAdmissionSubject,
    val signals: ModelEligibilitySignals,
    val launchPlan: ModelRuntimeLaunchPlan?,
    val catalogRegistry: NormalizedModelCatalogRegistry?,
) {
    val spec = catalogRegistry?.specFor(subject.modelId)
    val variant = catalogRegistry?.variantFor(subject.modelId, subject.version)
}

interface ModelAdmissionRule {
    fun evaluate(context: ModelAdmissionContext): ModelVersionEligibility?
}

class RuntimeCompatibilityAdmissionRule : ModelAdmissionRule {
    override fun evaluate(context: ModelAdmissionContext): ModelVersionEligibility? {
        val tags = buildSet {
            addAll(context.spec?.runtimeRequirements?.runtimeCompatibilityTags.orEmpty())
            context.subject.runtimeCompatibility
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        }
        if (tags.isEmpty() || tags.contains(context.signals.runtimeCompatibilityTag)) {
            return null
        }
        return ModelVersionEligibility.unsupported(
            reason = ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH,
            technicalDetail = "model=${context.subject.modelId}|version=${context.subject.version.orEmpty()}|expected=${context.signals.runtimeCompatibilityTag}|declared=${tags.joinToString(",")}",
        )
    }
}

class BridgeEnabledAdmissionRule : ModelAdmissionRule {
    override fun evaluate(context: ModelAdmissionContext): ModelVersionEligibility? {
        val spec = context.spec ?: return null
        if (spec.runtimeRequirements.bridgeSupported) {
            return null
        }
        return ModelVersionEligibility.unsupported(
            reason = ModelEligibilityReason.MODEL_NOT_RUNTIME_ENABLED,
            technicalDetail = "model=${context.subject.modelId}|bridge_supported=false|source=${spec.source.kind.name.lowercase()}",
        )
    }
}

class ArtifactCompletenessAdmissionRule : ModelAdmissionRule {
    override fun evaluate(context: ModelAdmissionContext): ModelVersionEligibility? {
        if (context.action != ModelAdmissionAction.DOWNLOAD &&
            context.action != ModelAdmissionAction.LOAD &&
            context.action != ModelAdmissionAction.ACTIVATE
        ) {
            return null
        }
        val launchPlan = context.launchPlan ?: return null
        if (!launchPlan.loadBlocked) {
            return null
        }
        return ModelVersionEligibility.unsupported(
            reason = ModelEligibilityReason.NONE,
            technicalDetail = buildString {
                append("model=").append(context.subject.modelId)
                append("|version=").append(context.subject.version.orEmpty())
                append("|missing_artifacts=").append(launchPlan.missingRequiredArtifacts.joinToString(","))
            },
            catalogVisible = true,
        )
    }
}

internal fun defaultModelAdmissionRules(): List<ModelAdmissionRule> {
    return listOf(
        RuntimeCompatibilityAdmissionRule(),
        BridgeEnabledAdmissionRule(),
        ArtifactCompletenessAdmissionRule(),
    )
}

internal fun missingRequiredArtifactsFromTechnicalDetail(detail: String?): List<String>? {
    val rawArtifacts = detail
        ?.split('|')
        ?.firstOrNull { field -> field.startsWith("missing_artifacts=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
    if (rawArtifacts.isBlank()) {
        return null
    }
    return rawArtifacts.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .ifEmpty { null }
}

internal fun missingRequiredArtifactsUserMessage(artifacts: List<String>): String {
    val listedArtifacts = artifacts.joinToString(", ")
    return if (artifacts.size == 1) {
        "Required companion file is missing: $listedArtifacts. Re-download or re-import the full model package."
    } else {
        "Required companion files are missing: $listedArtifacts. Re-download or re-import the full model package."
    }
}

private fun userMessageFor(reason: ModelEligibilityReason): String {
    return when (reason) {
        ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH ->
            "This model version does not match the current app runtime."
        ModelEligibilityReason.MODEL_NOT_RUNTIME_ENABLED ->
            "This model is not enabled in the current build yet."
        ModelEligibilityReason.NONE,
        -> "This model action is not available right now."
    }
}
