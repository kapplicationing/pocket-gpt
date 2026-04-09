package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMvpRuntimeFacadeTest {
    @Test
    fun `stream chat emits delta and completed events`() = runTest {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2()

        val events = facade.streamChat(request).toList()
        val tokenEvents = events.filterIsInstance<ChatStreamEvent.Delta>()
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        val phases = events.filterIsInstance<ChatStreamEvent.Phase>().map { phase -> phase.phase }.toSet()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals(2, tokenEvents.size)
        assertEquals(ChatStreamDelta.TextDelta("hello "), tokenEvents[0].delta)
        assertEquals("hello ", tokenEvents[0].accumulatedText)
        assertEquals(ChatStreamDelta.TextDelta("world "), tokenEvents[1].delta)
        assertEquals("hello world ", tokenEvents[1].accumulatedText)
        assertEquals("response", completed.response.text)
        assertTrue(phases.contains(ChatStreamPhase.CHAT_START))
        assertTrue(phases.contains(ChatStreamPhase.MODEL_LOAD))
        assertTrue(phases.contains(ChatStreamPhase.PROMPT_PROCESSING))
        assertTrue(phases.contains(ChatStreamPhase.TOKEN_STREAM))
        assertTrue(phases.contains(ChatStreamPhase.CHAT_END))
        assertEquals("hello", container.lastUserText)
        assertEquals(64, container.lastMaxTokens)
    }

    @Test
    fun `delegates runtime operations to container`() {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)

        assertEquals("session-1", facade.createSession().value)
        assertEquals("tool:calculator", facade.runTool("calculator", """{"expression":"1+2"}"""))
        assertEquals("image:/tmp/a.jpg", facade.analyzeImage("/tmp/a.jpg", "describe"))
        assertEquals("diag=ok", facade.exportDiagnostics())
        facade.setRoutingMode(RoutingMode.QWEN3_1_7B)
        assertEquals(RoutingMode.QWEN3_1_7B, facade.getRoutingMode())
        assertEquals(listOf("check"), facade.runStartupChecks())

        val turns = listOf(Turn(role = "user", content = "hello", timestampEpochMs = 1))
        facade.restoreSession(SessionId("session-1"), turns)
        assertEquals(1, container.restoreCalls)
        assertTrue(facade.deleteSession(SessionId("session-1")))
        assertEquals(1, container.deleteCalls)
    }

    @Test
    fun `stream chat emits cancelled event on runtime cancellation`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationCancelledException(requestId = "req-cancel")
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-cancel")

        val events = facade.streamChat(request).toList()
        val cancelled = events.filterIsInstance<ChatStreamEvent.Cancelled>().single()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals("cancelled", cancelled.reason)
    }

    @Test
    fun `stream chat emits failed event with bridge error code`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "utf8 stream failure",
                errorCode = "JNI_UTF8_STREAM_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-fail")

        val events = facade.streamChat(request).toList()
        val failed = events.filterIsInstance<ChatStreamEvent.Failed>().single()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals("jni_utf8_stream_error", failed.errorCode)
    }

    @Test
    fun `terminal failure does not trigger duplicate cancel from awaitClose`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "runtime failure",
                errorCode = "JNI_RUNTIME_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-fail-nocancel")

        val events = facade.streamChat(request).toList()

        assertTrue(events.any { it is ChatStreamEvent.Failed })
        assertEquals(0, container.cancelByRequestCalls)
        assertEquals(0, container.cancelBySessionCalls)
    }
}

private fun requestV2(
    requestId: String = "req-1",
    sessionId: SessionId = SessionId("session-1"),
): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = sessionId,
        requestId = requestId,
        messages = listOf(
            InteractionMessage(
                role = InteractionRole.USER,
                parts = listOf(InteractionContentPart.Text("hello")),
            ),
        ),
        taskType = "short_text",
        deviceState = DeviceState(80, 3, 8),
        maxTokens = 64,
    )
}

private class FakeRuntimeContainer : RuntimeContainer {
    private var currentRoutingMode: RoutingMode = RoutingMode.AUTO
    var lastUserText: String = ""
    var lastMaxTokens: Int = 0
    var restoreCalls: Int = 0
    var deleteCalls: Int = 0
    var sendError: Throwable? = null
    var cancelByRequestCalls: Int = 0
    var cancelBySessionCalls: Int = 0

    override fun createSession(): SessionId = SessionId("session-1")

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        previousResponseId: String?,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        sendError?.let { throw it }
        lastUserText = messages.lastOrNull { it.role == InteractionRole.USER }
            ?.parts
            ?.filterIsInstance<InteractionContentPart.Text>()
            ?.joinToString(separator = "\n") { it.text }
            .orEmpty()
        lastMaxTokens = maxTokens
        onToken("hello ")
        onToken("world ")
        return ChatResponse(
            sessionId = sessionId,
            modelId = "auto",
            text = "response",
            firstTokenLatencyMs = 42,
            totalLatencyMs = 75,
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        currentRoutingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = currentRoutingMode

    override fun runStartupChecks(): List<String> = listOf("check")

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        cancelBySessionCalls += 1
        return true
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelByRequestCalls += 1
        return true
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoreCalls += 1
    }

    override fun deleteSession(sessionId: SessionId): Boolean {
        deleteCalls += 1
        return true
    }
}
