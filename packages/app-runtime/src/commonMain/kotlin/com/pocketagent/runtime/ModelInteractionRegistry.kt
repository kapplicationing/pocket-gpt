package com.pocketagent.runtime

import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.NormalizedModelSpec
import com.pocketagent.core.model.SystemPromptHandling
import com.pocketagent.core.model.ThinkingStrategyId
import com.pocketagent.core.model.ToolCallStrategyId
import com.pocketagent.inference.ModelCatalog

class ModelInteractionRegistry(
    private val specProvider: ModelSpecProvider = ModelCatalog,
    private val profileByModelId: Map<String, ModelInteractionProfile>? = null,
) {
    fun interactionProfileForModel(modelId: String): ModelInteractionProfile {
        profileByModelId?.get(modelId)?.let { profile ->
            return profile
        }
        val spec = specProvider.specFor(modelId)
            ?: throw RuntimeTemplateUnavailableException("TEMPLATE_UNAVAILABLE: model profile missing for $modelId")
        return profileFromSpec(spec)
    }

    fun templateFamilyForModel(modelId: String) = interactionProfileForModel(modelId).templateFamily

    fun profilesByModelId(): Map<String, ModelInteractionProfile> {
        return profileByModelId ?: eligibleSpecs().associate { spec -> spec.modelId to profileFromSpec(spec) }
    }

    fun ensureTemplateAvailable(modelId: String): String? {
        return runCatching { interactionProfileForModel(modelId) }
            .exceptionOrNull()
            ?.message
    }

    private fun eligibleSpecs(): List<NormalizedModelSpec> {
        return specProvider.allSpecs()
            .filter { spec ->
                spec.runtimeRequirements.bridgeSupported || spec.productPolicy.startupCandidate
            }
    }

    companion object {
        internal fun profileFromSpec(spec: NormalizedModelSpec): ModelInteractionProfile {
            val toolCallSupport = if (spec.promptProfile.toolCallStrategy == ToolCallStrategyId.XML_TAGS) {
                ToolCallSupport.XmlTagFormat()
            } else {
                ToolCallSupport.NONE
            }
            val thinkingSupport = when (spec.promptProfile.thinkingStrategy) {
                ThinkingStrategyId.THINK_TAGS -> ThinkingSupport.THINK_TAGS
                else -> ThinkingSupport.NONE
            }
            val systemPromptStrategy = when (spec.promptProfile.systemPromptHandling) {
                SystemPromptHandling.PREPEND_TO_USER -> SystemPromptStrategy.PREPEND_TO_USER
                SystemPromptHandling.NATIVE -> SystemPromptStrategy.NATIVE
                SystemPromptHandling.UNSUPPORTED -> SystemPromptStrategy.NATIVE
            }
            val roleNameOverrides = buildMap {
                if (spec.promptProfile.assistantRoleName != "assistant") {
                    put(InteractionRole.ASSISTANT, spec.promptProfile.assistantRoleName)
                }
            }
            return ModelInteractionProfile(
                templateFamily = spec.promptProfile.templateFamily,
                thinkingSupport = thinkingSupport,
                toolCallSupport = toolCallSupport,
                systemPromptStrategy = systemPromptStrategy,
                roleNameOverrides = roleNameOverrides,
            )
        }
    }
}
