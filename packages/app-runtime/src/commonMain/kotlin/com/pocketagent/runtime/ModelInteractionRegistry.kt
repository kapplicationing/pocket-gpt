package com.pocketagent.runtime

import com.pocketagent.core.model.NormalizedModelSpec
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.core.model.SystemPromptHandling
import com.pocketagent.core.model.ThinkingStrategyId
import com.pocketagent.core.model.ToolCallStrategyId
import com.pocketagent.inference.ModelCatalog

class ModelInteractionRegistry(
    private val profileByModelId: Map<String, ModelInteractionProfile> = defaultProfiles(),
) {
    fun interactionProfileForModel(modelId: String): ModelInteractionProfile {
        return profileByModelId[modelId]
            ?: throw RuntimeTemplateUnavailableException("TEMPLATE_UNAVAILABLE: model profile missing for $modelId")
    }

    fun templateProfileForModel(modelId: String): ModelTemplateProfile {
        return interactionProfileForModel(modelId).templateProfile
    }

    fun ensureTemplateAvailable(modelId: String): String? {
        return runCatching { interactionProfileForModel(modelId) }
            .exceptionOrNull()
            ?.message
    }

    companion object {
        fun defaultProfiles(): Map<String, ModelInteractionProfile> {
            return ModelCatalog.normalizedSpecs()
                .filter { spec ->
                    spec.runtimeRequirements.bridgeSupported || spec.productPolicy.startupCandidate
                }
                .associate { spec ->
                    spec.modelId to profileFromSpec(spec)
                }
        }

        private fun profileFromSpec(spec: NormalizedModelSpec): ModelInteractionProfile {
            val templateProfile = when (spec.promptProfile.templateFamily) {
                PromptTemplateFamily.CHATML -> ModelTemplateProfile.CHATML
                PromptTemplateFamily.LLAMA3 -> ModelTemplateProfile.LLAMA3
                PromptTemplateFamily.PHI -> ModelTemplateProfile.PHI
                PromptTemplateFamily.GEMMA -> ModelTemplateProfile.GEMMA
                PromptTemplateFamily.GEMMA4 -> ModelTemplateProfile.GEMMA4
            }
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
                templateProfile = templateProfile,
                thinkingSupport = thinkingSupport,
                toolCallSupport = toolCallSupport,
                systemPromptStrategy = systemPromptStrategy,
                roleNameOverrides = roleNameOverrides,
            )
        }
    }
}
