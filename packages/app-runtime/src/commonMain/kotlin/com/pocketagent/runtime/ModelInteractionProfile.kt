package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily

data class ModelInteractionProfile(
    val templateFamily: PromptTemplateFamily,
    val thinkingSupport: ThinkingSupport = ThinkingSupport.NONE,
    val toolCallSupport: ToolCallSupport = ToolCallSupport.NONE,
    val systemPromptStrategy: SystemPromptStrategy = SystemPromptStrategy.NATIVE,
    val roleNameOverrides: Map<InteractionRole, String> = emptyMap(),
)

enum class ThinkingSupport {
    NONE,
    THINK_TAGS,
}

sealed interface ToolCallSupport {
    object NONE : ToolCallSupport

    data class XmlTagFormat(
        val openTag: String = "<tool_call>",
        val closeTag: String = "</tool_call>",
        val responseOpenTag: String = "<tool_response>",
        val responseCloseTag: String = "</tool_response>",
    ) : ToolCallSupport
}

enum class SystemPromptStrategy {
    NATIVE,
    PREPEND_TO_USER,
}
