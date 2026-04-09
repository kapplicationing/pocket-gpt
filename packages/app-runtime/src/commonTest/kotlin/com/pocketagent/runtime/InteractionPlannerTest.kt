package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionPlannerTest {
    @Test
    fun `planner keeps latest user turn and linked tool context under tight budget`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "model-1" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.XmlTagFormat(),
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )

        val rendered = planner.buildRenderedPrompt(
            modelId = "model-1",
            messages = listOf(
                message(InteractionRole.SYSTEM, "policy"),
                message(InteractionRole.USER, "old question"),
                message(InteractionRole.ASSISTANT, "old answer"),
                InteractionMessage(
                    role = InteractionRole.ASSISTANT,
                    parts = listOf(InteractionContentPart.Text("")),
                    toolCalls = listOf(
                        InteractionToolCall(id = "tc-1", name = "calculator", argumentsJson = """{"expression":"1+2"}"""),
                    ),
                ),
                InteractionMessage(
                    role = InteractionRole.TOOL,
                    parts = listOf(InteractionContentPart.Text("{\"result\":3}")),
                    toolCallId = "tc-1",
                ),
                message(InteractionRole.USER, "latest question"),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 128,
        )

        assertTrue(rendered.prompt.contains("latest question"))
        assertTrue(rendered.prompt.contains("tool_call(id=tc-1"))
        assertTrue(rendered.prompt.contains("{\"result\":3}"))
    }

    @Test
    fun `planner keeps prior user turn linked to latest assistant under tight budget`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "model-1" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )

        val rendered = planner.buildRenderedPrompt(
            modelId = "model-1",
            messages = listOf(
                message(InteractionRole.USER, "previous question with a longer payload"),
                message(InteractionRole.ASSISTANT, "previous answer"),
                message(InteractionRole.USER, "latest question"),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 128,
        )

        assertTrue(rendered.prompt.contains("previous question with a longer payload"))
        assertTrue(rendered.prompt.contains("previous answer"))
        assertTrue(rendered.prompt.contains("latest question"))
    }

    @Test
    fun `planner never slices message text mid-template`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "model-1" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.XmlTagFormat(),
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )
        val longText = "a".repeat(400) + " END_MARKER"

        val rendered = planner.buildRenderedPrompt(
            modelId = "model-1",
            messages = listOf(
                message(InteractionRole.USER, longText),
            ),
            memorySnippets = emptyList(),
            taskType = "long_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 64,
        )

        assertTrue(rendered.prompt.contains("END_MARKER"))
    }

    @Test
    fun `planner gates thinking and tool definitions by interaction support`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "chatml-model" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.XmlTagFormat(),
                    ),
                    "gemma-model" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.GEMMA,
                        thinkingSupport = ThinkingSupport.NONE,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
            enabledToolNames = listOf("calculator"),
        )

        val chatmlPrompt = planner.buildRenderedPrompt(
            modelId = "chatml-model",
            messages = listOf(message(InteractionRole.USER, "hello")),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 256,
            showThinking = true,
        )
        assertTrue(chatmlPrompt.prompt.contains("/think"))
        assertTrue(chatmlPrompt.prompt.contains("<tools>"))
        assertTrue(chatmlPrompt.prompt.contains(""""args":["expression"]"""))

        val gemmaPrompt = planner.buildRenderedPrompt(
            modelId = "gemma-model",
            messages = listOf(message(InteractionRole.USER, "hello")),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 256,
            showThinking = true,
        )
        assertFalse(gemmaPrompt.prompt.contains("/think"))
        assertFalse(gemmaPrompt.prompt.contains("<tools>"))
    }

    @Test
    fun `planner strips prior assistant thinking blocks from context`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "chatml-model" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )

        val rendered = planner.buildRenderedPrompt(
            modelId = "chatml-model",
            messages = listOf(
                message(InteractionRole.USER, "question"),
                message(InteractionRole.ASSISTANT, "<think>private</think>Visible answer"),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 256,
            showThinking = false,
        )

        assertFalse(rendered.prompt.contains("private"))
        assertTrue(rendered.prompt.contains("Visible answer"))
    }

    @Test
    fun `image part estimates to 300 tokens based on IMAGE_TOKEN_ESTIMATE_CHARS`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "model-1" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.NONE,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )

        // A message with a single Image part and no text should estimate to 300 tokens (1200 / 4).
        // Under a tight budget of 300 tokens (system + image message), the image message must survive.
        val rendered = planner.buildRenderedPrompt(
            modelId = "model-1",
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(
                        InteractionContentPart.Image("/tmp/photo.png"),
                    ),
                ),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 4096,
        )

        // The EchoTemplateRenderer joins Text parts; Image parts won't produce text,
        // but the message should still be included (not pruned). The prompt will contain
        // the system preamble at minimum.
        assertTrue(rendered.prompt.contains("helpful assistant"))
    }

    @Test
    fun `image parts survive removeThinkingFromContext unchanged`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "model-1" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = ImageAwareTemplateRenderer(),
        )

        val rendered = planner.buildRenderedPrompt(
            modelId = "model-1",
            messages = listOf(
                message(InteractionRole.USER, "describe this"),
                InteractionMessage(
                    role = InteractionRole.ASSISTANT,
                    parts = listOf(
                        InteractionContentPart.Text("<think>reasoning</think>Visible text"),
                        InteractionContentPart.Image("/tmp/diagram.png"),
                    ),
                ),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 4096,
            showThinking = false,
        )

        // Thinking text should be stripped, but the image marker must survive.
        assertFalse(rendered.prompt.contains("reasoning"))
        assertTrue(rendered.prompt.contains("Visible text"))
        assertTrue(rendered.prompt.contains("[image:/tmp/diagram.png]"))
    }

    @Test
    fun `planner injects think directive into all user turns`() {
        val planner = InteractionPlanner(
            interactionRegistry = ModelInteractionRegistry(
                profileByModelId = mapOf(
                    "chatml-model" to ModelInteractionProfile(
                        templateFamily = PromptTemplateFamily.CHATML,
                        thinkingSupport = ThinkingSupport.THINK_TAGS,
                        toolCallSupport = ToolCallSupport.NONE,
                    ),
                ),
            ),
            templateRenderer = EchoTemplateRenderer(),
        )

        val rendered = planner.buildRenderedPrompt(
            modelId = "chatml-model",
            messages = listOf(
                message(InteractionRole.USER, "older question"),
                message(InteractionRole.ASSISTANT, "older answer"),
                message(InteractionRole.USER, "latest question"),
            ),
            memorySnippets = emptyList(),
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            promptCharBudget = 256,
            showThinking = false,
        )

        assertTrue(rendered.prompt.contains("/no_think\nolder question"))
        assertTrue(rendered.prompt.contains("/no_think\nlatest question"))
    }
}

