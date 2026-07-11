package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamInterruptionReason
import com.pocketagent.runtime.ChatStreamPlan
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatStreamCoordinatorTest {
    @Test
    fun `collect stream emits terminal completed state`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello "),
                        ),
                    )
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "hello",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 2L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var beforeTerminalCalls = 0
        var terminalState: StreamTerminalState? = null
        var tokenEvents = 0

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-complete", 200L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { event, _: StreamReducerState ->
                if (event is ChatStreamEvent.Delta) {
                    tokenEvents += 1
                }
            },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { beforeTerminalCalls += 1 },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(1, tokenEvents)
        assertEquals(1, beforeTerminalCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
    }

    @Test
    fun `collect stream timeout cancels request and emits timeout terminal`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    awaitCancellation()
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-timeout", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertTrue(runtime.cancelByRequestCalls > 0)
        assertNotNull(terminalState)
        assertEquals("timeout", terminalState?.finishReason)
        assertEquals("UI-RUNTIME-001", terminalState?.uiError?.code)
        assertEquals(true, terminalState?.terminalEventSeen)
    }

    @Test
    fun `collect stream does not timeout after first token`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello "),
                        ),
                    )
                    delay(80L)
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "hello world",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 90L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-post-token", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(0, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
    }

    @Test
    fun `collect stream does not preempt completed terminal on first idle observation after deltas`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello "),
                        ),
                    )
                    delay(80L)
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "hello world",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 90L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
            activeGenerationCountProvider = { 0 },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-post-token-idle-race", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(0, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
        assertEquals(true, terminalState?.terminalEventSeen)
    }

    @Test
    fun `collect stream times out when only thinking is emitted past visible token deadline`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(ChatStreamEvent.Thinking(requestId = request.requestId, active = true))
                    awaitCancellation()
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-thinking", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertTrue(runtime.cancelByRequestCalls > 0)
        assertNotNull(terminalState)
        assertEquals("timeout", terminalState?.finishReason)
    }

    @Test
    fun `collect stream accepts short thinking phase before completed response`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(ChatStreamEvent.Thinking(requestId = request.requestId, active = true))
                    delay(20L)
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "final answer",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 25L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-thinking-short", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(0, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
    }

    @Test
    fun `parent cancellation propagates without terminal callbacks`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    awaitCancellation()
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        var beforeTerminalCalls = 0
        var terminalCalls = 0

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(25L) {
                coordinator.collectStream(
                    runtimeService = runtime,
                    preparedStream = preparedRequest("req-parent-cancel", 1_000L),
                    streamReducer = StreamStateReducer(requestTimeoutMs = 1_000L),
                    sendStartedAtMs = System.currentTimeMillis(),
                    onEvent = { _, _ -> },
                    onElapsed = { _, _ -> },
                    onBeforeTerminal = { beforeTerminalCalls += 1 },
                    onTerminal = { terminalCalls += 1 },
                )
            }
        }

        assertEquals(0, beforeTerminalCalls)
        assertEquals(0, terminalCalls)
        assertEquals(0, runtime.cancelByRequestCalls)
    }

    @Test
    fun `collect stream accepts legacy blank request ids`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(requestId = "", startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = "",
                            delta = ChatStreamDelta.TextDelta("legacy "),
                        ),
                    )
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = "",
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "legacy response",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 2L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-legacy", 200L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
        assertEquals("legacy response", terminalState?.responseText)
    }

    @Test
    fun `collect stream emits typed interruption when stream closes after deltas`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello "),
                        ),
                    )
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("world"),
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var terminalState: StreamTerminalState? = null
        var interruptedEvent: ChatStreamEvent.Interrupted? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-synth-complete", 200L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { event, _ ->
                if (event is ChatStreamEvent.Interrupted) interruptedEvent = event
            },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertNotNull(terminalState)
        assertEquals("interrupted:closed_without_terminal", terminalState?.finishReason)
        assertEquals(false, terminalState?.terminalEventSeen)
        assertEquals("hello world", terminalState?.responseText)
        assertEquals("stream_closed_without_terminal", terminalState?.errorCode)
        assertNotNull(terminalState?.uiError)
        assertEquals(ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL, interruptedEvent?.reason)
        assertEquals("hello world", interruptedEvent?.partialText)
    }

    @Test
    fun `collect stream emits typed interruption when runtime idles after deltas`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello "),
                        ),
                    )
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("world"),
                        ),
                    )
                    awaitCancellation()
                }
            },
            activeGenerationCountProvider = { 0 },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null
        var interruptedEvent: ChatStreamEvent.Interrupted? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-hung-complete", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { event, _ ->
                if (event is ChatStreamEvent.Interrupted) interruptedEvent = event
            },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(1, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("interrupted:stalled_without_terminal", terminalState?.finishReason)
        assertEquals(false, terminalState?.terminalEventSeen)
        assertEquals("hello world", terminalState?.responseText)
        assertEquals("stream_stalled_without_terminal", terminalState?.errorCode)
        assertNotNull(terminalState?.uiError)
        assertEquals(ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL, interruptedEvent?.reason)
    }

    @Test
    fun `collect stream emits typed interruption when active runtime stalls after visible text`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(
                        ChatStreamEvent.Delta(
                            requestId = request.requestId,
                            delta = ChatStreamDelta.TextDelta("hello world"),
                        ),
                    )
                    awaitCancellation()
                }
            },
            activeGenerationCountProvider = { 1 },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null
        var interruptedEvent: ChatStreamEvent.Interrupted? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-stuck-active-after-token", 30L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { event, _ ->
                if (event is ChatStreamEvent.Interrupted) interruptedEvent = event
            },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(1, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("interrupted:stalled_without_terminal", terminalState?.finishReason)
        assertEquals(false, terminalState?.terminalEventSeen)
        assertEquals("hello world", terminalState?.responseText)
        assertEquals(ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL, interruptedEvent?.reason)
    }

    @Test
    fun `collect stream synthesizes runtime error when stream closes before any terminal or text`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-synth-error", 200L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertNotNull(terminalState)
        assertEquals("runtime_error", terminalState?.finishReason)
        assertEquals(false, terminalState?.terminalEventSeen)
        assertEquals("UI-RUNTIME-001", terminalState?.uiError?.code)
    }

    @Test
    fun `collect stream thrown failure cancels request by request id`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    error("stream exploded")
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeService = runtime,
            preparedStream = preparedRequest("req-failure", 200L),
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(1, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("runtime_error", terminalState?.finishReason)
    }
}

