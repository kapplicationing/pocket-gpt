package com.pocketagent.core.model

data class PromptProfileTemplate(
    val profileId: String,
    val family: PromptTemplateFamily,
    val systemPromptHandling: SystemPromptHandling = SystemPromptHandling.NATIVE,
    val assistantRoleName: String = "assistant",
    val stopSequences: List<String> = emptyList(),
) {
    fun toPromptProfile(
        toolCallStrategy: ToolCallStrategyId = ToolCallStrategyId.NONE,
        thinkingStrategy: ThinkingStrategyId = ThinkingStrategyId.NONE,
    ): PromptProfile {
        return PromptProfile(
            profileId = profileId,
            templateFamily = family,
            systemPromptHandling = systemPromptHandling,
            toolCallStrategy = toolCallStrategy,
            thinkingStrategy = thinkingStrategy,
            assistantRoleName = assistantRoleName,
            stopSequences = stopSequences,
        )
    }
}

object PromptProfileRegistry {
    private val templates: Map<PromptTemplateFamily, PromptProfileTemplate> = mapOf(
        PromptTemplateFamily.CHATML to PromptProfileTemplate(
            profileId = "chatml-default",
            family = PromptTemplateFamily.CHATML,
            stopSequences = listOf("<|im_end|>", "<|im_start|>user", "</tool_call>"),
        ),
        PromptTemplateFamily.LLAMA3 to PromptProfileTemplate(
            profileId = "llama3-default",
            family = PromptTemplateFamily.LLAMA3,
            stopSequences = listOf("<|eot_id|>", "<|start_header_id|>user<|end_header_id|>"),
        ),
        PromptTemplateFamily.PHI to PromptProfileTemplate(
            profileId = "phi-default",
            family = PromptTemplateFamily.PHI,
            stopSequences = listOf("<|end|>", "<|endoftext|>"),
        ),
        PromptTemplateFamily.GEMMA to PromptProfileTemplate(
            profileId = "gemma2-it-legacy",
            family = PromptTemplateFamily.GEMMA,
            systemPromptHandling = SystemPromptHandling.PREPEND_TO_USER,
            assistantRoleName = "model",
            stopSequences = listOf("<end_of_turn>", "<start_of_turn>user"),
        ),
        PromptTemplateFamily.GEMMA4 to PromptProfileTemplate(
            profileId = "gemma4-e2b",
            family = PromptTemplateFamily.GEMMA4,
            assistantRoleName = "model",
            stopSequences = listOf("<turn|>", "<|turn>user"),
        ),
    )

    fun templateFor(family: PromptTemplateFamily): PromptProfileTemplate {
        return templates[family] ?: error("No prompt profile template registered for $family")
    }

    fun promptProfileFor(
        family: PromptTemplateFamily,
        toolCallStrategy: ToolCallStrategyId = ToolCallStrategyId.NONE,
        thinkingStrategy: ThinkingStrategyId = ThinkingStrategyId.NONE,
    ): PromptProfile {
        return templateFor(family).toPromptProfile(
            toolCallStrategy = toolCallStrategy,
            thinkingStrategy = thinkingStrategy,
        )
    }

    fun allFamilies(): Set<PromptTemplateFamily> = templates.keys
}
