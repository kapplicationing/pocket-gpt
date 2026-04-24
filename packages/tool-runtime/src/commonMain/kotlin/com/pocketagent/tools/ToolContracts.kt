package com.pocketagent.tools

data class ToolCall(
    val name: String,
    val jsonArgs: String,
)

data class ToolResult(
    val success: Boolean,
    val content: String,
    val validationErrorCode: String? = null,
    val validationErrorDetail: String? = null,
)

interface ToolModule {
    fun listEnabledTools(): List<String>
    fun validateToolCall(call: ToolCall): Boolean
    fun executeToolCall(call: ToolCall): ToolResult

    fun validateToolRequest(request: ToolCallRequest): Boolean = validateToolCall(request.toLegacyCall())

    fun executeToolRequest(request: ToolCallRequest): ToolResult = executeToolCall(request.toLegacyCall())
}
