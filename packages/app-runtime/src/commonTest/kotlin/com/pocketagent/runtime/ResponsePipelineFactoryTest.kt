package com.pocketagent.runtime

import com.pocketagent.core.model.PromptTemplateFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsePipelineFactoryTest {
    @Test
    fun `factory returns no-op pipeline when profile has no thinking or tools`() {
        val profile = ModelInteractionProfile(
            templateFamily = PromptTemplateFamily.GEMMA,
            thinkingSupport = ThinkingSupport.NONE,
            toolCallSupport = ToolCallSupport.NONE,
        )

        val filters = ResponsePipelineFactory.createStreamFilters(profile = profile, showThinking = true)
        assertNull(filters.thinkingFilter)

        val stripped = ResponsePipelineFactory.stripThinking("<think>hidden</think>visible", profile)
        assertEquals("<think>hidden</think>visible", stripped)

        val parsed = ResponsePipelineFactory.parseToolCalls(
            text = "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>",
            profile = profile,
        )
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals("<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>", parsed.textWithoutToolCalls)
    }

    @Test
    fun `factory parses and strips when profile enables think tags and xml tool calls`() {
        val profile = ModelInteractionProfile(
            templateFamily = PromptTemplateFamily.CHATML,
            thinkingSupport = ThinkingSupport.THINK_TAGS,
            toolCallSupport = ToolCallSupport.XmlTagFormat(
                openTag = "<call>",
                closeTag = "</call>",
            ),
        )

        val filters = ResponsePipelineFactory.createStreamFilters(profile = profile, showThinking = false)
        assertTrue(filters.thinkingFilter != null)
        assertEquals("", filters.thinkingFilter?.filterToken("<think>hidden</think>")?.visibleText.orEmpty())
        assertEquals("", filters.thinkingFilter?.capturedThinking().orEmpty())

        val stripped = ResponsePipelineFactory.stripThinking("<think>hidden</think>visible", profile)
        assertEquals("visible", stripped)

        val parsed = ResponsePipelineFactory.parseToolCalls(
            text = "before<call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</call>after",
            profile = profile,
        )
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("calculator", parsed.toolCalls.first().name)
        assertEquals("beforeafter", parsed.textWithoutToolCalls)
    }

    @Test
    fun `factory accepts bare json tool payloads for xml tool-call profiles`() {
        val profile = ModelInteractionProfile(
            templateFamily = PromptTemplateFamily.CHATML,
            thinkingSupport = ThinkingSupport.THINK_TAGS,
            toolCallSupport = ToolCallSupport.XmlTagFormat(),
        )

        val parsed = ResponsePipelineFactory.parseToolCalls(
            text = """
{"name":"date_time","arguments":{}}
{"name":"notes_lookup","arguments":{"query":"how you doin today lad?"}}
            """.trimIndent(),
            profile = profile,
        )

        assertEquals(2, parsed.toolCalls.size)
        assertEquals("date_time", parsed.toolCalls[0].name)
        assertEquals("notes_lookup", parsed.toolCalls[1].name)
        assertTrue(parsed.textWithoutToolCalls.isBlank())
    }
}
