package com.pocketagent.android.voice

import android.content.Context
import android.content.ContextWrapper
import com.pocketagent.tools.ToolArguments
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolModule
import com.pocketagent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidLocalToolRuntimeTest {
    @Test
    fun `legacy custom validation preserves primitive coercion`() {
        val runtime = AndroidLocalToolRuntime(context = TestToolRuntimeContext())

        assertTrue(
            runtime.validateToolCall(
                ToolCall(
                    name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                    jsonArgs = """{"duration_seconds":30}""",
                ),
            ),
        )
        assertTrue(
            runtime.validateToolCall(
                ToolCall(
                    name = AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE,
                    jsonArgs = """{"enabled":true}""",
                ),
            ),
        )
    }

    @Test
    fun `custom validation rejects nested typed arguments`() {
        val runtime = AndroidLocalToolRuntime(context = TestToolRuntimeContext())

        assertFalse(
            runtime.validateToolRequest(
                ToolCallRequest(
                    name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                    arguments = ToolArguments(
                        mapOf("duration_seconds" to JsonObject(mapOf("value" to JsonPrimitive("30")))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `legacy custom execution returns invalid json for malformed payload`() {
        val runtime = AndroidLocalToolRuntime(context = TestToolRuntimeContext())

        assertEquals(
            ToolResult(false, "Invalid tool JSON."),
            runtime.executeToolCall(
                ToolCall(
                    name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                    jsonArgs = """{"duration_seconds":""",
                ),
            ),
        )
    }

    @Test
    fun `typed custom execution returns invalid json for nested payload`() {
        val runtime = AndroidLocalToolRuntime(context = TestToolRuntimeContext())

        assertEquals(
            ToolResult(false, "Invalid tool JSON."),
            runtime.executeToolRequest(
                ToolCallRequest(
                    name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                    arguments = ToolArguments(
                        mapOf("duration_seconds" to JsonObject(mapOf("value" to JsonPrimitive("30")))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `typed non custom requests delegate directly to base runtime`() {
        val base = RecordingToolModule()
        val runtime = AndroidLocalToolRuntime(
            context = TestToolRuntimeContext(),
            base = base,
        )
        val request = ToolCallRequest(
            name = "calculator",
            arguments = ToolArguments(mapOf("expression" to JsonPrimitive("4+5"))),
        )

        assertTrue(runtime.validateToolRequest(request))
        assertEquals(ToolResult(true, "base-typed"), runtime.executeToolRequest(request))
        assertEquals(request, base.lastValidatedRequest)
        assertEquals(request, base.lastExecutedRequest)
        assertNull(base.lastValidatedCall)
        assertNull(base.lastExecutedCall)
    }
}

private class TestToolRuntimeContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

private class RecordingToolModule : ToolModule {
    var lastValidatedRequest: ToolCallRequest? = null
    var lastExecutedRequest: ToolCallRequest? = null
    var lastValidatedCall: ToolCall? = null
    var lastExecutedCall: ToolCall? = null

    override fun listEnabledTools(): List<String> = listOf("calculator")

    override fun validateToolCall(call: ToolCall): Boolean {
        lastValidatedCall = call
        return true
    }

    override fun executeToolCall(call: ToolCall): ToolResult {
        lastExecutedCall = call
        return ToolResult(true, "base-legacy")
    }

    override fun validateToolRequest(request: ToolCallRequest): Boolean {
        lastValidatedRequest = request
        return true
    }

    override fun executeToolRequest(request: ToolCallRequest): ToolResult {
        lastExecutedRequest = request
        return ToolResult(true, "base-typed")
    }
}
