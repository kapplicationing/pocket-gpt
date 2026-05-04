package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallParserTest {

    @Test
    fun `parse extracts single tool call`() {
        val text = """<tool_call>
{"name": "calculator", "arguments": {"expression": "4+5"}}
</tool_call>"""

        val result = ToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertEquals("calculator", result.toolCalls[0].name)
        assertTrue(result.toolCalls[0].argumentsJson.contains("4+5"))
        assertTrue(result.textWithoutToolCalls.isBlank())
    }

    @Test
    fun `parse extracts tool call with surrounding text`() {
        val text = """I'll calculate that for you.
<tool_call>
{"name": "calculator", "arguments": {"expression": "10*3"}}
</tool_call>"""

        val result = ToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertEquals("calculator", result.toolCalls[0].name)
        assertEquals("I'll calculate that for you.", result.textWithoutToolCalls)
    }

    @Test
    fun `parse extracts multiple tool calls`() {
        val text = """<tool_call>
{"name": "date_time", "arguments": {}}
</tool_call>
<tool_call>
{"name": "calculator", "arguments": {"expression": "2+2"}}
</tool_call>"""

        val result = ToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertEquals("date_time", result.toolCalls[0].name)
        assertEquals("calculator", result.toolCalls[1].name)
    }

    @Test
    fun `parse extracts multiple bare json tool calls when response is only tool payloads`() {
        val text = """
{"name":"date_time","arguments":{}}
{"name":"notes_lookup","arguments":{"query":"how you doin today lad?"}}
        """.trimIndent()

        val result = ToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertEquals("date_time", result.toolCalls[0].name)
        assertEquals("notes_lookup", result.toolCalls[1].name)
        assertTrue(result.textWithoutToolCalls.isBlank())
    }

    @Test
    fun `parse does not treat surrounding prose plus bare json as a tool sequence`() {
        val text = """
I'll use a tool now.
{"name":"date_time","arguments":{}}
        """.trimIndent()

        val result = ToolCallParser.parse(text)

        assertTrue(result.toolCalls.isEmpty())
        assertEquals(text, result.textWithoutToolCalls)
    }

    @Test
    fun `parse returns empty list when no tool calls present`() {
        val text = "Just a normal response."

        val result = ToolCallParser.parse(text)

        assertTrue(result.toolCalls.isEmpty())
        assertEquals("Just a normal response.", result.textWithoutToolCalls)
    }

    @Test
    fun `parse handles malformed json gracefully`() {
        val text = """<tool_call>
not valid json
</tool_call>"""

        val result = ToolCallParser.parse(text)

        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `parse treats trailing tool call payload without close tag as stop trimmed tool call`() {
        val text = "Before<tool_call>{\"name\":\"calc\",\"arguments\":{}}"

        val result = ToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertEquals("calc", result.toolCalls[0].name)
        assertEquals("Before", result.textWithoutToolCalls)
    }

    @Test
    fun `parse keeps malformed trailing unclosed tool call text visible`() {
        val text = "Before<tool_call>{\"name\":"

        val result = ToolCallParser.parse(text)

        assertTrue(result.toolCalls.isEmpty())
        assertEquals(text, result.textWithoutToolCalls)
    }

    @Test
    fun `renderToolDefinitionsXml produces xml for known tools`() {
        val xml = ToolCallParser.renderToolDefinitionsXml(listOf("calculator", "date_time"))

        assertTrue(xml.contains("<tools>"))
        assertTrue(xml.contains("</tools>"))
        assertTrue(xml.contains("calculator"))
        assertTrue(xml.contains("date_time"))
        assertTrue(xml.contains("tool_call"))
    }

    @Test
    fun `renderToolDefinitionsXml returns empty for empty list`() {
        val xml = ToolCallParser.renderToolDefinitionsXml(emptyList())
        assertEquals("", xml)
    }

    @Test
    fun `parse assigns unique ids to tool calls`() {
        val text = """<tool_call>
{"name": "calculator", "arguments": {"expression": "1+1"}}
</tool_call>
<tool_call>
{"name": "calculator", "arguments": {"expression": "2+2"}}
</tool_call>"""

        val result = ToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertTrue(result.toolCalls[0].id != result.toolCalls[1].id)
    }

    @Test
    fun `parse supports custom xml tags`() {
        val text = """before<call>
{"name": "calculator", "arguments": {"expression": "3+4"}}
</call>after"""

        val result = ToolCallParser.parse(
            text = text,
            openTag = "<call>",
            closeTag = "</call>",
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals("calculator", result.toolCalls[0].name)
        assertEquals("beforeafter", result.textWithoutToolCalls)
    }
}
