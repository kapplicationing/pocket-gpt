package com.pocketagent.android.ui

import android.net.Uri
import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.data.chat.SessionStateLoadResult
import com.pocketagent.android.data.chat.StoredChatMessage
import com.pocketagent.android.data.chat.StoredChatSession
import com.pocketagent.android.data.chat.StoredChatState
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.ProvisioningRecoverySignal
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.resolveChatGateState
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.android.runtime.ProvisioningReadiness
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ImageFailure
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.nativebridge.ModelLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send message streams tokens and persists assistant output`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello ui")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val activeSession = state.activeSession!!
        assertTrue(activeSession.messages.any { it.role == MessageRole.USER && it.content == "hello ui" })
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for hello ui") })
        assertEquals("auto", state.runtime.activeModelId)
        assertTrue(persistence.savedStates.isNotEmpty())
        assertEquals(null, state.runtime.lastErrorCode)
    }

    @Test
    fun `explicit tool command executes local tool path`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.runTool(
            toolName = "calculator",
            jsonArgs = """{"expression":"4*9"}""",
        )
        advanceUntilIdle()

        val messages = viewModel.uiState.value.activeSession!!.messages
        assertTrue(messages.any { it.role == MessageRole.USER && it.content.contains("Run tool: calculator") })
        assertTrue(messages.any { it.role == MessageRole.ASSISTANT && it.toolName == "calculator" })
        assertTrue(messages.any { it.role == MessageRole.TOOL && it.toolName == "calculator" && it.content.contains("tool:calculator") })
    }

    @Test
    fun `onboarding state defaults visible and persists when completed`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.Onboarding)
        assertEquals(FirstSessionStage.ONBOARDING, viewModel.uiState.value.firstSessionStage)

        viewModel.completeOnboarding()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.Onboarding)
        assertEquals(FirstSessionStage.READY_TO_CHAT, viewModel.uiState.value.firstSessionStage)
        assertTrue(persistence.savedStates.last().onboardingCompleted)
    }

    @Test
    fun `advanced controls are available on first launch`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.advancedUnlocked)
        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.AdvancedSettings)
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.AdvancedSettings)

        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.ToolSuggestions)
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.ToolSuggestions)

        viewModel.dismissSurface()
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.None)
    }

    @Test
    fun `prefill composer clears active surface`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.CompletionSettings)
        viewModel.prefillComposer("hello")

        assertEquals("hello", viewModel.uiState.value.composer.text)
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.None)
    }

    @Test
    fun `session drawer and model library surfaces can be opened`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.SessionDrawer)
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.SessionDrawer)

        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.ModelLibrary)
        assertTrue(viewModel.uiState.value.activeSurface is com.pocketagent.android.ui.state.ModalSurface.ModelLibrary)
    }

    @Test
    fun `runtime diagnostics snapshot is reflected in ui runtime state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            runtimeDiagnostics = RuntimeDiagnosticsSnapshot(
                backendProfile = "opencl",
                compiledBackend = "hexagon,opencl",
                nativeRuntimeSupported = true,
                strictAcceleratorFailFast = true,
                autoBackendCpuFallback = true,
            ),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value.runtime
        assertEquals("opencl", state.backendProfile)
        assertEquals("hexagon,opencl", state.compiledBackend)
        assertTrue(state.nativeRuntimeSupported == true)
        assertTrue(state.strictAcceleratorFailFast == true)
        assertTrue(state.autoBackendCpuFallback == true)
    }

    @Test
    fun `load last used send follow up offload reload send remains stable`() = runTest(dispatcher) {
        val provisioning = QuickLoadProvisioningGatewayForTest()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(
                initialState = StoredChatState(
                    onboardingCompleted = true,
                    advancedUnlocked = true,
                ),
            ),
            presetBackingStore = FakePresetBackingStore(),
            provisioningGateway = provisioning,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val initialLoadResult = viewModel.loadLastUsedModel()
        advanceUntilIdle()
        assertTrue(initialLoadResult?.success == true)
        assertTrue(viewModel.modelLoadingState.value is com.pocketagent.android.ui.state.ModelLoadingState.Loaded)

        viewModel.onComposerChanged("quick load prompt")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.onComposerChanged("follow up prompt")
        viewModel.sendMessage()
        advanceUntilIdle()

        val offloadResult = viewModel.offloadModel("quick-load-test")
        advanceUntilIdle()
        assertTrue(offloadResult?.success == true)
        assertTrue(viewModel.modelLoadingState.value is com.pocketagent.android.ui.state.ModelLoadingState.Idle)

        val reloadResult = viewModel.loadLastUsedModel()
        advanceUntilIdle()
        assertTrue(reloadResult?.success == true)
        assertTrue(viewModel.modelLoadingState.value is com.pocketagent.android.ui.state.ModelLoadingState.Loaded)

        viewModel.onComposerChanged("after reload prompt")
        viewModel.sendMessage()
        advanceUntilIdle()

        val activeSession = viewModel.uiState.value.activeSession!!
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for quick load prompt") })
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for follow up prompt") })
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for after reload prompt") })
        assertEquals(2, provisioning.loadLastUsedCalls)
        assertEquals(listOf("quick-load-test"), provisioning.offloadReasons)
    }

    @Test
    fun `switching to unloaded session hydrates messages from persistence`() = runTest(dispatcher) {
        val unloadedMessages = listOf(
            MessageUiModel(
                id = "m-unloaded-1",
                role = MessageRole.USER,
                content = "persisted question",
                timestampEpochMs = 10L,
                kind = MessageKind.TEXT,
            ),
            MessageUiModel(
                id = "m-unloaded-2",
                role = MessageRole.ASSISTANT,
                content = "persisted answer",
                timestampEpochMs = 20L,
                kind = MessageKind.TEXT,
            ),
        )
        val persistence = RecordingPersistence(
            initialState = StoredChatState(
                sessions = listOf(
                    ChatSessionUiModel(
                        id = "session-1",
                        title = "Loaded",
                        createdAtEpochMs = 1L,
                        updatedAtEpochMs = 2L,
                        messages = listOf(
                            MessageUiModel(
                                id = "m-loaded-1",
                                role = MessageRole.USER,
                                content = "hello",
                                timestampEpochMs = 5L,
                                kind = MessageKind.TEXT,
                            ),
                        ),
                    ).toStoredSession(),
                    ChatSessionUiModel(
                        id = "session-2",
                        title = "Unloaded",
                        createdAtEpochMs = 3L,
                        updatedAtEpochMs = 4L,
                        messages = emptyList(),
                        messagesLoaded = false,
                        messageCount = unloadedMessages.size,
                    ).toStoredSession(),
                ),
                activeSessionId = "session-1",
            ),
            sessionMessages = mapOf("session-2" to unloadedMessages.map(MessageUiModel::toStoredMessage)),
        )
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.switchSession("session-2")
        advanceUntilIdle()

        val activeSession = viewModel.uiState.value.activeSession!!
        assertEquals("session-2", activeSession.id)
        assertTrue(activeSession.messagesLoaded)
        assertEquals(2, activeSession.messages.size)
        assertEquals("persisted answer", activeSession.messages.last().content)
        assertTrue(runtime.restoredTurns.any { it.first.value == "session-2" })
    }

    @Test
    fun `simple-first progress still advances milestones when advanced controls are already available`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.completeOnboarding()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.advancedUnlocked)

        viewModel.onComposerChanged("first question")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.firstAnswerCompleted)
        assertFalse(viewModel.uiState.value.followUpCompleted)
        assertTrue(viewModel.uiState.value.advancedUnlocked)
        assertEquals(FirstSessionStage.FIRST_ANSWER_DONE, viewModel.uiState.value.firstSessionStage)

        viewModel.onComposerChanged("follow up question")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.firstAnswerCompleted)
        assertTrue(viewModel.uiState.value.followUpCompleted)
        assertTrue(viewModel.uiState.value.advancedUnlocked)
        assertEquals(FirstSessionStage.FOLLOW_UP_DONE, viewModel.uiState.value.firstSessionStage)
        assertFalse(viewModel.uiState.value.firstSessionTelemetryEvents.any { it.eventName == "advanced_unlocked" })
    }

    @Test
    fun `get ready action moves stage to get ready and records telemetry`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.completeOnboarding()
        advanceUntilIdle()
        viewModel.onGetReadyTapped()
        advanceUntilIdle()

        assertEquals(FirstSessionStage.GET_READY, viewModel.uiState.value.firstSessionStage)
        assertTrue(viewModel.uiState.value.firstSessionTelemetryEvents.any { it.eventName == "get_ready_started" })
    }

    @Test
    fun `stream token updates do not persist state on every token`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade(
            streamTokens = List(40) { "tok$it " },
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("long stream")
        viewModel.sendMessage()
        advanceUntilIdle()

        // bootstrap + send start + completed persist path should stay bounded.
        assertTrue(persistence.savedStates.size <= 6)
    }

    @Test
    fun `send flow uses injected device telemetry provider for routing request`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val expectedDeviceState = DeviceState(
            batteryPercent = 22,
            thermalLevel = 7,
            ramClassGb = 6,
        )
        val sendFlow = ChatSendFlow(
            runtimeGenerationTimeoutMs = 0L,
            deviceStateProvider = DeviceStateProvider { expectedDeviceState },
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            sendFlow = sendFlow,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("telemetry check")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(expectedDeviceState, runtime.lastStreamRequest?.deviceState)
    }

    @Test
    fun `bootstraps from persisted sessions and restores turns`() = runTest(dispatcher) {
        val persistedSession = ChatSessionUiModel(
            id = "persisted-1",
            title = "Persisted",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            messages = listOf(
                MessageUiModel(
                    id = "m1",
                    role = MessageRole.USER,
                    content = "remember launch checklist",
                    timestampEpochMs = 10L,
                    kind = MessageKind.TEXT,
                ),
                MessageUiModel(
                    id = "m2",
                    role = MessageRole.ASSISTANT,
                    content = "noted",
                    timestampEpochMs = 11L,
                    kind = MessageKind.TEXT,
                ),
                MessageUiModel(
                    id = "m3",
                    role = MessageRole.TOOL,
                    content = "{\"value\":42}",
                    timestampEpochMs = 12L,
                    kind = MessageKind.TOOL,
                ),
            ),
        )
        val persistence = RecordingPersistence(
            initialState = StoredChatState(
                sessions = listOf(persistedSession.toStoredSession()),
                activeSessionId = persistedSession.id,
                routingMode = RoutingMode.QWEN_0_8B.name,
                keepAlivePreference = RuntimeKeepAlivePreference.FIVE_MINUTES.name,
            ),
        )
        val runtime = RecordingRuntimeFacade()

        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(RoutingMode.QWEN_0_8B, viewModel.uiState.value.runtime.routingMode)
        assertEquals(RuntimeKeepAlivePreference.FIVE_MINUTES, viewModel.uiState.value.runtime.keepAlivePreference)
        assertEquals(1, runtime.restoredTurns.size)
        assertEquals("persisted-1", runtime.restoredTurns.first().first.value)
        assertEquals(3, runtime.restoredTurns.first().second.size)
        assertTrue(runtime.restoredTurns.first().second.any { it.role == "tool" && it.content.contains("42") })
    }

    @Test
    fun `recoverable persisted state corruption is surfaced with deterministic ui code`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = CorruptLoadPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val initialRuntimeState = viewModel.uiState.value.runtime
        assertEquals("UI-SESSION-001", initialRuntimeState.lastErrorCode)
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.BLOCKED, initialRuntimeState.startupProbeState)

        viewModel.refreshRuntimeReadiness()
        advanceUntilIdle()

        val recoveredRuntimeState = viewModel.uiState.value.runtime
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.READY, recoveredRuntimeState.startupProbeState)
        assertEquals(null, recoveredRuntimeState.lastErrorCode)
    }

    @Test
    fun `session switch preserves per-session timeline state`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("session one message")
        viewModel.sendMessage()
        advanceUntilIdle()
        val firstSessionId = viewModel.uiState.value.activeSession!!.id

        viewModel.createSession()
        val secondSessionId = viewModel.uiState.value.activeSession!!.id
        viewModel.onComposerChanged("session two message")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.switchSession(firstSessionId)
        val firstSession = viewModel.uiState.value.activeSession!!
        assertTrue(firstSession.messages.any { it.content == "session one message" })
        assertFalse(firstSession.messages.any { it.content == "session two message" })

        viewModel.switchSession(secondSessionId)
        val secondSession = viewModel.uiState.value.activeSession!!
        assertTrue(secondSession.messages.any { it.content == "session two message" })
    }

    @Test
    fun `deleting only session creates replacement session`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val initialSessionId = viewModel.uiState.value.activeSession!!.id

        viewModel.deleteSession(initialSessionId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessions.size)
        assertTrue(state.activeSession != null)
        assertTrue(state.activeSession!!.id != initialSessionId)
        assertEquals("New chat", state.activeSession!!.title)
    }

    @Test
    fun `attach image success appends image user message and assistant response`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/test-image.jpg")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.kind == MessageKind.IMAGE && it.role == MessageRole.USER })
        assertTrue(active.messages.any { it.kind == MessageKind.IMAGE && it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun `attach image failure shows system error message`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(failImage = true)
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/bad-image.jpg")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `image validation error maps to deterministic ui code`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(returnImageValidationError = true)
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/invalid.tiff")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-IMG-VAL-001") })
        assertEquals("UI-IMG-VAL-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `tool request success and failures are rendered in timeline`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.TOOL && it.content.contains("tool:calculator") })

        runtime.failTool = true
        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)

        runtime.failTool = false
        runtime.returnToolValidationError = true
        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-TOOL-SCHEMA-001") })
        assertEquals("UI-TOOL-SCHEMA-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `startup checks are mapped to startup ui error code`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )

        advanceUntilIdle()
        assertEquals("UI-STARTUP-001", viewModel.uiState.value.runtime.lastErrorCode)
        assertTrue(viewModel.uiState.value.runtime.lastErrorUserMessage?.contains("Runtime setup is incomplete") == true)
    }

    @Test
    fun `send path blocks when runtime startup probe is not ready`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello while blocked")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello while blocked" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
        assertEquals("UI-STARTUP-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `chat gate resolves model-missing state to expected primary actions`() {
        val runtime = RuntimeUiState(
            startupProbeState = com.pocketagent.android.ui.state.StartupProbeState.BLOCKED,
            modelRuntimeStatus = com.pocketagent.android.ui.state.ModelRuntimeStatus.NOT_READY,
        )
        val snapshot = RuntimeProvisioningSnapshot(
            models = emptyList(),
            storageSummary = StorageSummary(
                totalBytes = 0L,
                freeBytes = 0L,
                usedByModelsBytes = 0L,
                tempDownloadBytes = 0L,
            ),
            requiredModelIds = setOf("qwen3.5-0.8b-q4"),
        )

        val simpleFirstGate = resolveChatGateState(
            runtime = runtime,
            provisioningSnapshot = snapshot,
            advancedUnlocked = false,
        )
        assertEquals(ChatGateStatus.BLOCKED_MODEL_MISSING, simpleFirstGate.status)
        assertEquals(ChatGatePrimaryAction.GET_READY, simpleFirstGate.primaryAction)

        val advancedGate = resolveChatGateState(
            runtime = runtime,
            provisioningSnapshot = snapshot,
            advancedUnlocked = true,
        )
        assertEquals(ChatGateStatus.BLOCKED_MODEL_MISSING, advancedGate.status)
        assertEquals(ChatGatePrimaryAction.OPEN_MODEL_SETUP, advancedGate.primaryAction)
    }

    @Test
    fun `chat gate ready when runtime model loaded despite provisioning blocked for startup candidates`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = emptyList(),
            storageSummary = StorageSummary(
                totalBytes = 0L,
                freeBytes = 0L,
                usedByModelsBytes = 0L,
                tempDownloadBytes = 0L,
            ),
            requiredModelIds = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )
        assertEquals(ProvisioningReadiness.BLOCKED, snapshot.readiness)

        val gate = resolveChatGateState(
            runtime = RuntimeUiState(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                activeModelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
            ),
            provisioningSnapshot = snapshot,
            advancedUnlocked = true,
        )
        assertEquals(ChatGateStatus.READY, gate.status)
        assertEquals(ChatGatePrimaryAction.NONE, gate.primaryAction)
    }

    @Test
    fun `chat gate surfaces recoverable provisioning signal with refresh action`() {
        val snapshot = RuntimeProvisioningSnapshot(
            models = listOf(
                ProvisionedModelState(
                    modelId = "qwen3.5-0.8b-q4",
                    displayName = "Qwen",
                    fileName = "qwen.gguf",
                    absolutePath = null,
                    sha256 = null,
                    importedAtEpochMs = null,
                    activeVersion = null,
                    installedVersions = emptyList(),
                ),
            ),
            storageSummary = StorageSummary(
                totalBytes = 0L,
                freeBytes = 0L,
                usedByModelsBytes = 0L,
                tempDownloadBytes = 0L,
            ),
            requiredModelIds = setOf("qwen3.5-0.8b-q4"),
            recoverableCorruptions = listOf(
                ProvisioningRecoverySignal(
                    code = "MODEL_PATH_ALIAS_STALE",
                    message = "Model path alias is stale. Refresh runtime checks.",
                    technicalDetail = "model=qwen3.5-0.8b-q4",
                ),
            ),
        )
        val gate = resolveChatGateState(
            runtime = RuntimeUiState(
                startupProbeState = com.pocketagent.android.ui.state.StartupProbeState.READY,
                modelRuntimeStatus = com.pocketagent.android.ui.state.ModelRuntimeStatus.READY,
            ),
            provisioningSnapshot = snapshot,
            advancedUnlocked = true,
        )

        assertEquals(ChatGateStatus.ERROR_RECOVERABLE, gate.status)
        assertEquals(ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS, gate.primaryAction)
        assertEquals("Model path alias is stale. Refresh runtime checks.", gate.detail)
    }

    @Test
    fun `send and attach blocked guardrails emit consistent user guidance`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("blocked send")
        viewModel.sendMessage()
        viewModel.attachImage("/tmp/blocked-image.jpg")
        advanceUntilIdle()

        val systemMessages = viewModel.uiState.value.activeSession!!
            .messages
            .filter { it.role == MessageRole.SYSTEM }
            .takeLast(2)
        assertEquals(2, systemMessages.size)
        assertEquals(systemMessages[0].content, systemMessages[1].content)
        assertTrue(systemMessages[0].content.contains("UI-STARTUP-001"))
    }

    @Test
    fun `cancelled send clears transient loading placeholder message state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamDelayMs = 500L,
            streamTokens = emptyList(),
            streamTerminal = StreamTerminal.CANCELLED_MANUAL,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeGenerationTimeoutMs = 2_000L,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("cancel placeholder")
        viewModel.sendMessage()
        val beforeCancelMessages = viewModel.uiState.value.activeSession!!.messages
        assertTrue(beforeCancelMessages.any(::shouldRenderInThreadLoadingPlaceholder))

        viewModel.cancelActiveSend()
        advanceUntilIdle()

        val afterCancelMessages = viewModel.uiState.value.activeSession!!.messages
        assertFalse(afterCancelMessages.any(::shouldRenderInThreadLoadingPlaceholder))
        assertFalse(viewModel.uiState.value.composer.isSending)
        assertFalse(viewModel.uiState.value.composer.isCancelling)
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
        assertEquals("Generation cancelled.", viewModel.uiState.value.runtime.modelStatusDetail)
    }

    @Test
    fun `send message timeout maps to deterministic runtime error`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.CANCELLED_TIMEOUT,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeGenerationTimeoutMs = 50L,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("ola how you doin")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("timed out") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
        assertFalse(viewModel.uiState.value.composer.isSending)
    }

    @Test
    fun `cancelled terminal event preserves partial output and records terminal metadata`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTokens = listOf("partial "),
            streamTerminal = StreamTerminal.CANCELLED_MANUAL,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("cancel test")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        val assistant = active.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.contains("partial") }

        assertTrue(assistant != null)
        assertEquals("cancelled", assistant?.finishReason)
        assertTrue(assistant?.terminalEventSeen == true)
        assertTrue(assistant?.requestId?.isNotBlank() == true)
        assertFalse(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
        assertFalse(viewModel.uiState.value.composer.isSending)
    }

    @Test
    fun `failed terminal event records normalized finish reason`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTokens = listOf("chunk "),
            streamTerminal = StreamTerminal.FAILED,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("failure test")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        val assistant = active.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.contains("chunk") }
        val terminalSystem = active.messages.lastOrNull { it.role == MessageRole.SYSTEM && it.finishReason == "failed:jni_utf8_stream_error" }

        assertTrue(assistant != null)
        assertEquals("failed:jni_utf8_stream_error", assistant?.finishReason)
        assertTrue(assistant?.terminalEventSeen == true)
        assertTrue(terminalSystem != null)
        assertEquals(assistant?.requestId, terminalSystem?.requestId)
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `startup probe timeout maps to blocked timeout startup state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val startupProbeController = TimeoutStartupProbeController()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals("UI-STARTUP-001", runtimeState.lastErrorCode)
        assertEquals(
            "Startup checks timed out. Runtime readiness is unknown; refresh checks before sending.",
            runtimeState.modelStatusDetail,
        )
        assertEquals(com.pocketagent.android.ui.state.ModelRuntimeStatus.NOT_READY, runtimeState.modelRuntimeStatus)
        assertTrue(runtimeState.startupChecks.any { it.contains("timed out", ignoreCase = true) })
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.BLOCKED_TIMEOUT, runtimeState.startupProbeState)
    }

    @Test
    fun `startup probe timeout blocks send path`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.COMPLETED,
        )
        val startupProbeController = TimeoutStartupProbeController()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            runtimeGenerationTimeoutMs = 500L,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello degraded mode")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello degraded mode" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
    }

    @Test
    fun `startup timeout does not enter stream flow`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.COMPLETED,
            streamDelayMs = 100L,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            runtimeGenerationTimeoutMs = 500L,
            startupProbeController = TimeoutStartupProbeController(),
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello virtual time")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello virtual time" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
    }

    @Test
    fun `optional model warning maps to ready state with warning and no startup error`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Optional runtime model unavailable: qwen3-1.7b-q4_k_m."),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.READY, runtimeState.startupProbeState)
        assertEquals(com.pocketagent.android.ui.state.ModelRuntimeStatus.READY, runtimeState.modelRuntimeStatus)
        assertEquals(null, runtimeState.lastErrorCode)
        assertEquals(1, runtimeState.startupWarnings.size)
        assertTrue(runtimeState.modelStatusDetail?.contains("model ready") == true)
    }

    @Test
    fun `send path stays enabled when startup checks only report optional model warning`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Optional runtime model unavailable: qwen3-1.7b-q4_k_m."),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello optional warning")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.USER && it.content == "hello optional warning" })
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `battery profile uses extended adaptive timeout`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.setPerformanceProfile(RuntimePerformanceProfile.BATTERY)
        viewModel.onComposerChanged("hello with 2b")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(900_000L, runtime.lastStreamRequest?.requestTimeoutMs)
    }

    @Test
    fun `cancel active send delegates request scoped cancellation`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamDelayMs = 500L,
            streamTokens = listOf("a "),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            runtimeGenerationTimeoutMs = 1_000L,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("cancel me")
        viewModel.sendMessage()
        viewModel.cancelActiveSend()

        assertTrue(viewModel.uiState.value.composer.isCancelling)
        assertEquals("Cancelling generation...", viewModel.uiState.value.runtime.modelStatusDetail)

        advanceUntilIdle()

        assertTrue(runtime.cancelledRequestIds.isNotEmpty())
        assertFalse(viewModel.uiState.value.composer.isCancelling)
    }

    @Test
    fun `routing mode and diagnostics export update runtime and timeline`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.setRoutingMode(RoutingMode.QWEN3_1_7B)
        viewModel.exportDiagnostics()
        advanceUntilIdle()

        assertEquals(RoutingMode.QWEN3_1_7B, viewModel.uiState.value.runtime.routingMode)
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("diag=ok") })
    }

    @Test
    fun `refresh runtime readiness updates backend and startup state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )

        viewModel.refreshRuntimeReadiness()
        advanceUntilIdle()

        assertEquals("NATIVE_JNI", viewModel.uiState.value.runtime.runtimeBackend)
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
        assertTrue(runtime.cancelledSessions.isNotEmpty())
    }

    @Test
    fun `refresh runtime readiness does not mask blocking startup failures with override text`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
            runtimeBackend = "NATIVE_JNI",
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "verified and active")
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals("UI-STARTUP-001", runtimeState.lastErrorCode)
        assertEquals("Missing runtime model(s): qwen", runtimeState.modelStatusDetail)
    }

    @Test
    fun `refresh runtime readiness applies override text only when startup checks pass`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "verified and active")
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(null, runtimeState.lastErrorCode)
        assertEquals("verified and active", runtimeState.modelStatusDetail)
    }

    @Test
    fun `startup probe unexpected exception maps to blocked runtime state without crash`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            startupProbeController = ThrowingOnSecondStartupProbeController(),
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness()
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals("UI-STARTUP-001", runtimeState.lastErrorCode)
        assertTrue(runtimeState.startupChecks.any { it.contains("failed unexpectedly") })
    }

    @Test
    fun `startup probe latest refresh wins when earlier probe ignores cancellation`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            startupProbeController = NonCooperativeStartupProbeController(),
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "stale status")
        advanceTimeBy(10L)
        viewModel.refreshRuntimeReadiness(statusDetailOverride = "latest status")
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(null, runtimeState.lastErrorCode)
        assertEquals("latest status", runtimeState.modelStatusDetail)
    }

    @Test
    fun `duplicate readiness refresh requests with same detail are coalesced`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val startupProbeController = CountingStartupProbeController()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()
        val baselineCalls = startupProbeController.callCount

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "same detail")
        viewModel.refreshRuntimeReadiness(statusDetailOverride = "same detail")
        advanceUntilIdle()

        assertEquals(baselineCalls + 1, startupProbeController.callCount)
    }

    @Test
    fun `successful explicit load warms model and skips redundant startup probe rerun`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = emptyList(),
            warmupResult = WarmupResult(
                attempted = true,
                warmed = true,
                residentHit = false,
                warmupDurationMs = 25L,
            ),
        )
        val startupProbeController = CountingStartupProbeController()
        val provisioningGateway = LoadingProvisioningGateway()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            provisioningGateway = provisioningGateway,
            ioDispatcher = dispatcher,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()
        val baselineCalls = startupProbeController.callCount

        val result = viewModel.loadModel("qwen3.5-0.8b-q4", "1")
        advanceUntilIdle()

        assertTrue(result?.success == true)
        assertEquals(1, runtime.warmupCalls)
        assertEquals(baselineCalls, startupProbeController.callCount)
        assertEquals("qwen3.5-0.8b-q4", viewModel.uiState.value.runtime.activeModelId)
        assertEquals(ModelLifecycleState.LOADED, provisioningGateway.lifecycle.value.state)
    }

    @Test
    fun `duplicate settings updates do not persist or re-dispatch runtime calls`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val baselineSaves = persistence.savedStates.size
        val baselineRoutingCalls = runtime.routingModeSetCalls

        viewModel.setRoutingMode(RoutingMode.AUTO)
        viewModel.setPerformanceProfile(RuntimePerformanceProfile.BALANCED)
        viewModel.setGpuAccelerationEnabled(false)
        advanceUntilIdle()

        val afterFirstPassSaves = persistence.savedStates.size
        val afterFirstRoutingCalls = runtime.routingModeSetCalls
        assertTrue(afterFirstPassSaves == baselineSaves || afterFirstPassSaves == baselineSaves + 1)
        assertEquals(baselineRoutingCalls, afterFirstRoutingCalls)

        viewModel.setRoutingMode(RoutingMode.AUTO)
        viewModel.setPerformanceProfile(RuntimePerformanceProfile.BALANCED)
        viewModel.setGpuAccelerationEnabled(false)
        advanceUntilIdle()

        assertEquals(afterFirstPassSaves, persistence.savedStates.size)
        assertEquals(afterFirstRoutingCalls, runtime.routingModeSetCalls)
    }

    @Test
    fun `resident lifecycle model preserves persisted routing mode during startup`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val residentModel = com.pocketagent.runtime.RuntimeLoadedModel(
            modelId = com.pocketagent.inference.ModelCatalog.QWEN3_0_6B_Q4_K_M,
            modelVersion = "1",
        )
        val gateway = LifecycleOnlyProvisioningGateway(
            initialLifecycle = RuntimeModelLifecycleSnapshot(
                state = com.pocketagent.nativebridge.ModelLifecycleState.LOADED,
                loadedModel = residentModel,
                lastUsedModel = residentModel,
            ),
        )
        val persistence = RecordingPersistence(
            initialState = StoredChatState(
                routingMode = RoutingMode.QWEN_0_8B.name,
                onboardingCompleted = true,
                firstSessionStage = FirstSessionStage.READY_TO_CHAT.name,
            ),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            provisioningGateway = gateway,
            ioDispatcher = dispatcher,
        )

        advanceUntilIdle()

        // Routing mode from persistence is preserved — the lifecycle observer
        // no longer auto-syncs routing to the loaded model. Only explicit
        // user-initiated loads (finalizeModelOperation) sync routing mode.
        assertEquals(RoutingMode.QWEN_0_8B, viewModel.uiState.value.runtime.routingMode)
        assertEquals(RoutingMode.QWEN_0_8B, runtime.getRoutingMode())
        assertEquals(residentModel.modelId, viewModel.uiState.value.runtime.activeModelId)
    }

    @Test
    fun `loaded lifecycle detail is surfaced as runtime status detail`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val residentModel = RuntimeLoadedModel(
            modelId = com.pocketagent.inference.ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelVersion = "q4_0",
        )
        val gateway = LifecycleOnlyProvisioningGateway(
            initialLifecycle = RuntimeModelLifecycleSnapshot(
                state = com.pocketagent.nativebridge.ModelLifecycleState.LOADED,
                loadedModel = residentModel,
                lastUsedModel = residentModel,
                loadingDetail = "Model loaded with reduced GPU acceleration.",
                loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.COMPLETED,
                loadingProgress = 1.0f,
            ),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            provisioningGateway = gateway,
            ioDispatcher = dispatcher,
        )

        advanceUntilIdle()

        assertEquals(
            "Model loaded with reduced GPU acceleration.",
            viewModel.uiState.value.runtime.modelStatusDetail,
        )
    }

    @Test
    fun `gpu probe pending state is refreshed to terminal status`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            runtimeBackend = "NATIVE_JNI",
            gpuStatusSequence = mutableListOf(
                com.pocketagent.android.runtime.GpuProbeResult(
                    status = com.pocketagent.android.runtime.GpuProbeStatus.PENDING,
                ),
                com.pocketagent.android.runtime.GpuProbeResult(
                    status = com.pocketagent.android.runtime.GpuProbeStatus.FAILED,
                    failureReason = com.pocketagent.android.runtime.GpuProbeFailureReason.MODEL_UNAVAILABLE,
                ),
            ),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )

        advanceTimeBy(2_000L)
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(com.pocketagent.android.runtime.GpuProbeStatus.FAILED, runtimeState.gpuProbeStatus)
        assertFalse(runtimeState.gpuAccelerationSupported)
    }

    @Test
    fun `persistence queue tracks median duration and payload size`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            presetBackingStore = FakePresetBackingStore(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        val metrics = viewModel.persistenceMetricsSnapshot()
        assertTrue(metrics.writeCount > 0)
        assertTrue(metrics.lastPayloadBytes > 0)
        assertTrue(metrics.medianPayloadBytes > 0)
        assertTrue(metrics.lastPersistDurationMs >= 0L)
        assertTrue(metrics.medianPersistDurationMs >= 0L)
    }
}

private fun ChatSessionUiModel.toStoredSession(clearStreaming: Boolean = false): StoredChatSession {
    return StoredChatSession(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages.map { it.toStoredMessage(clearStreaming = clearStreaming) },
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = if (messagesLoaded) messages.size else messageCount,
    )
}

private fun MessageUiModel.toStoredMessage(clearStreaming: Boolean = false): StoredChatMessage {
    return StoredChatMessage(
        id = id,
        role = role,
        content = content,
        timestampEpochMs = timestampEpochMs,
        kind = kind,
        imagePath = imagePath,
        imagePaths = imagePaths,
        toolName = toolName,
        isStreaming = if (clearStreaming) false else isStreaming,
        requestId = requestId,
        finishReason = finishReason,
        terminalEventSeen = terminalEventSeen,
        isThinking = isThinking,
        interaction = interaction,
        reasoningContent = reasoningContent,
        firstTokenMs = firstTokenMs,
        tokensPerSec = tokensPerSec,
        totalLatencyMs = totalLatencyMs,
    )
}

private fun shouldRenderInThreadLoadingPlaceholder(message: MessageUiModel): Boolean {
    return message.role == MessageRole.ASSISTANT && message.isStreaming && message.content.isBlank()
}

private class RecordingPersistence(
    private val initialState: StoredChatState = StoredChatState(),
    private val sessionMessages: Map<String, List<StoredChatMessage>> = emptyMap(),
) : SessionPersistence {
    val savedStates = mutableListOf<StoredChatState>()
    private var current = initialState

    override fun loadState(): StoredChatState = current

    override fun loadBootstrapState(): StoredChatState = current

    override fun loadSessionMessages(sessionId: String): List<StoredChatMessage>? {
        return sessionMessages[sessionId]
    }

    override fun saveState(state: StoredChatState) {
        current = state
        savedStates += state
    }
}

private class CorruptLoadPersistence : SessionPersistence {
    private var persisted = StoredChatState()

    override fun loadState(): StoredChatState = persisted

    override fun loadStateResult(): SessionStateLoadResult {
        return SessionStateLoadResult.RecoverableCorruption(
            resetState = StoredChatState(),
            code = "CHAT_STATE_CORRUPT_JSON",
            userMessage = "Saved chat state was corrupted and reset.",
            technicalDetail = "code=CHAT_STATE_CORRUPT_JSON;backup=chat-state-1;error=parse",
        )
    }

    override fun loadBootstrapStateResult(): SessionStateLoadResult = loadStateResult()

    override fun saveState(state: StoredChatState) {
        persisted = state
    }
}

private class LifecycleOnlyProvisioningGateway(
    initialLifecycle: RuntimeModelLifecycleSnapshot,
) : ProvisioningGateway {
    private val lifecycle = MutableStateFlow(initialLifecycle)

    override fun currentSnapshot(): RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot(
        models = emptyList(),
        storageSummary = StorageSummary(
            totalBytes = 0L,
            freeBytes = 0L,
            usedByModelsBytes = 0L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = emptySet(),
    )

    override fun observeDownloads(): MutableStateFlow<List<DownloadTaskState>> = MutableStateFlow(emptyList())

    override fun observeDownloadPreferences(): MutableStateFlow<DownloadPreferencesState> =
        MutableStateFlow(DownloadPreferencesState())

    override fun currentDownloadPreferences(): DownloadPreferencesState = DownloadPreferencesState()

    override fun observeModelLifecycle(): MutableStateFlow<RuntimeModelLifecycleSnapshot> = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        error("not used in ChatViewModelTest")
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> = emptyList()

    override fun setActiveVersion(modelId: String, version: String): Boolean = false

    override fun clearActiveVersion(modelId: String): Boolean = false

    override fun removeVersion(modelId: String, version: String): Boolean = false

    override suspend fun loadInstalledModel(
        modelId: String,
        version: String,
    ): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun loadLastUsedModel(): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun offloadModel(reason: String): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        error("not used in ChatViewModelTest")
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) = Unit

    override fun acknowledgeLargeDownloadCellularWarning() = Unit

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit

    override fun syncDownloadsFromScheduler() = Unit
}

private class RecordingRuntimeFacade(
    private val failImage: Boolean = false,
    private val returnImageValidationError: Boolean = false,
    private val startupChecks: List<String> = emptyList(),
    private val streamTokens: List<String> = listOf("stream ", "token "),
    private val streamDelayMs: Long = 0L,
    private val streamTerminal: StreamTerminal = StreamTerminal.COMPLETED,
    private val runtimeBackend: String? = null,
    private val runtimeDiagnostics: RuntimeDiagnosticsSnapshot = RuntimeDiagnosticsSnapshot(),
    private val gpuStatusSequence: MutableList<com.pocketagent.android.runtime.GpuProbeResult> = mutableListOf(),
    private val warmupResult: WarmupResult = WarmupResult.skipped("warmup_unsupported"),
) : ChatRuntimeService {
    private var sessionCounter = 0
    private var routingMode: RoutingMode = RoutingMode.AUTO
    var failTool: Boolean = false
    var returnToolValidationError: Boolean = false
    var routingModeSetCalls: Int = 0
    val restoredTurns = mutableListOf<Pair<SessionId, List<Turn>>>()
    val cancelledSessions = mutableListOf<SessionId>()
    val cancelledRequestIds = mutableListOf<String>()
    var lastStreamRequest: StreamChatRequestV2? = null
    var warmupCalls: Int = 0

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = flow {
        val request = prepared.runtimeRequest
        lastStreamRequest = request
        emit(
            ChatStreamEvent.Started(
                requestId = request.requestId,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (streamDelayMs > 0L) {
            delay(streamDelayMs)
        }
        val builder = StringBuilder()
        streamTokens.forEach { token ->
            builder.append(token)
            emit(
                ChatStreamEvent.Delta(
                    requestId = request.requestId,
                    delta = ChatStreamDelta.TextDelta(token),
                    accumulatedText = builder.toString(),
                ),
            )
        }
        val latestUser = request.messages
            .asReversed()
            .firstOrNull { message -> message.role == InteractionRole.USER }
            ?.parts
            ?.filterIsInstance<InteractionContentPart.Text>()
            ?.joinToString(separator = "\n") { it.text }
            .orEmpty()
        when (streamTerminal) {
            StreamTerminal.COMPLETED -> emit(
                ChatStreamEvent.Completed(
                    requestId = request.requestId,
                    response = ChatResponse(
                        sessionId = request.sessionId,
                        modelId = "auto",
                        text = "response for $latestUser",
                        firstTokenLatencyMs = 25,
                        totalLatencyMs = 75,
                    ),
                    finishReason = "completed",
                    firstTokenMs = 25,
                    completionMs = 75,
                ),
            )

            StreamTerminal.CANCELLED_TIMEOUT -> emit(
                ChatStreamEvent.Cancelled(
                    requestId = request.requestId,
                    reason = "timeout",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )

            StreamTerminal.CANCELLED_MANUAL -> emit(
                ChatStreamEvent.Cancelled(
                    requestId = request.requestId,
                    reason = "cancelled",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )

            StreamTerminal.FAILED -> emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "jni_utf8_stream_error",
                    message = "utf8 stream failure",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )
        }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        cancelledSessions += sessionId
        return true
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelledRequestIds += requestId
        return true
    }

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        if (returnToolValidationError) {
            return ToolExecutionResult.Failure(
                ToolFailure.Validation(
                    code = "invalid_field_value",
                    userMessage = "That tool request was rejected for safety.",
                    technicalDetail = "Field 'expression' has disallowed characters.",
                ),
            )
        }
        if (failTool) {
            error("simulated tool failure")
        }
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        if (returnImageValidationError) {
            return ImageAnalysisResult.Failure(
                ImageFailure.Validation(
                    code = "unsupported_extension",
                    userMessage = "That image could not be processed. Use a supported file and try again.",
                    technicalDetail = "extension 'tiff' is not supported",
                ),
            )
        }
        if (failImage) {
            error("simulated image failure")
        }
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
        routingModeSetCalls += 1
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = startupChecks

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoredTurns += sessionId to turns
    }

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = runtimeBackend

    override fun runtimeDiagnosticsSnapshot(): RuntimeDiagnosticsSnapshot = runtimeDiagnostics

    override fun supportsGpuOffload(): Boolean {
        val current = gpuStatusSequence.firstOrNull() ?: return false
        return current.status == com.pocketagent.android.runtime.GpuProbeStatus.QUALIFIED &&
            current.maxStableGpuLayers > 0
    }

    override fun warmupActiveModel(): WarmupResult {
        warmupCalls += 1
        return warmupResult
    }

    override fun gpuOffloadStatus(): com.pocketagent.android.runtime.GpuProbeResult {
        if (gpuStatusSequence.isEmpty()) {
            return com.pocketagent.android.runtime.GpuProbeResult(
                status = com.pocketagent.android.runtime.GpuProbeStatus.FAILED,
                failureReason = com.pocketagent.android.runtime.GpuProbeFailureReason.UNKNOWN,
            )
        }
        return gpuStatusSequence.removeAt(0)
    }
}

private class LoadingProvisioningGateway : ProvisioningGateway {
    val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())

    override fun currentSnapshot(): RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot(
        models = emptyList(),
        storageSummary = StorageSummary(
            totalBytes = 0L,
            freeBytes = 0L,
            usedByModelsBytes = 0L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = emptySet(),
    )

    override fun observeDownloads(): MutableStateFlow<List<DownloadTaskState>> = MutableStateFlow(emptyList())

    override fun observeDownloadPreferences(): MutableStateFlow<DownloadPreferencesState> =
        MutableStateFlow(DownloadPreferencesState())

    override fun currentDownloadPreferences(): DownloadPreferencesState = DownloadPreferencesState()

    override fun observeModelLifecycle(): MutableStateFlow<RuntimeModelLifecycleSnapshot> = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        error("not used in ChatViewModelTest")
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> = emptyList()

    override fun setActiveVersion(modelId: String, version: String): Boolean = false

    override fun clearActiveVersion(modelId: String): Boolean = false

    override fun removeVersion(modelId: String, version: String): Boolean = false

    override suspend fun loadInstalledModel(
        modelId: String,
        version: String,
    ): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        val loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycle.value = RuntimeModelLifecycleSnapshot(
            state = ModelLifecycleState.LOADED,
            loadedModel = loadedModel,
            lastUsedModel = loadedModel,
        )
        return com.pocketagent.runtime.RuntimeModelLifecycleCommandResult.applied(loadedModel = loadedModel)
    }

    override suspend fun loadLastUsedModel(): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun offloadModel(reason: String): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        error("not used in ChatViewModelTest")
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) = Unit

    override fun acknowledgeLargeDownloadCellularWarning() = Unit

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit

    override fun syncDownloadsFromScheduler() = Unit
}

private class QuickLoadProvisioningGatewayForTest(
    private val modelId: String = "qwen3.5-0.8b-q4",
    private val modelVersion: String = "v1",
) : ProvisioningGateway {
    private val downloads = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    private val preferences = MutableStateFlow(DownloadPreferencesState())
    private val lifecycle = MutableStateFlow(
        RuntimeModelLifecycleSnapshot.initial().copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            lastUsedModel = RuntimeLoadedModel(
                modelId = modelId,
                modelVersion = modelVersion,
            ),
        ),
    )

    var loadLastUsedCalls: Int = 0
        private set
    val offloadReasons = mutableListOf<String>()

    override fun currentSnapshot(): RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = modelId,
                displayName = "Qwen",
                fileName = "qwen.gguf",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                importedAtEpochMs = 1L,
                activeVersion = modelVersion,
                installedVersions = listOf(
                    ModelVersionDescriptor(
                        modelId = modelId,
                        version = modelVersion,
                        displayName = "Qwen",
                        absolutePath = "/tmp/qwen.gguf",
                        sha256 = "a".repeat(64),
                        provenanceIssuer = "issuer",
                        provenanceSignature = "sig",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 123L,
                        importedAtEpochMs = 1L,
                        isActive = true,
                    ),
                ),
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 1_000L,
            freeBytes = 500L,
            usedByModelsBytes = 250L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = setOf(modelId),
    )

    override fun observeDownloads(): StateFlow<List<DownloadTaskState>> = downloads

    override fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> = preferences

    override fun currentDownloadPreferences(): DownloadPreferencesState = preferences.value

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        error("not used in ChatViewModelTest")
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        error("not used in ChatViewModelTest")
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return currentSnapshot().models.firstOrNull { it.modelId == modelId }?.installedVersions.orEmpty()
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean = true

    override fun clearActiveVersion(modelId: String): Boolean = true

    override fun removeVersion(modelId: String, version: String): Boolean = true

    override suspend fun loadInstalledModel(
        modelId: String,
        version: String,
    ): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        val loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.LOADED,
            loadedModel = loadedModel,
            requestedModel = null,
            lastUsedModel = loadedModel,
            errorCode = null,
            errorDetail = null,
        )
        return com.pocketagent.runtime.RuntimeModelLifecycleCommandResult.applied(loadedModel = loadedModel)
    }

    override suspend fun loadLastUsedModel(): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        loadLastUsedCalls += 1
        val lastUsed = lifecycle.value.lastUsedModel
            ?: return com.pocketagent.runtime.RuntimeModelLifecycleCommandResult.rejected(
                code = com.pocketagent.nativebridge.ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "last_loaded_model_missing",
            )
        return loadInstalledModel(
            modelId = lastUsed.modelId,
            version = lastUsed.modelVersion.orEmpty(),
        )
    }

    override suspend fun offloadModel(reason: String): com.pocketagent.runtime.RuntimeModelLifecycleCommandResult {
        offloadReasons += reason
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            requestedModel = null,
            errorCode = null,
            errorDetail = null,
            queuedOffload = false,
        )
        return com.pocketagent.runtime.RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        error("not used in ChatViewModelTest")
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) = Unit

    override fun acknowledgeLargeDownloadCellularWarning() = Unit

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit

    override fun syncDownloadsFromScheduler() = Unit
}

private enum class StreamTerminal {
    COMPLETED,
    CANCELLED_TIMEOUT,
    CANCELLED_MANUAL,
    FAILED,
}

private class TimeoutStartupProbeController : StartupProbeController() {
    override suspend fun runStartupChecks(
        runtimeGateway: ChatRuntimeService,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        val timeoutSeconds = (timeoutMs / 1000L).coerceAtLeast(1L)
        return listOf("Startup checks timed out after ${timeoutSeconds}s.")
    }
}

private class ThrowingOnSecondStartupProbeController : StartupProbeController() {
    private var calls: Int = 0

    override suspend fun runStartupChecks(
        runtimeGateway: ChatRuntimeService,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        calls += 1
        if (calls == 1) {
            return emptyList()
        }
        error("startup probe exploded")
    }
}

private class NonCooperativeStartupProbeController : StartupProbeController() {
    private var calls: Int = 0

    override suspend fun runStartupChecks(
        runtimeGateway: ChatRuntimeService,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        calls += 1
        return when (calls) {
            1 -> emptyList()
            2 -> {
                try {
                    delay(50L)
                } catch (_: CancellationException) {
                    // Simulate a non-cooperative startup check that ignores cancellation.
                }
                listOf("Missing runtime model(s): stale-check")
            }
            else -> emptyList()
        }
    }
}

private class CountingStartupProbeController : StartupProbeController() {
    var callCount: Int = 0

    override suspend fun runStartupChecks(
        runtimeGateway: ChatRuntimeService,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        callCount += 1
        return emptyList()
    }
}