private fun request(requestId: String): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = SessionId("session-1"),
        requestId = requestId,
        messages = listOf(
            InteractionMessage(
                role = InteractionRole.USER,
                parts = listOf(InteractionContentPart.Text("hello")),
            ),
        ),
        taskType = "short_text",
        deviceState = DeviceState(80, 3, 8),
    )
}

private fun preparedRequest(requestId: String, timeoutMs: Long): PreparedChatStream {
    val request = request(requestId).copy(requestTimeoutMs = timeoutMs)
    return PreparedChatStream(
        plan = ChatStreamPlan(
            requestId = requestId,
            requestTimeoutMs = timeoutMs,
            baseConfig = request.performanceConfig,
            effectiveConfig = request.performanceConfig,
        ),
        runtimeRequest = request,
    )
}

private class FlowRuntimeGateway(
    private val flowFactory: (StreamChatRequestV2) -> Flow<ChatStreamEvent>,
    private val activeGenerationCountProvider: (() -> Int)? = null,
) : ChatRuntimeService {
    var cancelByRequestCalls: Int = 0
    private val activeFlowCount = AtomicInteger(0)

    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> {
        activeFlowCount.incrementAndGet()
        return flowFactory(prepared.runtimeRequest)
            .onCompletion { activeFlowCount.decrementAndGet() }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelByRequestCalls += 1
        return true
    }

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false

    override fun activeGenerationCount(): Int = activeGenerationCountProvider?.invoke() ?: activeFlowCount.get()
}
