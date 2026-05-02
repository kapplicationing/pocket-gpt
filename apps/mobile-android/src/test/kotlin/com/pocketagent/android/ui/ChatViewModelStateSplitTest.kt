package com.pocketagent.android.ui

import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.data.chat.StoredChatState
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ImageFailure
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.toList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStateSplitTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `composerFlow does not emit when only runtime status changes`() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()
        viewModel._uiState.value = ChatUiState(composer = ComposerUiState(text = "draft"))
        val emissions = mutableListOf<ComposerUiState>()
        val job = launch { viewModel.composerFlow.toList(emissions) }
        advanceUntilIdle()
        emissions.clear()

        viewModel._uiState.value = viewModel._uiState.value.copy(
            runtime = RuntimeUiState(modelRuntimeStatus = ModelRuntimeStatus.LOADING),
        )
        advanceUntilIdle()

        assertEquals(emptyList(), emissions)
        job.cancel()
    }

    @Test
    fun `runtimeFlow does not emit when only composer text changes`() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()
        val runtime = RuntimeUiState(modelStatusDetail = "ready")
        viewModel._uiState.value = ChatUiState(runtime = runtime)
        val emissions = mutableListOf<RuntimeUiState>()
        val job = launch { viewModel.runtimeFlow.toList(emissions) }
        advanceUntilIdle()
        emissions.clear()

        repeat(20) { index ->
            viewModel.onComposerChanged("hello $index")
        }
        advanceUntilIdle()

        assertEquals(emptyList(), emissions)
        job.cancel()
    }

    @Test
    fun `streaming token update does not replace sessions list`() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()
        val messages = (0 until 50).map { index ->
            MessageUiModel(
                id = if (index == 1) "m1" else "m$index",
                role = MessageRole.ASSISTANT,
                content = "",
                timestampEpochMs = index.toLong(),
                isStreaming = index == 1,
            )
        }
        viewModel._uiState.value = ChatUiState(
            sessions = listOf(
                ChatSessionUiModel(
                    id = "s1",
                    title = "Session",
                    createdAtEpochMs = 0L,
                    updatedAtEpochMs = 0L,
                    messages = messages,
                ),
            ),
            activeSessionId = "s1",
        )
        val before = viewModel.uiState.value.sessions

        viewModel.updateStreamingMessage(sessionId = "s1", messageId = "m1", text = "Hello")

        assertSame(before, viewModel.uiState.value.sessions)
        assertEquals("Hello", viewModel.uiState.value.streaming.text)
    }

    @Test
    fun `thinking enabled flow does not emit when active session messages change`() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()
        viewModel._uiState.value = ChatUiState(
            sessions = listOf(
                ChatSessionUiModel(
                    id = "s1",
                    title = "Session",
                    createdAtEpochMs = 0L,
                    updatedAtEpochMs = 0L,
                    messages = listOf(
                        MessageUiModel(
                            id = "m1",
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestampEpochMs = 0L,
                            isStreaming = true,
                        ),
                    ),
                ),
            ),
            activeSessionId = "s1",
        )
        val emissions = mutableListOf<Boolean>()
        val job = launch { viewModel.currentThinkingEnabledFlow.toList(emissions) }
        advanceUntilIdle()
        emissions.clear()

        viewModel.updateStreamingMessage(sessionId = "s1", messageId = "m1", text = "Hello")
        viewModel.finalizeStreamingMessage(sessionId = "s1", messageId = "m1", finalText = "Hello")
        advanceUntilIdle()

        assertEquals(emptyList(), emissions)
        job.cancel()
    }

    private fun testViewModel(): ChatViewModel {
        return ChatViewModel(
            runtimeFacade = FakeRuntimeService(),
            sessionPersistence = InMemorySessionPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
    }
}

private class InMemorySessionPersistence : SessionPersistence {
    private var state = StoredChatState(onboardingCompleted = true)

    override fun loadState(): StoredChatState = state

    override fun saveState(state: StoredChatState) {
        this.state = state
    }
}

private class FakeRuntimeService : ChatRuntimeService {
    override fun createSession(): SessionId = SessionId("session")

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        error("unused")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.Failure(
            ImageFailure.Runtime(
                code = "unused",
                userMessage = "unused",
                technicalDetail = null,
            ),
        )
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = "TEST"

    override fun supportsGpuOffload(): Boolean = false
}
