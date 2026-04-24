package com.pocketagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLocalToolRuntimeTest {
    private val runtime = SafeLocalToolRuntime()
    private val expectedValidationCodes = setOf(
        "NOT_ALLOWLISTED",
        "INVALID_JSON",
        "MISSING_REQUIRED_FIELD",
        "UNKNOWN_FIELD",
        "INVALID_FIELD_TYPE",
        "INVALID_FIELD_VALUE",
    )

    @Test
    fun `calculator tool executes valid expression`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":\"4+5\"}"))
        assertTrue(result.success)
        assertEquals("9.0", result.content)
    }

    @Test
    fun `rejects malformed json with deterministic contract`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":"))
        assertValidationError(
            result = result,
            expectedCode = "INVALID_JSON",
            expectedDetail = "Payload must be valid JSON object text.",
        )
    }

    @Test
    fun `rejects schema-invalid payload type deterministically`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":123}"))
        assertValidationError(
            result = result,
            expectedCode = "INVALID_FIELD_TYPE",
            expectedDetail = "Field 'expression' must be a string.",
        )
    }

    @Test
    fun `rejects unknown field bypass attempts`() {
        val result = runtime.executeToolCall(
            ToolCall(
                "local_search",
                "{\"query\":\"hello\",\"tool\":\"calculator\"}",
            ),
        )
        assertValidationError(
            result = result,
            expectedCode = "UNKNOWN_FIELD",
            expectedDetail = "Unknown field 'tool'.",
        )
    }

    @Test
    fun `rejects missing required fields`() {
        val result = runtime.executeToolCall(ToolCall("local_search", "{}"))
        assertValidationError(
            result = result,
            expectedCode = "MISSING_REQUIRED_FIELD",
            expectedDetail = "Missing required field 'query'.",
        )
    }

    @Test
    fun `non allowlisted tool cannot execute`() {
        val result = runtime.executeToolCall(ToolCall("shell_exec", "{\"command\":\"ls\"}"))
        assertValidationError(
            result = result,
            expectedCode = "NOT_ALLOWLISTED",
            expectedDetail = "Tool 'shell_exec' is not allowlisted.",
        )
        assertFalse(runtime.validateToolCall(ToolCall("shell_exec", "{\"command\":\"ls\"}")))
    }

    @Test
    fun `rejects adversarial fragments and escaped bypass payloads`() {
        val plain = runtime.executeToolCall(ToolCall("local_search", "{\"query\":\"exec rm -rf\"}"))
        val escaped = runtime.executeToolCall(
            ToolCall("local_search", "{\"query\":\"\\u0065\\u0078\\u0065\\u0063 whoami\"}"),
        )

        assertValidationError(
            result = plain,
            expectedCode = "INVALID_FIELD_VALUE",
            expectedDetail = "Field 'query' contains denied fragment 'exec'.",
        )
        assertValidationError(
            result = escaped,
            expectedCode = "INVALID_FIELD_VALUE",
            expectedDetail = "Field 'query' contains denied fragment 'exec'.",
        )
    }

    @Test
    fun `rejects nested-object bypass for string field`() {
        val result = runtime.executeToolCall(
            ToolCall("local_search", "{\"query\":{\"operator\":\"equals\",\"value\":\"hello\"}}"),
        )
        assertValidationError(
            result = result,
            expectedCode = "INVALID_FIELD_TYPE",
            expectedDetail = "Field 'query' must be a string.",
        )
    }

    @Test
    fun `validate tool call is true for schema-valid payload`() {
        assertTrue(runtime.validateToolCall(ToolCall("local_search", "{\"query\":\"hello\"}")))
    }

    @Test
    fun `typed request path preserves successful execution behavior`() {
        val result = runtime.executeToolRequest(
            ToolCallRequest(
                name = "calculator",
                arguments = ToolArguments(mapOf("expression" to JsonPrimitive("4+5"))),
            ),
        )

        assertTrue(result.success)
        assertEquals("9.0", result.content)
    }

    @Test
    fun `typed request path preserves schema validation semantics`() {
        val result = runtime.executeToolRequest(
            ToolCallRequest(
                name = "local_search",
                arguments = ToolArguments(
                    mapOf(
                        "query" to JsonObject(
                            mapOf(
                                "operator" to JsonPrimitive("equals"),
                                "value" to JsonPrimitive("hello"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertValidationError(
            result = result,
            expectedCode = "INVALID_FIELD_TYPE",
            expectedDetail = "Field 'query' must be a string.",
        )
    }

    @Test
    fun `notes lookup and local search return real store-backed results`() {
        val notesResult = runtime.executeToolCall(ToolCall("notes_lookup", "{\"query\":\"runtime gate\"}"))
        val searchResult = runtime.executeToolCall(ToolCall("local_search", "{\"query\":\"schema safety\"}"))

        assertTrue(notesResult.success)
        assertTrue(notesResult.content.startsWith("notes_lookup:"))
        assertFalse(notesResult.content.contains("placeholder"))

        assertTrue(searchResult.success)
        assertTrue(searchResult.content.startsWith("local_search:"))
        assertFalse(searchResult.content.contains("placeholder"))
    }

    @Test
    fun `reminder create persists deterministic local ids`() {
        val first = runtime.executeToolCall(ToolCall("reminder_create", "{\"title\":\"ship wp12 packet\"}"))
        val second = runtime.executeToolCall(ToolCall("reminder_create", "{\"title\":\"run qa closeout\"}"))

        assertTrue(first.success)
        assertTrue(second.success)
        assertTrue(first.content.contains("reminder_create:id=rem-1"))
        assertTrue(second.content.contains("reminder_create:id=rem-2"))
    }

    @Test
    fun `date_time rejects extra schema fields`() {
        val result = runtime.executeToolCall(ToolCall("date_time", "{\"timezone\":\"UTC\"}"))
        assertValidationError(
            result = result,
            expectedCode = "UNKNOWN_FIELD",
            expectedDetail = "Unknown field 'timezone'.",
        )
    }

    @Test
    fun `validation error contract shape and code set remain stable`() {
        val scenarios = listOf(
            ToolCall("shell_exec", "{\"command\":\"ls\"}") to "NOT_ALLOWLISTED",
            ToolCall("calculator", "{\"expression\":") to "INVALID_JSON",
            ToolCall("local_search", "{}") to "MISSING_REQUIRED_FIELD",
            ToolCall("date_time", "{\"timezone\":\"UTC\"}") to "UNKNOWN_FIELD",
            ToolCall("calculator", "{\"expression\":123}") to "INVALID_FIELD_TYPE",
            ToolCall("local_search", "{\"query\":\"exec rm -rf\"}") to "INVALID_FIELD_VALUE",
        )
        val observedCodes = mutableSetOf<String>()

        scenarios.forEach { (call, expectedCode) ->
            val parsed = parseValidationError(runtime.executeToolCall(call))
            assertEquals(expectedCode, parsed.code)
            observedCodes += parsed.code
        }

        assertEquals(expectedValidationCodes, observedCodes)
    }

    private fun assertValidationError(
        result: ToolResult,
        expectedCode: String,
        expectedDetail: String,
    ) {
        val parsed = parseValidationError(result)
        assertEquals(expectedCode, parsed.code)
        assertEquals(expectedDetail, parsed.detail)
    }

    private fun parseValidationError(result: ToolResult): ValidationErrorParts {
        assertFalse(result.success)
        val parts = result.content.split(":", limit = 3)
        assertEquals(3, parts.size)
        assertEquals("TOOL_VALIDATION_ERROR", parts[0])
        val code = parts[1]
        val detail = parts[2]
        assertTrue(expectedValidationCodes.contains(code), "Unexpected validation code: $code")
        assertTrue(detail.isNotBlank(), "Validation error detail must be non-blank.")
        return ValidationErrorParts(code = code, detail = detail)
    }

    private data class ValidationErrorParts(
        val code: String,
        val detail: String,
    )
}
