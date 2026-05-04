package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.DEFAULT_CHAT_MAX_TOKENS
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSendFlowTest {
    @Test
    fun `prepare chat stream keeps default quick chat token cap bounded`() {
        val sendFlow = ChatSendFlow(runtimeGenerationTimeoutMs = 180_000L)
        val prepared = sendFlow.prepareChatStream(
            sessionId = SessionId("session-1"),
            requestId = "req-1",
            messages = listOf(userMessage("Reply with exactly: OK.")),
            promptHint = "Reply with exactly: OK.",
            previousResponseId = null,
            runtime = RuntimeUiState(activeModelId = "qwen3.5-0.8b-q4"),
            completionSettings = CompletionSettings(),
            prepare = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 180_000L)::prepare,
        )

        assertEquals(DEFAULT_CHAT_MAX_TOKENS, prepared.preparedStream.runtimeRequest.maxTokens)
    }

    @Test
    fun `prepare chat stream preserves explicit max token override`() {
        val sendFlow = ChatSendFlow(runtimeGenerationTimeoutMs = 180_000L)
        val prepared = sendFlow.prepareChatStream(
            sessionId = SessionId("session-1"),
            requestId = "req-1",
            messages = listOf(userMessage("Explain compactly.")),
            promptHint = "Explain compactly.",
            previousResponseId = null,
            runtime = RuntimeUiState(activeModelId = "qwen3.5-0.8b-q4"),
            completionSettings = CompletionSettings(maxTokens = 192),
            prepare = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 180_000L)::prepare,
        )

        assertEquals(192, prepared.preparedStream.runtimeRequest.maxTokens)
    }

    private fun userMessage(text: String): InteractionMessage {
        return InteractionMessage(
            id = "user-1",
            role = InteractionRole.USER,
            parts = listOf(InteractionContentPart.Text(text)),
        )
    }
}
