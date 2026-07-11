package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.CancellationException

sealed interface ToolLoopOutcome {
    data class Success(val content: String) : ToolLoopOutcome
    data class Failure(val uiError: UiError) : ToolLoopOutcome
}

class ToolLoopUseCase(
    private val sendController: ChatSendController,
) {
    suspend fun execute(
        toolName: String,
        jsonArgs: String,
    ): ToolLoopOutcome {
        return runCatching {
            sendController.runTool(toolName = toolName, jsonArgs = jsonArgs)
        }.fold(
            onSuccess = { result ->
                val mappedError = UiErrorMapper.fromToolResult(result)
                if (mappedError != null) {
                    ToolLoopOutcome.Failure(mappedError)
                } else {
                    ToolLoopOutcome.Success(result.contentOrDetail())
                }
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    throw error
                }
                ToolLoopOutcome.Failure(
                    UiErrorMapper.runtimeFailure(error.message ?: "Tool request failed."),
                )
            },
        )
    }
}

private fun ToolExecutionResult.contentOrDetail(): String {
    return when (this) {
        is ToolExecutionResult.Success -> content
        is ToolExecutionResult.Failure -> failure.technicalDetail ?: failure.userMessage
    }
}
