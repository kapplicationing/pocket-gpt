package com.pocketagent.android.ui.contracts

import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ChatStreamDelta

sealed interface ChatCommand {
    data class SendMessage(val text: String) : ChatCommand
    data class Cancel(val requestId: String? = null) : ChatCommand
    data class RunTool(val toolName: String, val jsonArgs: String) : ChatCommand
    data class AnalyzeImage(val imagePath: String, val prompt: String) : ChatCommand
}

sealed interface ChatEvent {
    data class Started(val requestId: String, val startedAtEpochMs: Long) : ChatEvent
    data class Delta(val requestId: String, val delta: ChatStreamDelta) : ChatEvent
    data class Completed(val requestId: String, val text: String, val finishReason: String) : ChatEvent
    data class Failed(val requestId: String, val code: String, val message: String) : ChatEvent
    data class Cancelled(val requestId: String, val reason: String) : ChatEvent
    data class ToolCompleted(val result: ToolExecutionResult) : ChatEvent
    data class ImageCompleted(val result: ImageAnalysisResult) : ChatEvent
}
