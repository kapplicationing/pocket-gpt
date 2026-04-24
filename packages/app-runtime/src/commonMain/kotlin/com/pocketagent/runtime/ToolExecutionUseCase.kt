package com.pocketagent.runtime

import com.pocketagent.core.PolicyModule
import com.pocketagent.tools.ToolCallRequest

internal class ToolExecutionUseCase(
    private val policyModule: PolicyModule,
    private val toolLoopCoordinator: ToolLoopCoordinator,
) {
    fun execute(
        toolName: String,
        jsonArgs: String,
    ): ToolExecutionResult {
        if (!policyModule.enforceDataBoundary("tool.execute")) {
            return ToolExecutionResult.Failure(
                failure = ToolFailure.PolicyDenied(
                    technicalDetail = "Policy module rejected tool event type.",
                ),
            )
        }
        val result = toolLoopCoordinator.executeToolCall(toolName = toolName, jsonArgs = jsonArgs)
        return mapToolResult(result)
    }

    fun execute(request: ToolCallRequest): ToolExecutionResult {
        if (!policyModule.enforceDataBoundary("tool.execute")) {
            return ToolExecutionResult.Failure(
                failure = ToolFailure.PolicyDenied(
                    technicalDetail = "Policy module rejected tool event type.",
                ),
            )
        }
        val result = toolLoopCoordinator.executeToolCall(request)
        return mapToolResult(result)
    }

    private fun mapToolResult(result: InteractionToolExecutionResult): ToolExecutionResult {
        if (result.success) {
            return ToolExecutionResult.Success(result.content)
        }
        if (!result.validationErrorCode.isNullOrBlank()) {
            return ToolExecutionResult.Failure(
                failure = ToolFailure.Validation(
                    code = result.validationErrorCode.lowercase(),
                    userMessage = "That tool request was rejected for safety.",
                    technicalDetail = result.validationErrorDetail ?: result.content,
                ),
            )
        }
        if (result.content.startsWith("TOOL_VALIDATION_ERROR:")) {
            return ToolExecutionResult.fromLegacy(result.content)
        }
        return ToolExecutionResult.Failure(
            failure = ToolFailure.Execution(
                code = "tool_runtime_error",
                userMessage = "Tool request failed.",
                technicalDetail = result.content,
            ),
        )
    }
}
