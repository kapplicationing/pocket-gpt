package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.DEFAULT_CHAT_MAX_TOKENS
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSendFlowTest {
    @Test
    fun `prepare chat stream keeps default quick chat token cap bounded`() = runTest {
        val sendFlow = ChatSendFlow(
            runtimeGenerationTimeoutMs = 180_000L,
            preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
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
    fun `prepare chat stream preserves explicit max token override`() = runTest {
        val sendFlow = ChatSendFlow(
            runtimeGenerationTimeoutMs = 180_000L,
            preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
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

    @Test
    fun `prepare chat stream runs telemetry and runtime preparation on injected dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "chat-send-preparation-test")
        }
        val preparationDispatcher = executor.asCoroutineDispatcher()
        try {
            var deviceStateThread: String? = null
            var runtimePreparationThread: String? = null
            val sendFlow = ChatSendFlow(
                runtimeGenerationTimeoutMs = 180_000L,
                deviceStateProvider = DeviceStateProvider {
                    deviceStateThread = Thread.currentThread().name
                    com.pocketagent.inference.DeviceState(
                        batteryPercent = 80,
                        thermalLevel = 2,
                        ramClassGb = 8,
                    )
                },
                preparationDispatcher = preparationDispatcher,
            )

            runBlocking {
                sendFlow.prepareChatStream(
                    sessionId = SessionId("session-1"),
                    requestId = "req-1",
                    messages = listOf(userMessage("Hello")),
                    promptHint = "Hello",
                    previousResponseId = null,
                    runtime = RuntimeUiState(activeModelId = "qwen3.5-0.8b-q4"),
                    prepare = { command ->
                        runtimePreparationThread = Thread.currentThread().name
                        ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 180_000L).prepare(command)
                    },
                )
            }

            assertEquals("chat-send-preparation-test", deviceStateThread?.substringBefore(" @"))
            assertEquals("chat-send-preparation-test", runtimePreparationThread?.substringBefore(" @"))
        } finally {
            preparationDispatcher.close()
        }
    }

    private fun userMessage(text: String): InteractionMessage {
        return InteractionMessage(
            id = "user-1",
            role = InteractionRole.USER,
            parts = listOf(InteractionContentPart.Text(text)),
        )
    }
}
