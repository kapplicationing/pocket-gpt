package com.pocketagent.runtime

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses `<tool_call>` blocks from model output text.
 *
 * SmolLM3 emits tool calls in the format:
 * ```
 * <tool_call>
 * {"name": "calculator", "arguments": {"expression": "4+5"}}
 * </tool_call>
 * ```
 *
 * This parser extracts all such blocks and maps them to [InteractionToolCall] instances.
 */
internal object ToolCallParser {
    private const val DEFAULT_OPEN_TAG = "<tool_call>"
    private const val DEFAULT_CLOSE_TAG = "</tool_call>"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val toolCallSequence = AtomicLong(0L)

    data class ParsedToolCalls(
        val toolCalls: List<InteractionToolCall>,
        val textWithoutToolCalls: String,
    )

    /**
     * Extracts all `<tool_call>...</tool_call>` blocks from [text] and returns
     * the parsed tool calls plus the remaining visible text.
     */
    fun parse(
        text: String,
        openTag: String = DEFAULT_OPEN_TAG,
        closeTag: String = DEFAULT_CLOSE_TAG,
    ): ParsedToolCalls {
        if (!text.contains(openTag)) {
            return parseBareToolCallSequence(text) ?: ParsedToolCalls(toolCalls = emptyList(), textWithoutToolCalls = text)
        }

        val toolCalls = mutableListOf<InteractionToolCall>()
        val remaining = StringBuilder(text.length)
        var pos = 0

        while (pos < text.length) {
            val openIdx = text.indexOf(openTag, pos)
            if (openIdx < 0) {
                remaining.append(text, pos, text.length)
                break
            }
            remaining.append(text, pos, openIdx)
            val contentStart = openIdx + openTag.length
            val closeIdx = text.indexOf(closeTag, contentStart)
            if (closeIdx < 0) {
                val trailingJsonBlock = text.substring(contentStart).trim()
                val parsedTrailing = parseToolCallJson(trailingJsonBlock)
                if (parsedTrailing != null) {
                    toolCalls += parsedTrailing
                    break
                }
                // Unclosed tag with non-parseable payload — treat remaining as text.
                remaining.append(text, openIdx, text.length)
                break
            }
            val jsonBlock = text.substring(contentStart, closeIdx).trim()
            val parsed = parseToolCallJson(jsonBlock)
            if (parsed != null) {
                toolCalls += parsed
            }
            pos = closeIdx + closeTag.length
        }

        return ParsedToolCalls(
            toolCalls = toolCalls,
            textWithoutToolCalls = remaining.toString().trim(),
        )
    }

    private fun parseBareToolCallSequence(text: String): ParsedToolCalls? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }
        val jsonObjects = splitTopLevelJsonObjects(trimmed) ?: return null
        if (jsonObjects.isEmpty()) {
            return null
        }
        val parsedToolCalls = jsonObjects.mapNotNull(::parseToolCallJson)
        if (parsedToolCalls.size != jsonObjects.size) {
            return null
        }
        return ParsedToolCalls(
            toolCalls = parsedToolCalls,
            textWithoutToolCalls = "",
        )
    }

    private fun splitTopLevelJsonObjects(text: String): List<String>? {
        val objects = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var objectStart = -1

        text.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            when (char) {
                '\\' -> if (inString) {
                    escaped = true
                }

                '"' -> inString = !inString

                '{' -> if (!inString) {
                    if (depth == 0) {
                        objectStart = index
                    }
                    depth += 1
                }

                '}' -> if (!inString) {
                    if (depth == 0) {
                        return null
                    }
                    depth -= 1
                    if (depth == 0) {
                        if (objectStart < 0) {
                            return null
                        }
                        objects += text.substring(objectStart, index + 1)
                        objectStart = -1
                    }
                }

                else -> if (depth == 0 && !char.isWhitespace()) {
                    return null
                }
            }
        }

        if (depth != 0 || inString) {
            return null
        }
        return objects
    }

    private fun parseToolCallJson(jsonText: String): InteractionToolCall? {
        return runCatching {
            val obj = json.parseToJsonElement(jsonText).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val arguments = obj["arguments"]
            val argumentsJson = when (arguments) {
                is JsonObject -> arguments.toString()
                else -> arguments?.jsonPrimitive?.content ?: "{}"
            }
            InteractionToolCall(
                id = "tc-${toolCallSequence.incrementAndGet()}-${name.hashCode().toUInt()}",
                name = name,
                argumentsJson = argumentsJson,
            )
        }.getOrNull()
    }

    /**
     * Renders tool definitions as XML for injection into the system prompt.
     * This format is compatible with SmolLM3's tool calling convention.
     */
    fun renderToolDefinitionsXml(
        toolNames: List<String>,
        openTag: String = DEFAULT_OPEN_TAG,
        closeTag: String = DEFAULT_CLOSE_TAG,
    ): String {
        if (toolNames.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("Available tools:")
            appendLine("<tools>")
            toolNames.forEach { name ->
                val schema = TOOL_SCHEMAS[name]
                if (schema != null) {
                    appendLine(schema)
                }
            }
            appendLine("</tools>")
            appendLine()
            append("Call tools only when needed. Emit $openTag{\"name\":\"tool\",\"arguments\":{...}}$closeTag.")
        }
    }

    private val TOOL_SCHEMAS = mapOf(
        "calculator" to """<tool>{"name":"calculator","args":["expression"]}</tool>""",
        "date_time" to """<tool>{"name":"date_time","args":[]}</tool>""",
        "notes_lookup" to """<tool>{"name":"notes_lookup","args":["query"]}</tool>""",
        "local_search" to """<tool>{"name":"local_search","args":["query"]}</tool>""",
        "reminder_create" to """<tool>{"name":"reminder_create","args":["title"]}</tool>""",
    )
}
