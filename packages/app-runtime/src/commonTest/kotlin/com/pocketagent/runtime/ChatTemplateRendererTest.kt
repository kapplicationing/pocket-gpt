package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTemplateRendererTest {
    private val renderer = DefaultChatTemplateRenderer()

    @Test
    fun `chatml profile renders role headers and stop sequences`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.SYSTEM,
                    parts = listOf(InteractionContentPart.Text("You are local.")),
                ),
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("hello")),
                ),
            ),
            modelProfile = PromptTemplateFamily.CHATML,
        )

        assertTrue(rendered.prompt.contains("<|im_start|>system"))
        assertTrue(rendered.prompt.contains("<|im_start|>user"))
        assertTrue(rendered.prompt.endsWith("<|im_start|>assistant\n"))
        assertEquals(listOf("<|im_end|>", "<|im_start|>user", "</tool_call>"), rendered.stopSequences)
    }

    @Test
    fun `llama3 profile renders headers and stop sequences`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("hello")),
                ),
            ),
            modelProfile = PromptTemplateFamily.LLAMA3,
        )

        assertTrue(rendered.prompt.contains("<|begin_of_text|>"))
        assertTrue(rendered.prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(rendered.prompt.contains("<|start_header_id|>assistant<|end_header_id|>"))
        assertEquals(listOf("<|eot_id|>", "<|start_header_id|>user<|end_header_id|>"), rendered.stopSequences)
    }

    @Test
    fun `chatml renders assistant tool calls in tool_call blocks`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("What is 4+5?")),
                ),
                InteractionMessage(
                    role = InteractionRole.ASSISTANT,
                    parts = listOf(InteractionContentPart.Text("Let me calculate.")),
                    toolCalls = listOf(
                        InteractionToolCall(
                            id = "tc-1",
                            name = "calculator",
                            argumentsJson = """{"expression": "4+5"}""",
                        ),
                    ),
                ),
            ),
            modelProfile = PromptTemplateFamily.CHATML,
        )

        assertTrue(rendered.prompt.contains("<tool_call>"))
        assertTrue(rendered.prompt.contains("\"calculator\""))
        assertTrue(rendered.prompt.contains("</tool_call>"))
    }

    @Test
    fun `chatml renders tool response in tool_response blocks`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.TOOL,
                    parts = listOf(InteractionContentPart.Text("9.0")),
                    toolCallId = "tc-1",
                ),
            ),
            modelProfile = PromptTemplateFamily.CHATML,
        )

        assertTrue(rendered.prompt.contains("<|im_start|>tool"))
        assertTrue(rendered.prompt.contains("<tool_response>9.0</tool_response>"))
    }

    @Test
    fun `gemma profile uses model role for assistant and prepends system to first user`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.SYSTEM,
                    parts = listOf(InteractionContentPart.Text("You are helpful.")),
                ),
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("hello")),
                ),
                InteractionMessage(
                    role = InteractionRole.ASSISTANT,
                    parts = listOf(InteractionContentPart.Text("Hi there!")),
                ),
            ),
            modelProfile = PromptTemplateFamily.GEMMA,
        )

        assertTrue(rendered.prompt.startsWith("<bos>"), "should start with <bos>")
        assertTrue(rendered.prompt.contains("<start_of_turn>user\nYou are helpful."), "system text prepended to first user turn")
        assertTrue(rendered.prompt.contains("<start_of_turn>model\nHi there!"), "assistant rendered as model role")
        assertTrue(rendered.prompt.endsWith("<start_of_turn>model\n"), "generation prompt uses model role")
        assertEquals(listOf("<end_of_turn>", "<start_of_turn>user"), rendered.stopSequences)
        assertEquals(PromptTemplateFamily.GEMMA, rendered.templateFamily)
    }

    @Test
    fun `chatml stop sequences include tool_call closer`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("test")),
                ),
            ),
            modelProfile = PromptTemplateFamily.CHATML,
        )

        assertTrue(rendered.stopSequences.contains("</tool_call>"))
    }
}
