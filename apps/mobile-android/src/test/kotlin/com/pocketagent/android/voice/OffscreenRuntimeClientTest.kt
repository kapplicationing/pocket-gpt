package com.pocketagent.android.voice

import com.pocketagent.android.RuntimeFacadeAvailability
import com.pocketagent.android.RuntimeFacadeUnavailableException
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.RuntimeSessionUnavailableException
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ChatToolCall
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamInterruptionReason
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeMemoryRetention
import com.pocketagent.runtime.RuntimeRecoveryDisposition
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OffscreenRuntimeClientTest {
    @Test
    fun `voice turn preserves typed runtime transition without creating an ephemeral session`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = emptyList(),
            createUnavailable = RuntimeFacadeAvailability.REPLACING,
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
        )

        val error = assertFailsWith<RuntimeSessionUnavailableException> {
            client.runVoiceTurn(transcript = "Open Maps", systemPrompt = "voice-system")
        }

        assertEquals("RUNTIME_REPLACEMENT_IN_PROGRESS", error.errorCode)
        assertEquals(RuntimeRecoveryDisposition.RETRY_REQUEST, error.recoveryDisposition)
        assertTrue(runtimeGateway.deletedSessions.isEmpty())
        assertTrue(runtimeGateway.preparedCommands.isEmpty())
    }

    @Test
    fun `model proposed action requires confirmation before shared runtime execution`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = listOf(
                ChatStreamEvent.Completed(
                    requestId = "ignored",
                    response = ChatResponse(
                        sessionId = SessionId("session-1"),
                        modelId = "model-a",
                        text = "Opening Maps.",
                        firstTokenLatencyMs = 10L,
                        totalLatencyMs = 25L,
                        toolCalls = listOf(
                            ChatToolCall(
                                id = "tool-1",
                                name = "app_open",
                                argumentsJson = """{"app_name":"Maps"}""",
                            ),
                        ),
                    ),
                    finishReason = "completed",
                ),
            ),
            toolResults = mapOf(
                "app_open" to ToolExecutionResult.Success("Opened Maps"),
            ),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 0L },
        )

        val proposal = client.runVoiceTurn(
            transcript = "Could you navigate with Maps",
            systemPrompt = "voice-system",
        )

        assertTrue(proposal.requiresFollowUpCapture)
        assertTrue(proposal.toolOutputs.single().contains("Say yes"))
        assertTrue(runtimeGateway.toolNames.isEmpty())
        client.startConfirmationWindow()

        val result = client.runVoiceTurn(
            transcript = "yes",
            systemPrompt = "voice-system",
        )

        assertEquals(listOf("Opened Maps"), result.toolOutputs)
        assertEquals(listOf(SessionId("session-1")), runtimeGateway.deletedSessions)
        assertEquals(listOf("app_open"), runtimeGateway.toolNames)
        assertEquals(2, runtimeGateway.touchKeepAliveCalls)
        val command = assertNotNull(runtimeGateway.preparedCommands.singleOrNull())
        assertEquals("voice", command.promptHint)
        assertEquals(
            "Could you navigate with Maps",
            (command.messages.single().parts.single() as InteractionContentPart.Text).text,
        )
        assertEquals("voice-system", command.samplingOverrides?.systemPrompt)
        assertEquals(160, command.samplingOverrides?.maxTokens)
        assertEquals(RuntimeMemoryRetention.EPHEMERAL, command.memoryRetention)
    }

    @Test
    fun `run voice turn deletes ephemeral session when stream fails`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = listOf(
                ChatStreamEvent.Failed(
                    requestId = "ignored",
                    errorCode = "native_error",
                    message = "generation failed",
                ),
            ),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 0L },
        )

        val error = assertFailsWith<IllegalStateException> {
            client.runVoiceTurn(
                transcript = "Tell me something",
                systemPrompt = "voice-system",
            )
        }

        assertTrue(error.message.orEmpty().contains("generation failed"))
        assertEquals(listOf(SessionId("session-1")), runtimeGateway.deletedSessions)
    }

    @Test
    fun `run voice turn rejects interrupted partial output as incomplete`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = listOf(
                ChatStreamEvent.Interrupted(
                    requestId = "ignored",
                    reason = ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL,
                    partialText = "partial answer",
                ),
            ),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
        )

        val error = assertFailsWith<IllegalStateException> {
            client.runVoiceTurn(
                transcript = "Tell me something",
                systemPrompt = "voice-system",
            )
        }

        assertTrue(error.message.orEmpty().contains("closed_without_terminal"))
        assertEquals(listOf(SessionId("session-1")), runtimeGateway.deletedSessions)
    }

    @Test
    fun `warm last used model uses shared provisioning path and keeps runtime alive`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(streamEvents = emptyList())
        var warmCalls = 0
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = {
                warmCalls += 1
                RuntimeModelLifecycleCommandResult.applied()
            },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
        )

        val result = client.warmLastUsedModel()

        assertTrue(result.success)
        assertEquals(1, warmCalls)
        assertEquals(1, runtimeGateway.touchKeepAliveCalls)
    }

    @Test
    fun `run voice turn preserves legacy tool failure strings`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = listOf(
                ChatStreamEvent.Completed(
                    requestId = "ignored",
                    response = ChatResponse(
                        sessionId = SessionId("session-1"),
                        modelId = "model-a",
                        text = "",
                        firstTokenLatencyMs = 10L,
                        totalLatencyMs = 25L,
                        toolCalls = listOf(
                            ChatToolCall(
                                id = "tool-1",
                                name = "timer_set",
                                argumentsJson = """{"duration_seconds":"300"}""",
                            ),
                        ),
                    ),
                    finishReason = "completed",
                ),
            ),
            toolResults = mapOf(
                "timer_set" to ToolExecutionResult.Failure(
                    ToolFailure.Execution(
                        code = "tool_runtime_error",
                        userMessage = "Tool request failed.",
                        technicalDetail = "timer unavailable",
                    ),
                ),
            ),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 0L },
        )

        val proposal = client.runVoiceTurn(
            transcript = "Please help with timing",
            systemPrompt = "voice-system",
        )
        assertTrue(proposal.requiresFollowUpCapture)
        client.startConfirmationWindow()

        val result = client.runVoiceTurn(
            transcript = "yes",
            systemPrompt = "voice-system",
        )

        assertEquals(listOf("Tool error: timer unavailable"), result.toolOutputs)
    }

    @Test
    fun `wake action waits for deterministic confirmation then executes once`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = completedWithTools(
                ChatToolCall(
                    id = "tool-1",
                    name = "volume_set",
                    argumentsJson = """{"level_percent":"40"}""",
                ),
            ),
            toolResults = mapOf("volume_set" to ToolExecutionResult.Success("Volume changed")),
        )
        var elapsed = 1_000L
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { elapsed },
        )

        val proposal = client.runVoiceTurn(
            transcript = "set volume to forty percent",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertTrue(proposal.requiresFollowUpCapture)
        assertTrue(proposal.toolOutputs.single().contains("Say yes"))
        assertTrue(runtimeGateway.toolNames.isEmpty())
        client.startConfirmationWindow()
        elapsed += 2_000L

        val confirmed = client.runVoiceTurn(
            transcript = "yes",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertFalse(confirmed.requiresFollowUpCapture)
        assertEquals(listOf("Volume changed"), confirmed.toolOutputs)
        assertEquals(listOf("volume_set"), runtimeGateway.toolNames)
    }

    @Test
    fun `wake action cancellation never calls runtime`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = completedWithTools(
                ChatToolCall("tool-1", "flashlight_toggle", """{"enabled":"on"}"""),
            ),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 0L },
        )
        client.runVoiceTurn(
            transcript = "turn on the flashlight",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )
        client.startConfirmationWindow()

        val cancelled = client.runVoiceTurn(
            transcript = "no thanks",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertEquals(listOf("Cancelled."), cancelled.toolOutputs)
        assertTrue(runtimeGateway.toolNames.isEmpty())
    }

    @Test
    fun `failed confirmation preview can abandon the pending mutation`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = emptyList(),
            toolResults = mapOf("volume_set" to ToolExecutionResult.Success("Volume changed")),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 0L },
        )

        val proposal = client.runVoiceTurn(
            transcript = "set volume to forty percent",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertTrue(proposal.requiresFollowUpCapture)
        assertTrue(client.cancelPendingInteraction())
        assertFalse(client.cancelPendingInteraction())
        assertTrue(runtimeGateway.toolNames.isEmpty())
    }

    @Test
    fun `alarm period clarification stays in the same explicit voice session`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = emptyList(),
            toolResults = mapOf("alarm_set" to ToolExecutionResult.Success("Alarm handed to Clock")),
        )
        var elapsed = 10_000L
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { elapsed },
            localDateTimeNow = { java.time.LocalDateTime.of(2026, 7, 11, 20, 30) },
        )

        val question = client.runVoiceTurn(
            transcript = "set an alarm for seven called work",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.ASSISTANT,
        )
        assertTrue(question.requiresFollowUpCapture)
        assertTrue(question.assistantText.contains("AM or PM"))
        client.startConfirmationWindow()
        elapsed += 1_000L

        val completed = client.runVoiceTurn(
            transcript = "AM",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.ASSISTANT,
        )

        assertFalse(completed.requiresFollowUpCapture)
        assertEquals(listOf("Alarm handed to Clock"), completed.toolOutputs)
        assertEquals(listOf("alarm_set"), runtimeGateway.toolNames)
    }

    @Test
    fun `confirmation expires using monotonic time and source mismatch fails closed`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = completedWithTools(
                ChatToolCall("tool-1", "volume_set", """{"level_percent":"10"}"""),
            ),
        )
        var elapsed = 5_000L
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { elapsed },
        )
        client.runVoiceTurn(
            transcript = "set volume to ten",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )
        client.startConfirmationWindow()
        elapsed += 31_000L

        val expired = client.runVoiceTurn(
            transcript = "yes",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertTrue(expired.toolOutputs.single().contains("expired"))
        assertTrue(runtimeGateway.toolNames.isEmpty())
    }

    @Test
    fun `replacement voice session cannot confirm a stale same-source action`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = emptyList(),
            toolResults = mapOf("volume_set" to ToolExecutionResult.Success("Volume changed")),
        )
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 1_000L },
        )

        val proposal = client.runVoiceTurn(
            transcript = "set volume to ten percent",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
            voiceSessionId = 100L,
        )
        assertTrue(proposal.requiresFollowUpCapture)
        client.startConfirmationWindow()

        val staleConfirmation = client.runVoiceTurn(
            transcript = "yes",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
            voiceSessionId = 101L,
        )

        assertTrue(staleConfirmation.toolOutputs.single().contains("expired"))
        assertTrue(runtimeGateway.toolNames.isEmpty())
    }

    @Test
    fun `replacement voice session cannot complete a stale alarm clarification`() = runTest {
        val runtimeGateway = RecordingOffscreenRuntimeGateway(streamEvents = emptyList())
        val client = OffscreenRuntimeClient(
            runtimeGateway = runtimeGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
            elapsedRealtimeMillis = { 1_000L },
        )

        val question = client.runVoiceTurn(
            transcript = "set an alarm for seven",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.ASSISTANT,
            voiceSessionId = 200L,
        )
        assertTrue(question.requiresFollowUpCapture)
        client.startConfirmationWindow()

        val staleClarification = client.runVoiceTurn(
            transcript = "AM",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.ASSISTANT,
            voiceSessionId = 201L,
        )

        assertTrue(staleClarification.assistantText.contains("expired"))
        assertTrue(runtimeGateway.toolNames.isEmpty())
    }

    @Test
    fun `multiple tools and hidden activity launches are rejected before execution`() = runTest {
        val multipleGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = completedWithTools(
                ChatToolCall("tool-1", "volume_set", """{"level_percent":"10"}"""),
                ChatToolCall("tool-2", "flashlight_toggle", """{"enabled":"on"}"""),
            ),
        )
        val multipleClient = OffscreenRuntimeClient(
            runtimeGateway = multipleGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
        )

        val multiple = multipleClient.runVoiceTurn("do two things", "voice-system")

        assertTrue(multiple.toolOutputs.single().contains("one action at a time"))
        assertTrue(multipleGateway.toolNames.isEmpty())

        val hiddenGateway = RecordingOffscreenRuntimeGateway(
            streamEvents = completedWithTools(
                ChatToolCall("tool-3", "app_open", """{"app_name":"Maps"}"""),
            ),
        )
        val hiddenClient = OffscreenRuntimeClient(
            runtimeGateway = hiddenGateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { false },
        )

        val hidden = hiddenClient.runVoiceTurn(
            transcript = "open Maps",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.WAKE_WORD,
        )

        assertTrue(hidden.toolOutputs.single().contains("Talk now"))
        assertTrue(hiddenGateway.toolNames.isEmpty())
    }

    @Test
    fun `locked assistant never enters conversational memory or opens apps`() = runTest {
        val gateway = RecordingOffscreenRuntimeGateway(streamEvents = emptyList())
        val client = OffscreenRuntimeClient(
            runtimeGateway = gateway,
            loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
            deviceStateProvider = { TEST_DEVICE_STATE },
            isVisibleVoiceSession = { true },
        )

        val conversation = client.runVoiceTurn(
            transcript = "what is my last note",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.LOCKED_ASSISTANT,
        )
        val appLaunch = client.runVoiceTurn(
            transcript = "open Maps",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.LOCKED_ASSISTANT,
        )
        val flashlight = client.runVoiceTurn(
            transcript = "turn on the flashlight",
            systemPrompt = "voice-system",
            invocationSource = VoiceInvocationSource.LOCKED_ASSISTANT,
        )

        assertTrue(conversation.assistantText.contains("Unlock"))
        assertTrue(appLaunch.toolOutputs.single().contains("Unlock"))
        assertTrue(flashlight.toolOutputs.single().contains("Unlock"))
        assertTrue(gateway.preparedCommands.isEmpty())
        assertTrue(gateway.toolNames.isEmpty())
    }
}

