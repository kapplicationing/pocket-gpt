package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily

interface ChatTemplateRenderer {
    fun render(
        messages: List<InteractionMessage>,
        modelProfile: PromptTemplateFamily,
    ): RenderedPrompt
}

class DefaultChatTemplateRenderer : ChatTemplateRenderer {
    override fun render(
        messages: List<InteractionMessage>,
        modelProfile: PromptTemplateFamily,
    ): RenderedPrompt {
        return when (modelProfile) {
            PromptTemplateFamily.CHATML -> renderChatMl(messages)
            PromptTemplateFamily.LLAMA3 -> renderLlama3(messages)
            PromptTemplateFamily.PHI -> renderPhi(messages)
            PromptTemplateFamily.GEMMA -> renderGemma(messages)
            PromptTemplateFamily.GEMMA4 -> renderGemma4(messages)
        }
    }

    private fun renderChatMl(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            messages.forEach { message ->
                append("<|im_start|>")
                append(message.role.toTemplateRole())
                append('\n')
                append(message.renderedContentWithToolCalls())
                append('\n')
                append("<|im_end|>")
                append('\n')
            }
            append("<|im_start|>assistant\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|im_end|>", "<|im_start|>user", "</tool_call>"),
            templateFamily = PromptTemplateFamily.CHATML,
        )
    }

    private fun renderLlama3(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            append("<|begin_of_text|>")
            messages.forEach { message ->
                append("<|start_header_id|>")
                append(message.role.toTemplateRole())
                append("<|end_header_id|>\n\n")
                append(message.renderedText())
                append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|eot_id|>", "<|start_header_id|>user<|end_header_id|>"),
            templateFamily = PromptTemplateFamily.LLAMA3,
        )
    }
    private fun renderPhi(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            messages.forEach { message ->
                append("<|")
                append(message.role.toTemplateRole())
                append("|>\n")
                append(message.renderedText())
                append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|end|>", "<|endoftext|>"),
            templateFamily = PromptTemplateFamily.PHI,
        )
    }

    private fun renderGemma(messages: List<InteractionMessage>): RenderedPrompt {
        // Gemma uses "model" instead of "assistant" and has no native system role.
        // System messages are prepended to the first user message.
        val systemText = messages
            .filter { it.role == InteractionRole.SYSTEM }
            .joinToString("\n") { it.renderedText() }
        val nonSystemMessages = messages.filter { it.role != InteractionRole.SYSTEM }
        var systemPrepended = systemText.isBlank()

        val prompt = buildString {
            append("<bos>")
            nonSystemMessages.forEach { message ->
                val role = when (message.role) {
                    InteractionRole.ASSISTANT -> "model"
                    else -> message.role.toTemplateRole()
                }
                append("<start_of_turn>")
                append(role)
                append("\n")
                if (!systemPrepended && message.role == InteractionRole.USER) {
                    append(systemText)
                    append("\n\n")
                    systemPrepended = true
                }
                append(message.renderedText())
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<end_of_turn>", "<start_of_turn>user"),
            templateFamily = PromptTemplateFamily.GEMMA,
        )
    }

    private fun renderGemma4(messages: List<InteractionMessage>): RenderedPrompt {
        // Gemma 4 uses "model" instead of "assistant" and has native system role support.
        val prompt = buildString {
            append("<bos>")
            messages.forEach { message ->
                val role = when (message.role) {
                    InteractionRole.ASSISTANT -> "model"
                    else -> message.role.toTemplateRole()
                }
                append("<|turn>")
                append(role)
                append("\n")
                append(message.renderedText())
                append("<turn|>")
            }
            append("<|turn>model\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<turn|>", "<|turn>user"),
            templateFamily = PromptTemplateFamily.GEMMA4,
        )
    }
}

private fun InteractionRole.toTemplateRole(): String {
    return when (this) {
        InteractionRole.SYSTEM -> "system"
        InteractionRole.USER -> "user"
        InteractionRole.ASSISTANT -> "assistant"
        InteractionRole.TOOL -> "tool"
    }
}

private fun InteractionMessage.renderedText(): String {
    return parts.filterIsInstance<InteractionContentPart.Text>()
        .joinToString(separator = "\n") { it.text }
}

/**
 * Renders message content including tool calls (for ChatML).
 * - Assistant messages with tool calls append `<tool_call>` blocks after the text.
 * - Tool role messages wrap their content in `<tool_response>` blocks.
 * - All other messages render as plain text.
 */
private fun InteractionMessage.renderedContentWithToolCalls(): String {
    val textContent = renderedText()
    return when {
        role == InteractionRole.ASSISTANT && toolCalls.isNotEmpty() -> {
            buildString {
                if (textContent.isNotBlank()) {
                    append(textContent)
                    append('\n')
                }
                toolCalls.forEach { call ->
                    append("<tool_call>\n")
                    append("{\"name\": \"${call.name}\", \"arguments\": ${call.argumentsJson}}")
                    append("\n</tool_call>")
                }
            }
        }
        role == InteractionRole.TOOL -> {
            buildString {
                append("<tool_response>")
                append(textContent)
                append("</tool_response>")
            }
        }
        else -> textContent
    }
}
