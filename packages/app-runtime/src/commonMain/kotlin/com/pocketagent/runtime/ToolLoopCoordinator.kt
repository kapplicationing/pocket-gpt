package com.pocketagent.runtime

import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolCallRequestParseResult
import com.pocketagent.tools.ToolModule

class ToolLoopCoordinator(
    private val toolModule: ToolModule,
) {
    fun executeToolCall(toolName: String, jsonArgs: String): InteractionToolExecutionResult {
        val result = when (val request = ToolCallRequest.fromLegacy(name = toolName, jsonArgs = jsonArgs)) {
            is ToolCallRequestParseResult.Success -> toolModule.executeToolRequest(request.request)
            is ToolCallRequestParseResult.InvalidJson -> toolModule.executeToolCall(ToolCall(toolName, jsonArgs))
        }
        return executionResult(toolName = toolName, result = result)
    }

    fun executeToolCall(request: ToolCallRequest): InteractionToolExecutionResult {
        val result = toolModule.executeToolRequest(request)
        return executionResult(toolName = request.name, result = result)
    }

    private fun executionResult(
        toolName: String,
        result: com.pocketagent.tools.ToolResult,
    ): InteractionToolExecutionResult {
        return InteractionToolExecutionResult(
            success = result.success,
            content = result.content,
            validationErrorCode = result.validationErrorCode,
            validationErrorDetail = result.validationErrorDetail,
            message = InteractionMessage(
                id = "tool-${System.currentTimeMillis()}",
                role = InteractionRole.TOOL,
                toolCallId = "tool-${System.currentTimeMillis()}",
                parts = listOf(InteractionContentPart.Text(result.content)),
                metadata = mapOf("toolName" to toolName),
            ),
        )
    }
}

data class InteractionToolExecutionResult(
    val success: Boolean,
    val content: String,
    val validationErrorCode: String? = null,
    val validationErrorDetail: String? = null,
    val message: InteractionMessage,
)
