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
import com.pocketagent.runtime.RuntimeRecoveryDisposition
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `run voice turn uses shared runtime gateway and executes returned tools`() = runTest {
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
        )

        val result = client.runVoiceTurn(
            transcript = "Open Maps",
            systemPrompt = "voice-system",
        )

        assertEquals("Opening Maps.", result.assistantText)
        assertEquals(listOf("Opened Maps"), result.toolOutputs)
        assertEquals(listOf(SessionId("session-1")), runtimeGateway.deletedSessions)
        assertEquals(listOf("app_open"), runtimeGateway.toolNames)
        assertEquals(2, runtimeGateway.touchKeepAliveCalls)
        val command = assertNotNull(runtimeGateway.preparedCommands.singleOrNull())
        assertEquals("voice", command.promptHint)
        assertEquals(
            "Open Maps",
            (command.messages.single().parts.single() as InteractionContentPart.Text).text,
        )
        assertEquals("voice-system", command.samplingOverrides?.systemPrompt)
        assertEquals(160, command.samplingOverrides?.maxTokens)
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
        )

        val error = assertFailsWith<IllegalStateException> {
            client.runVoiceTurn(
                transcript = "Open Maps",
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
        )

        val error = assertFailsWith<IllegalStateException> {
            client.runVoiceTurn(
                transcript = "Open Maps",
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
        )

        val result = client.runVoiceTurn(
            transcript = "Set a timer",
            systemPrompt = "voice-system",
        )

        assertEquals(listOf("Tool error: timer unavailable"), result.toolOutputs)
    }
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
