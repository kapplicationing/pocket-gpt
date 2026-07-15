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
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidLocalToolRuntimeTest {
    @Test
    fun `lists bounded voice tools alongside base tools`() {
        val runtime = AndroidLocalToolRuntime(
            context = TestToolRuntimeContext(),
            base = RecordingToolModule(),
        )

        assertContentEquals(
            listOf(
                AndroidLocalToolRuntime.TOOL_ALARM_SET,
                AndroidLocalToolRuntime.TOOL_APP_OPEN,
                "calculator",
                AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE,
                AndroidLocalToolRuntime.TOOL_TIMER_SET,
                AndroidLocalToolRuntime.TOOL_VOLUME_SET,
            ),
            runtime.listEnabledTools(),
        )
    }

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

        val result = runtime.executeToolCall(
            ToolCall(
                name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                jsonArgs = """{"duration_seconds":""",
            ),
        )

        assertFalse(result.success)
        assertEquals("invalid_voice_action", result.validationErrorCode)
        assertEquals("The action arguments were not valid JSON.", result.content)
    }

    @Test
    fun `typed custom execution returns invalid json for nested payload`() {
        val runtime = AndroidLocalToolRuntime(context = TestToolRuntimeContext())

        val result = runtime.executeToolRequest(
            ToolCallRequest(
                name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                arguments = ToolArguments(
                    mapOf("duration_seconds" to JsonObject(mapOf("value" to JsonPrimitive("30")))),
                ),
            ),
        )

        assertFalse(result.success)
        assertEquals("invalid_voice_action", result.validationErrorCode)
        assertEquals("Phone actions only accept simple values.", result.content)
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

    @Test
    fun `raw valid phone action execution is blocked without an authorized plan`() {
        val deviceActions = RecordingDeviceActions()
        val runtime = AndroidLocalToolRuntime(
            deviceActions = deviceActions,
            testBoundary = Unit,
        )

        val result = runtime.executeToolCall(
            ToolCall(
                name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                jsonArgs = """{"duration_seconds":"30"}""",
            ),
        )

        assertFalse(result.success)
        assertEquals("voice_action_not_authorized", result.validationErrorCode)
        assertNull(deviceActions.executedTool)
    }

    @Test
    fun `authorized validated phone action executes once through device boundary`() {
        val deviceActions = RecordingDeviceActions()
        val runtime = AndroidLocalToolRuntime(
            deviceActions = deviceActions,
            testBoundary = Unit,
        )
        val call = ToolCall(
            name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
            jsonArgs = """{"duration_seconds":"30"}""",
        )

        val result = VoiceActionExecutionAuthorization.runAuthorized(call.name, call.jsonArgs) {
            runtime.executeToolCall(call)
        }

        assertTrue(result.success)
        assertEquals(AndroidLocalToolRuntime.TOOL_TIMER_SET, deviceActions.executedTool)
        assertEquals("30", deviceActions.executedArguments?.get("duration_seconds"))
        assertEquals("PocketAgent timer", deviceActions.executedArguments?.get("label"))
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

private class RecordingDeviceActions : AndroidDeviceActions {
    var executedTool: String? = null
    var executedArguments: Map<String, String>? = null

    override fun execute(toolName: String, arguments: Map<String, String>): ToolResult {
        executedTool = toolName
        executedArguments = arguments
        return ToolResult(true, "executed")
    }
}
