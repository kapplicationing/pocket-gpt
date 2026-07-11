package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllersTest {
    @Test
    fun `chat send controller delegates tool execution to runtime gateway`() = runTest {
        val runtime = RecordingRuntimeGateway()
        val controller = ChatSendController(runtimeGateway = runtime, ioDispatcher = Dispatchers.IO)

        val result = controller.runTool("calculator", """{"expression":"2+2"}""")

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("calculator", runtime.lastToolName)
        assertEquals("""{"expression":"2+2"}""", runtime.lastToolJsonArgs)
    }

    @Test
    fun `chat send controller delegates image analysis to runtime gateway`() = runTest {
        val runtime = RecordingRuntimeGateway()
        val controller = ChatSendController(runtimeGateway = runtime, ioDispatcher = Dispatchers.IO)

        val result = controller.analyzeImage("/tmp/a.jpg", "describe")

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals("/tmp/a.jpg", runtime.lastImagePath)
        assertEquals("describe", runtime.lastImagePrompt)
    }

    @Test
    fun `tool loop propagates parent cancellation instead of mapping it to a failure`() = runBlocking {
        val enteredTool = CompletableDeferred<Unit>()
        val releaseTool = CountDownLatch(1)
        val escapedOutcome = CompletableDeferred<ToolLoopOutcome>()
        val runtime = RecordingRuntimeGateway(
            toolBehavior = { _, _ ->
                enteredTool.complete(Unit)
                releaseTool.await()
                ToolExecutionResult.Success("late tool result")
            },
        )
        val toolLoop = ToolLoopUseCase(
            sendController = ChatSendController(runtimeGateway = runtime, ioDispatcher = Dispatchers.IO),
        )
        val parent = launch {
            escapedOutcome.complete(
                toolLoop.execute("calculator", """{"expression":"2+2"}"""),
            )
        }

        try {
            enteredTool.await()
            parent.cancel(CancellationException("parent send cancelled"))
            withTimeout(5_000L) { parent.join() }

            assertFalse(escapedOutcome.isCompleted)
        } finally {
            releaseTool.countDown()
            parent.cancelAndJoin()
        }
    }

    @Test
    fun `startup probe controller returns timeout marker when startup checks exceed timeout`() = runTest {
        val runtime = RecordingRuntimeGateway(
            startupBehavior = {
                val started = System.currentTimeMillis()
                while ((System.currentTimeMillis() - started) < 40L) {
                    Thread.onSpinWait()
                }
                listOf("late")
            },
        )
        val controller = StartupProbeController()

        val checks = controller.runStartupChecks(
            runtimeGateway = runtime,
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 5L,
        )

        assertTrue(checks.single().contains("timed out"))
    }

    @Test
    fun `startup probe controller converts unexpected startup exception into check message`() = runBlocking {
        val runtime = RecordingRuntimeGateway(
            startupBehavior = {
                error("startup crash")
            },
        )
        val controller = StartupProbeController()

        val checks = controller.runStartupChecks(
            runtimeGateway = runtime,
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 5_000L,
        )

        assertEquals(1, checks.size)
        assertTrue(checks.single().contains("Startup checks"))
        assertTrue(checks.single().contains("startup crash"))
    }

    @Test
    fun `startup flow preserves ready model while probe refresh runs`() {
        val flow = ChatStartupFlow(
            runtimeGateway = RecordingRuntimeGateway(),
            startupProbeController = StartupProbeController(),
            startupReadinessCoordinator = StartupReadinessCoordinator(),
            ioDispatcher = Dispatchers.IO,
            runtimeStartupProbeTimeoutMs = 1_000L,
            nativeRuntimeLibraryPackaged = true,
        )
        val state = ChatUiState(
            runtime = RuntimeUiState(
                activeModelId = "qwen3.5-0.8b-q4",
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = "Runtime model ready",
                lastErrorCode = "STALE_ERROR",
                lastErrorUserMessage = "stale",
                lastErrorTechnicalDetail = "stale_detail",
                lastError = "stale",
            ),
        )

        val nextState = flow.markProbeRunning(state)

        assertEquals(StartupProbeState.READY, nextState.runtime.startupProbeState)
        assertEquals(ModelRuntimeStatus.READY, nextState.runtime.modelRuntimeStatus)
        assertEquals("Runtime model ready", nextState.runtime.modelStatusDetail)
        assertEquals(null, nextState.runtime.lastErrorCode)
        assertEquals(null, nextState.runtime.lastErrorUserMessage)
    }
}

private class RecordingRuntimeGateway(
    private val startupBehavior: () -> List<String> = { emptyList() },
    private val toolBehavior: (String, String) -> ToolExecutionResult = { toolName, _ ->
        ToolExecutionResult.Success("tool:$toolName")
    },
) : ChatRuntimeService {
    var lastToolName: String? = null
    var lastToolJsonArgs: String? = null
    var lastImagePath: String? = null
    var lastImagePrompt: String? = null

    override fun createSession(): SessionId = SessionId("session-1")
    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        lastToolName = toolName
        lastToolJsonArgs = jsonArgs
        return toolBehavior(toolName, jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        lastImagePath = imagePath
        lastImagePrompt = prompt
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = startupBehavior()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false
}
