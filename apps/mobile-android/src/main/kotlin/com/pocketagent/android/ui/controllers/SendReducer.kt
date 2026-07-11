package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.clearError
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamPhase

class SendReducer {
    fun onSendStarted(
        runtime: RuntimeUiState,
        toolDriven: Boolean,
    ): RuntimeUiState {
        val base = runtime.copy(
            sendElapsedMs = 0L,
            sendSlowState = null,
        ).clearError()
        if (toolDriven) {
            return base
        }
        return base.copy(
            modelRuntimeStatus = ModelRuntimeStatus.LOADING,
            modelStatusDetail = "Preparing request...",
        )
    }

    fun statusDetailForEvent(event: ChatStreamEvent): String? {
        return when (event) {
            is ChatStreamEvent.Started -> "Preparing request..."
            is ChatStreamEvent.Phase -> event.phase.statusDetail()
            is ChatStreamEvent.Delta -> "Generating..."
            is ChatStreamEvent.Thinking -> if (event.active) "Thinking..." else "Generating..."
            is ChatStreamEvent.Completed -> "Completed"
            is ChatStreamEvent.Interrupted -> "Response interrupted"
            is ChatStreamEvent.Cancelled -> "Cancelled"
            is ChatStreamEvent.Failed -> "Runtime error"
        }
    }
}

private fun ChatStreamPhase.statusDetail(): String {
    return when (this) {
        ChatStreamPhase.CHAT_START -> "Preparing request..."
        ChatStreamPhase.MODEL_LOAD -> "Loading model..."
        ChatStreamPhase.PROMPT_PROCESSING -> "Prefill..."
        ChatStreamPhase.TOKEN_STREAM -> "Generating..."
        ChatStreamPhase.CHAT_END -> "Finalizing..."
        ChatStreamPhase.ERROR -> "Runtime error"
    }
}