private fun completedWithTools(vararg tools: ChatToolCall): List<ChatStreamEvent> {
    return listOf(
        ChatStreamEvent.Completed(
            requestId = "ignored",
            response = ChatResponse(
                sessionId = SessionId("session-1"),
                modelId = "model-a",
                text = "",
                firstTokenLatencyMs = 10L,
                totalLatencyMs = 25L,
                toolCalls = tools.toList(),
            ),
            finishReason = "completed",
        ),
    )
}

private val TEST_DEVICE_STATE = DeviceState(
    batteryPercent = 85,
    thermalLevel = 3,
    ramClassGb = 8,
)

private class RecordingOffscreenRuntimeGateway(
    private val streamEvents: List<ChatStreamEvent>,
    private val toolResults: Map<String, ToolExecutionResult> = emptyMap(),
    private val createUnavailable: RuntimeFacadeAvailability? = null,
) : ChatRuntimeService {
    val preparedCommands = mutableListOf<ChatStreamCommand>()
    val deletedSessions = mutableListOf<SessionId>()
    val toolNames = mutableListOf<String>()
    var touchKeepAliveCalls: Int = 0

    override fun createSession(): SessionId {
        createUnavailable?.let { unavailable -> throw RuntimeFacadeUnavailableException(unavailable) }
        return SessionId("session-1")
    }

    override fun prepareChatStream(command: ChatStreamCommand): PreparedChatStream {
        preparedCommands += command
        return ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L).prepare(command)
    }

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = flow {
        streamEvents.forEach { emit(it) }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        toolNames += toolName
        return toolResults[toolName] ?: ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(
        imagePath: String,
        prompt: String,
    ) = error("Not used in OffscreenRuntimeClientTest")

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean {
        deletedSessions += sessionId
        return true
    }

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false

    override fun touchKeepAlive(): Boolean {
        touchKeepAliveCalls += 1
        return true
    }
}
