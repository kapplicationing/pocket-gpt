package com.pocketagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRequestContractsTest {
    @Test
    fun `fromLegacy parses typed arguments without losing json shape`() {
        val parsed = ToolCallRequest.fromLegacy(
            name = "calculator",
            jsonArgs = """{"expression":"4+5","flags":["fast"],"meta":{"source":"model"}}""",
        )

        val request = assertIs<ToolCallRequestParseResult.Success>(parsed).request
        assertEquals("calculator", request.name)
        assertEquals("4+5", request.arguments.getString("expression"))
        assertIs<JsonArray>(request.arguments["flags"])
        assertIs<JsonObject>(request.arguments["meta"])
    }

    @Test
    fun `typed request round-trips back to legacy ToolCall`() {
        val request = ToolCallRequest(
            name = "notes_lookup",
            arguments = ToolArguments(
                mapOf(
                    "query" to JsonPrimitive("runtime gate"),
                ),
            ),
        )

        val legacy = request.toLegacyCall()

        assertEquals("notes_lookup", legacy.name)
        assertTrue(legacy.jsonArgs.contains("runtime gate"))
    }

    @Test
    fun `fromLegacy surfaces invalid json without inventing typed arguments`() {
        val parsed = ToolCallRequest.fromLegacy(name = "calculator", jsonArgs = """{"expression":""")

        val invalid = assertIs<ToolCallRequestParseResult.InvalidJson>(parsed)
        assertEquals("calculator", invalid.name)
        assertEquals("""{"expression":""", invalid.rawJsonArgs)
    }

    @Test
    fun `tool arguments coerce flat primitive objects into strings`() {
        val request = assertIs<ToolCallRequestParseResult.Success>(
            ToolCallRequest.fromLegacy(
                name = "alarm_set",
                jsonArgs = """{"title":"Wake up","time_iso":"2026-04-25T08:00:00"}""",
            ),
        ).request

        assertEquals(
            mapOf(
                "title" to "Wake up",
                "time_iso" to "2026-04-25T08:00:00",
            ),
            request.arguments.toPrimitiveStringMapOrNull(),
        )
    }

    @Test
    fun `tool arguments reject nested values while preserving number coercion`() {
        val nested = assertIs<ToolCallRequestParseResult.Success>(
            ToolCallRequest.fromLegacy(
                name = "timer_set",
                jsonArgs = """{"nested":{"value":"x"}}""",
            ),
        ).request
        val numeric = assertIs<ToolCallRequestParseResult.Success>(
            ToolCallRequest.fromLegacy(
                name = "timer_set",
                jsonArgs = """{"duration_seconds":30}""",
            ),
        ).request

        assertNull(nested.arguments.toPrimitiveStringMapOrNull())
        assertEquals(
            mapOf("duration_seconds" to "30"),
            numeric.arguments.toPrimitiveStringMapOrNull(),
        )
    }
}