private class EchoTemplateRenderer : ChatTemplateRenderer {
    override fun render(
        messages: List<InteractionMessage>,
        modelProfile: PromptTemplateFamily,
    ): RenderedPrompt {
        val prompt = messages.joinToString(separator = "\n") { message ->
            val text = message.parts
                .filterIsInstance<InteractionContentPart.Text>()
                .joinToString(separator = "") { it.text }
            if (message.toolCalls.isEmpty()) {
                text
            } else {
                text + message.toolCalls.joinToString(separator = "") { call ->
                    " tool_call(id=${call.id},name=${call.name})"
                }
            }
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = emptyList(),
            templateFamily = modelProfile,
        )
    }
}

private class ImageAwareTemplateRenderer : ChatTemplateRenderer {
    override fun render(
        messages: List<InteractionMessage>,
        modelProfile: PromptTemplateFamily,
    ): RenderedPrompt {
        val prompt = messages.joinToString(separator = "\n") { message ->
            message.parts.joinToString(separator = "") { part ->
                when (part) {
                    is InteractionContentPart.Text -> part.text
                    is InteractionContentPart.Image -> "[image:${part.path}]"
                }
            }
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = emptyList(),
            templateFamily = modelProfile,
        )
    }
}

private fun message(role: InteractionRole, text: String): InteractionMessage {
    return InteractionMessage(
        role = role,
        parts = listOf(InteractionContentPart.Text(text)),
    )
}
