package com.pocketagent.android.ui

import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.AppDispatchers
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.android.runtime.missingRequiredArtifactsFromTechnicalDetail
import com.pocketagent.android.runtime.missingRequiredArtifactsUserMessage
import com.pocketagent.android.ui.controllers.ChatPersistenceFlow
import com.pocketagent.android.ui.controllers.ChatStreamCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceQueue
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.controllers.AndroidChatConversationService
import com.pocketagent.android.ui.controllers.AndroidChatSessionService
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.ChatSendController
import com.pocketagent.android.ui.controllers.PersistenceQueueMetrics
import com.pocketagent.android.ui.controllers.ChatStartupProbeOrchestrator
import com.pocketagent.android.ui.controllers.SendReducer
import com.pocketagent.android.ui.controllers.ChatStartupFlow
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.android.ui.controllers.StartupReadinessCoordinator
import com.pocketagent.android.ui.controllers.TimelineProjector
import com.pocketagent.android.ui.controllers.ToolLoopUseCase
import com.pocketagent.android.runtime.PresetBackingStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.activeSession
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamingState
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.android.ui.state.toModelLoadingState
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SendOperationLease(
    val operationId: String,
    val sessionId: String,
    initialRequestId: String,
) {
    private val requestId = AtomicReference(initialRequestId)
    private val cancellationRequested = AtomicBoolean(false)
    private val userCancellationRequested = AtomicBoolean(false)
    private val userCancellationRequestId = AtomicReference<String?>(null)
    private val operationJob = AtomicReference<Job?>(null)
    private val messageId = AtomicReference<String?>(null)
    private val toolCallIds = AtomicReference<Set<String>>(emptySet())

    fun currentRequestId(): String = requestId.get()

    fun transitionRequest(expectedRequestId: String, nextRequestId: String): Boolean {
        return requestId.compareAndSet(expectedRequestId, nextRequestId)
    }

    fun requestCancellation(userInitiated: Boolean): String {
        cancellationRequested.set(true)
        val observedRequestId = currentRequestId()
        if (userInitiated) {
            userCancellationRequested.set(true)
            userCancellationRequestId.compareAndSet(null, observedRequestId)
            return userCancellationRequestId.get() ?: observedRequestId
        }
        return observedRequestId
    }

    fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun isUserCancellationRequested(): Boolean = userCancellationRequested.get()

    fun userCancellationRequestId(): String? = userCancellationRequestId.get()

    fun attachJob(job: Job) {
        check(operationJob.compareAndSet(null, job)) { "Send operation job already attached." }
        if (isCancellationRequested()) {
            job.cancel()
        }
    }

    fun cancelJob() {
        operationJob.get()?.cancel()
    }

    fun setCurrentMessageId(value: String?) {
        messageId.set(value)
    }

    fun currentMessageId(): String? = messageId.get()

    fun trackToolCallIds(ids: Collection<String>) {
        if (ids.isEmpty()) {
            return
        }
        while (true) {
            val current = toolCallIds.get()
            val next = current + ids
            if (toolCallIds.compareAndSet(current, next)) {
                return
            }
        }
    }

    fun ownedToolCallIds(): Set<String> = toolCallIds.get()
}

class ChatViewModel internal constructor(
    internal val runtimeFacade: ChatRuntimeService,
    sessionPersistence: SessionPersistence,
    internal val presetBackingStore: PresetBackingStore,
    internal val dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
    internal val ioDispatcher: CoroutineDispatcher = dispatchers.io,
    internal val runtimeGenerationTimeoutMs: Long = 0L,
    private val runtimeStartupProbeTimeoutMs: Long = DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS,
    internal val sendController: ChatSendController = ChatSendController(runtimeFacade, ioDispatcher),
    internal val streamCoordinator: ChatStreamCoordinator = ChatStreamCoordinator(),
    private val startupProbeController: StartupProbeController = StartupProbeController(),
    private val startupReadinessCoordinator: StartupReadinessCoordinator = StartupReadinessCoordinator(
        runtimeProfile = resolveModelRuntimeProfile(isDebugBuild = BuildConfig.DEBUG),
    ),
    private val persistenceCoordinator: ChatPersistenceCoordinator = ChatPersistenceCoordinator(sessionPersistence),
    internal val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    internal val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
    internal val sessionService: AndroidChatSessionService = AndroidChatSessionService(),
    internal val conversationService: AndroidChatConversationService = AndroidChatConversationService(sessionService),
    internal val timelineProjector: TimelineProjector = TimelineProjector(),
    internal val persistenceFlow: ChatPersistenceFlow = ChatPersistenceFlow(persistenceCoordinator),
    internal val startupFlow: ChatStartupFlow = ChatStartupFlow(
        runtimeGateway = runtimeFacade,
        startupProbeController = startupProbeController,
        startupReadinessCoordinator = startupReadinessCoordinator,
        ioDispatcher = ioDispatcher,
        runtimeStartupProbeTimeoutMs = runtimeStartupProbeTimeoutMs,
        nativeRuntimeLibraryPackaged = BuildConfig.NATIVE_RUNTIME_LIBRARY_PACKAGED,
        sessionService = sessionService,
        timelineProjector = timelineProjector,
    ),
    internal val sendFlow: ChatSendFlow = ChatSendFlow(
        runtimeGenerationTimeoutMs = runtimeGenerationTimeoutMs,
        deviceStateProvider = deviceStateProvider,
        runtimeTuning = runtimeTuning,
        preparationDispatcher = ioDispatcher,
    ),
    internal val sendReducer: SendReducer = SendReducer(),
    internal val toolLoopUseCase: ToolLoopUseCase = ToolLoopUseCase(sendController),
) : ViewModel() {
    internal val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    val bootstrapCompletedFlow: StateFlow<Boolean> = _uiState
        .map { it.bootstrapCompleted }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.bootstrapCompleted)
    val composerFlow: StateFlow<ComposerUiState> = _uiState
        .map { it.composer }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.composer)
    val runtimeFlow: StateFlow<RuntimeUiState> = _uiState
        .map { it.runtime }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.runtime)
    val sessionsFlow: StateFlow<List<ChatSessionUiModel>> = _uiState
        .map { it.sessions }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.sessions)
    val activeSessionIdFlow: StateFlow<String?> = _uiState
        .map { it.activeSessionId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.activeSessionId)
    val activeSessionFlow: StateFlow<ChatSessionUiModel?> = _uiState
        .map { it.activeSession() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.activeSession())
    val currentThinkingEnabledFlow: StateFlow<Boolean> = _uiState
        .map { state -> state.activeSession()?.completionSettings?.showThinking == true }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            _uiState.value.activeSession()?.completionSettings?.showThinking == true,
        )
    val defaultThinkingEnabledFlow: StateFlow<Boolean> = _uiState
        .map { it.defaultThinkingEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.defaultThinkingEnabled)
    // Narrow flow for the active session's completion settings. Used by the Completion
    // Settings modal sheet, which previously read state.activeSession()?.completionSettings
    // off the full uiState — that observation pulled the entire ChatUiState through Compose
    // and re-evaluated the O(N) activeSession() lookup on every emission, even during typing.
    val currentCompletionSettingsFlow: StateFlow<CompletionSettings> = _uiState
        .map { state -> state.activeSession()?.completionSettings ?: CompletionSettings() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            _uiState.value.activeSession()?.completionSettings ?: CompletionSettings(),
        )
    val activeSurfaceFlow: StateFlow<ModalSurface> = _uiState
        .map { it.activeSurface }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.activeSurface)
    val advancedUnlockedFlow: StateFlow<Boolean> = _uiState
        .map { it.advancedUnlocked }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.advancedUnlocked)
    val onboardingPageFlow: StateFlow<Int> = _uiState
        .map { it.onboardingPage }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.onboardingPage)
    val streamingFlow: StateFlow<StreamingState> = _uiState
        .map { it.streaming }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.streaming)
    internal var gpuProbeRefreshJob: Job? = null
    private val activeSendOperation = AtomicReference<SendOperationLease?>(null)
    internal val sessionMutationInFlight = AtomicBoolean(false)
    internal val activeSendRequestId: String?
        get() = activeSendOperation.get()?.currentRequestId()
    @Volatile
    internal var lastKeepAliveTouchAtMs: Long = 0L
    internal val persistenceQueue = ChatPersistenceQueue(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        toStoredState = { state -> persistenceFlow.toStoredState(state) },
        saveStoredState = { stored -> persistenceFlow.saveStoredState(stored) },
        debounceMs = CHAT_PERSIST_DEBOUNCE_MS,
        onMetrics = { metrics ->
            val shouldLog = metrics.writeCount <= 3 || metrics.writeCount % 8 == 0
            if (shouldLog) {
                runCatching {
                    Log.i(
                        LOG_TAG,
                        "CHAT_PERSIST|writes=${metrics.writeCount}|last_ms=${metrics.lastPersistDurationMs}|" +
                            "median_ms=${metrics.medianPersistDurationMs}|last_bytes=${metrics.lastPayloadBytes}|" +
                            "median_bytes=${metrics.medianPayloadBytes}",
                    )
                }
            }
        },
    )
    internal val startupProbeOrchestrator = ChatStartupProbeOrchestrator(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        runtimeGateway = runtimeFacade,
        startupFlow = startupFlow,
        startupReadinessCoordinator = startupReadinessCoordinator,
        updateState = { transform -> _uiState.update(transform) },
        onPersist = { persistState() },
        onProbeApplied = {
            ensureRuntimeSessionAfterReadyProbeInternal()
            refreshGpuProbeStatusIfPendingInternal()
            refreshRuntimeDiagnostics()
        },
        log = { phase, probeToken, detail, error ->
            logStartupProbeInternal(
                phase = phase,
                probeToken = probeToken,
                statusDetailOverride = detail,
                error = error,
            )
        },
    )

    init {
        viewModelScope.launch(ioDispatcher) {
            bootstrapStateInternal()
        }
    }

    fun onComposerChanged(text: String) {
        updateComposerText(text)
        if (text.isBlank()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveTouchAtMs >= COMPOSER_KEEP_ALIVE_TOUCH_DEBOUNCE_MS) {
            lastKeepAliveTouchAtMs = now
            viewModelScope.launch(ioDispatcher) {
                runtimeFacade.touchKeepAlive()
            }
        }
    }

    internal fun applyComposerDraft(text: String) {
        updateComposerText(text)
    }

    private fun updateComposerText(text: String) {
        _uiState.update { state ->
            if (state.composer.text == text) {
                state
            } else {
                state.copy(composer = state.composer.copy(text = text))
            }
        }
    }

    fun sendMessage() {
        sendMessageInternal()
    }

    fun editMessage(messageId: String) {
        editMessageInternal(messageId)
    }

    fun cancelEdit() {
        cancelEditInternal()
    }

    fun submitEdit() {
        submitEditInternal()
    }

    fun regenerateResponse(messageId: String) {
        regenerateResponseInternal(messageId)
    }

    fun updateSessionCompletionSettings(settings: CompletionSettings) {
        updateSessionCompletionSettingsInternal(settings)
    }

    fun toggleSessionThinking() {
        val activeSession = _uiState.value.activeSession() ?: return
        updateSessionCompletionSettingsInternal(
            activeSession.completionSettings.copy(
                showThinking = !activeSession.completionSettings.showThinking,
            ),
        )
    }

    fun setDefaultThinkingEnabled(enabled: Boolean) {
        if (_uiState.value.defaultThinkingEnabled == enabled) {
            return
        }
        _uiState.update { state ->
            state.copy(defaultThinkingEnabled = enabled)
        }
        persistState()
    }

    fun addAttachedImage(imagePath: String) {
        addAttachedImageInternal(imagePath)
    }

    fun removeAttachedImage(index: Int) {
        removeAttachedImageInternal(index)
    }

    fun cancelActiveSend() {
        val lease = currentSendOperation() ?: return
        val requestId = lease.requestCancellation(userInitiated = true)
        runtimeFacade.cancelGenerationByRequest(requestId)
        _uiState.update { state ->
            if (!isActiveSendOperation(lease)) {
                state
            } else {
                state.copy(
                    composer = state.composer.copy(isCancelling = true),
                    runtime = state.runtime.copy(
                        modelStatusDetail = "Cancelling generation...",
                    ).clearError(),
                )
            }
        }
        lease.cancelJob()
    }

    fun createSession() {
        createSessionInternal()
    }

    fun switchSession(sessionId: String) {
        switchSessionInternal(sessionId)
    }

    fun deleteSession(sessionId: String) {
        deleteSessionInternal(sessionId)
    }

    fun attachImage(imagePath: String) {
        attachImageInternal(imagePath)
    }

    fun runTool(toolName: String, jsonArgs: String) {
        runToolInternal(toolName, jsonArgs)
    }

    fun exportDiagnostics() {
        exportDiagnosticsInternal()
    }

    fun setRoutingMode(mode: RoutingMode) {
        setRoutingModeInternal(mode)
    }

    fun setModelPreset(preset: ModelPreset) {
        val mode = presetBackingStore.routingModeForPreset(preset)
        setRoutingModeInternal(mode)
    }

    internal fun setCustomPresetBacking(preset: ModelPreset, modelId: String) {
        presetBackingStore.setCustomBackingModelId(preset, modelId)
    }

    fun resetPresetMappingsToDefaults() {
        presetBackingStore.resetToDefaults()
    }

    fun setPerformanceProfile(profile: RuntimePerformanceProfile) {
        setPerformanceProfileInternal(profile)
    }

    fun setKeepAlivePreference(preference: RuntimeKeepAlivePreference) {
        setKeepAlivePreferenceInternal(preference)
    }

    fun setGpuAccelerationEnabled(enabled: Boolean) {
        setGpuAccelerationEnabledInternal(enabled)
    }

    fun showSurface(surface: ModalSurface) {
        _uiState.update { it.copy(activeSurface = surface) }
    }

    fun dismissSurface() {
        _uiState.update { it.copy(activeSurface = ModalSurface.None) }
    }

    fun prefillComposer(text: String) {
        prefillComposerInternal(text)
    }

    fun nextOnboardingPage() {
        nextOnboardingPageInternal()
    }

    fun setOnboardingPage(page: Int) {
        setOnboardingPageInternal(page)
    }

    fun completeOnboarding() {
        completeOnboardingInternal()
    }

    fun skipOnboarding() {
        skipOnboardingInternal()
    }

    fun refreshRuntimeReadiness(statusDetailOverride: String? = null) {
        refreshRuntimeReadinessInternal(statusDetailOverride)
    }

    fun onGetReadyTapped() {
        onGetReadyTappedInternal()
    }

    fun onFirstAnswerCompleted() {
        onFirstAnswerCompletedInternal()
    }

    fun onFollowUpCompleted() {
        onFollowUpCompletedInternal()
    }

    fun onAdvancedUnlocked() {
        onAdvancedUnlockedInternal()
    }

    @Suppress("ComplexCondition")
    internal fun updateStreamingMessage(
        sessionId: String,
        messageId: String,
        text: String,
        isThinking: Boolean? = null,
    ) {
        _uiState.update { state ->
            val current = state.streaming
            val nextThinking = isThinking ?: current.isThinking
            if (
                current.sessionId == sessionId &&
                current.messageId == messageId &&
                current.text == text &&
                current.isThinking == nextThinking
            ) {
                state
            } else {
                state.copy(
                    streaming = StreamingState(
                        sessionId = sessionId,
                        messageId = messageId,
                        text = text,
                        isThinking = nextThinking,
                    ),
                )
            }
        }
    }

    internal fun finalizeStreamingMessage(
        sessionId: String,
        messageId: String,
        finalText: String,
        role: MessageRole = MessageRole.ASSISTANT,
        reasoningContent: String? = null,
        toolCalls: List<PersistedToolCall>? = null,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = true,
        firstTokenMs: Long? = null,
        tokensPerSec: Double? = null,
        totalLatencyMs: Long? = null,
    ) {
        updateActiveSession(sessionId) { session ->
            val updatedMessages = session.messages.map { message ->
                if (message.id != messageId) {
                    message
                } else {
                    message.copy(
                        role = role,
                        content = finalText,
                        isStreaming = false,
                        isThinking = false,
                        timestampEpochMs = System.currentTimeMillis(),
                        requestId = requestId ?: message.requestId,
                        finishReason = finishReason,
                        terminalEventSeen = terminalEventSeen,
                        interaction = (message.interaction ?: PersistedInteractionMessage(
                            role = role.name,
                            parts = listOf(PersistedInteractionPart(type = "text", text = finalText)),
                        )).copy(
                            role = role.name,
                            parts = listOf(PersistedInteractionPart(type = "text", text = finalText)),
                            toolCalls = toolCalls ?: message.interaction?.toolCalls.orEmpty(),
                            metadata = (message.interaction?.metadata ?: emptyMap()) + ("state" to "final"),
                        ),
                        reasoningContent = reasoningContent,
                        firstTokenMs = firstTokenMs,
                        tokensPerSec = tokensPerSec,
                        totalLatencyMs = totalLatencyMs,
                    )
                }
            }
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        _uiState.update { state ->
            if (state.streaming.sessionId == sessionId && state.streaming.messageId == messageId) {
                state.copy(streaming = StreamingState())
            } else {
                state
            }
        }
    }

    internal fun appendSystemMessage(
        sessionId: String,
        content: String,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = false,
    ) {
        updateActiveSession(sessionId) { session ->
            session.copy(
                messages = session.messages + createMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    kind = MessageKind.TEXT,
                    requestId = requestId,
                    finishReason = finishReason,
                    terminalEventSeen = terminalEventSeen,
                ),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    internal fun messageContent(
        sessionId: String,
        messageId: String,
    ): String? {
        val state = _uiState.value
        if (state.streaming.sessionId == sessionId && state.streaming.messageId == messageId) {
            return state.streaming.text
        }
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return null
        return session.messages.firstOrNull { it.id == messageId }?.content
    }

    internal fun updateActiveSession(
        sessionId: String,
        transform: (ChatSessionUiModel) -> ChatSessionUiModel,
    ) {
        _uiState.update { state ->
            val updatedSessions = state.sessions.map { session ->
                if (session.id == sessionId) {
                    normalizeSession(transform(session))
                } else {
                    session
                }
            }
            state.copy(sessions = updatedSessions)
        }
    }

    private fun normalizeSession(session: ChatSessionUiModel): ChatSessionUiModel {
        return sessionService.normalize(session)
    }

    internal fun persistState() {
        persistenceQueue.enqueue(_uiState.value)
    }

    internal fun persistenceMetricsSnapshot(): PersistenceQueueMetrics {
        return persistenceQueue.metricsSnapshot()
    }

    override fun onCleared() {
        startupProbeOrchestrator.cancel()
        gpuProbeRefreshJob?.cancel()
        persistenceQueue.close()
        super.onCleared()
    }

    internal fun executeToolCommand(
        sessionId: String,
        toolName: String,
        jsonArgs: String,
        toolCallId: String,
    ) {
        executeToolCommandInternal(
            sessionId = sessionId,
            toolName = toolName,
            jsonArgs = jsonArgs,
            toolCallId = toolCallId,
        )
    }

    internal fun updateToolCallStatus(
        sessionId: String,
        toolCallId: String,
        status: PersistedToolCallStatus,
    ) {
        updateToolCallStatusInternal(
            sessionId = sessionId,
            toolCallId = toolCallId,
            status = status,
        )
    }

    internal fun createMessage(
        role: MessageRole,
        content: String,
        kind: MessageKind,
        imagePath: String? = null,
        imagePaths: List<String> = emptyList(),
        toolName: String? = null,
        toolArgsJson: String? = null,
        toolCallId: String? = null,
        toolCallStatus: PersistedToolCallStatus = PersistedToolCallStatus.PENDING,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = false,
    ): MessageUiModel {
        return createMessageInternal(
            role = role,
            content = content,
            kind = kind,
            imagePath = imagePath,
            imagePaths = imagePaths,
            toolName = toolName,
            toolArgsJson = toolArgsJson,
            toolCallId = toolCallId,
            toolCallStatus = toolCallStatus,
            requestId = requestId,
            finishReason = finishReason,
            terminalEventSeen = terminalEventSeen,
        )
    }

    internal fun maybeAdvanceAfterAssistantResponse() {
        maybeAdvanceAfterAssistantResponseInternal()
    }

    internal fun ensureSimpleFirstEnteredTelemetryIfNeeded() {
        ensureSimpleFirstEnteredTelemetryIfNeededInternal()
    }

    internal fun recordFirstSessionEventOnce(eventName: String) {
        recordFirstSessionEventOnceInternal(eventName)
    }

    internal fun tryAcquireSendOperation(lease: SendOperationLease): Boolean {
        return activeSendOperation.compareAndSet(null, lease)
    }

    internal fun currentSendOperation(): SendOperationLease? = activeSendOperation.get()

    internal fun isActiveSendOperation(lease: SendOperationLease): Boolean {
        return activeSendOperation.get() === lease
    }

    internal fun releaseSendOperation(lease: SendOperationLease): Boolean {
        return activeSendOperation.compareAndSet(lease, null)
    }

    internal fun syncRuntimeModelLoadingState(nextState: ModelLoadingState) {
        when (nextState) {
            is ModelLoadingState.Idle -> {
                _uiState.update { state ->
                    val activeModelId = nextState.loadedModel?.modelId
                    state.copy(
                        runtime = state.runtime.copy(
                            activeModelId = activeModelId,
                            modelRuntimeStatus = if (activeModelId == null) {
                                ModelRuntimeStatus.NOT_READY
                            } else {
                                state.runtime.modelRuntimeStatus
                            },
                            modelStatusDetail = if (activeModelId == null) {
                                "No model loaded."
                            } else {
                                state.runtime.modelStatusDetail
                            },
                            startupProbeState = if (activeModelId == null) {
                                StartupProbeState.IDLE
                            } else {
                                state.runtime.startupProbeState
                            },
                        ).clearError(),
                    )
                }
            }

            is ModelLoadingState.Loading -> {
                _uiState.update { state ->
                    state.copy(
                        runtime = state.runtime.copy(
                            activeModelId = nextState.loadedModel?.modelId,
                            modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                            modelStatusDetail = nextState.stage,
                            startupProbeState = StartupProbeState.RUNNING,
                        ).clearError(),
                    )
                }
            }

            is ModelLoadingState.Loaded -> {
                // Routing mode sync is handled in finalizeModelOperation for
                // user-initiated loads only. Syncing here would cement accidental
                // model switches caused by ensureLoaded or background restore.
                _uiState.update { state ->
                    val loadedDetail = nextState.detail?.takeIf { detail -> detail.isNotBlank() }
                    state.copy(
                        runtime = state.runtime.copy(
                            activeModelId = nextState.model.modelId,
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = loadedDetail ?: buildString {
                                append("Runtime model loaded (")
                                append(nextState.model.modelId)
                                nextState.model.modelVersion?.takeIf { it.isNotBlank() }?.let { version ->
                                    append("@")
                                    append(version)
                                }
                                append(")")
                            },
                            startupProbeState = StartupProbeState.READY,
                        ).clearError(),
                    )
                }
            }

            is ModelLoadingState.Offloading -> {
                _uiState.update { state ->
                    state.copy(
                        runtime = state.runtime.copy(
                            modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                            modelStatusDetail = if (nextState.queued) {
                                "Offload queued until the active task finishes."
                            } else {
                                "Offloading model..."
                            },
                        ).clearError(),
                    )
                }
            }

            is ModelLoadingState.Error -> {
                _uiState.update { state ->
                    state.copy(
                        runtime = state.runtime.copy(
                            activeModelId = nextState.loadedModel?.modelId,
                            modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                            modelStatusDetail = nextState.message,
                            startupProbeState = StartupProbeState.BLOCKED,
                            lastErrorCode = nextState.code,
                            lastErrorUserMessage = nextState.message,
                            lastErrorTechnicalDetail = nextState.detail,
                            lastError = nextState.detail ?: nextState.message,
                        ),
                    )
                }
            }
        }
    }

    internal suspend fun handleCompletedModelOperation(result: RuntimeModelLifecycleCommandResult?) {
        val resolvedResult = result ?: return
        if (!resolvedResult.success || resolvedResult.queued) {
            return
        }
        val loadedModel = resolvedResult.loadedModel
        if (loadedModel != null) {
            val pinned = ModelCatalog.routingModesForModel(loadedModel.modelId)
                .firstOrNull { it != RoutingMode.AUTO }
            if (pinned != null) {
                setRoutingModeInternal(pinned)
            }
            withContext(ioDispatcher) {
                runtimeFacade.warmupActiveModel()
            }
        }
        runCatching { runtimeFacade.gpuOffloadStatus() }
            .getOrNull()
            ?.let(::updateRuntimeGpuProbeStateInternal)
        refreshGpuProbeStatusIfPendingInternal()
        refreshRuntimeDiagnostics()
        persistState()
    }

}

internal fun lifecycleErrorMessage(
    result: RuntimeModelLifecycleCommandResult,
    fallbackModelId: String?,
    fallbackVersion: String?,
): String {
    missingRequiredArtifactsFromTechnicalDetail(result.detail)?.let { artifacts ->
        return missingRequiredArtifactsUserMessage(artifacts)
    }
    val modelLabel = buildString {
        append(fallbackModelId.orEmpty())
        fallbackVersion?.takeIf { it.isNotBlank() }?.let { version ->
            if (isNotBlank()) {
                append(" ")
            }
            append(version)
        }
    }.ifBlank { "selected model" }
    return when (result.errorCodeName()) {
        "MODEL_FILE_UNAVAILABLE" -> "Model file unavailable for $modelLabel."
        "RUNTIME_INCOMPATIBLE" -> "Model is incompatible with this runtime."
        "BACKEND_INIT_FAILED" -> "Runtime backend failed to initialize."
        "OUT_OF_MEMORY" -> "Not enough memory to load $modelLabel."
        "BUSY_GENERATION" -> "Wait for the current response to finish before changing models."
        "CANCELLED_BY_NEWER_REQUEST" -> "Model change was superseded by a newer request."
        else -> result.detail ?: "Model operation failed."
    }
}

internal const val MODEL_OPERATION_DEBOUNCE_MS = 500L

@Suppress("CyclomaticComplexMethod")
internal fun modelLoadingStatesEquivalentForUi(a: ModelLoadingState, b: ModelLoadingState): Boolean {
    if (a::class != b::class) {
        return false
    }
    return when (a) {
        is ModelLoadingState.Loading -> {
            b is ModelLoadingState.Loading &&
                a.requestedModel == b.requestedModel &&
                a.loadedModel == b.loadedModel &&
                a.lastUsedModel == b.lastUsedModel &&
                a.stage == b.stage &&
                abs((a.progress ?: 0f) - (b.progress ?: 0f)) < LOADING_PROGRESS_UI_EPSILON
        }
        is ModelLoadingState.Idle -> {
            b is ModelLoadingState.Idle &&
                a.loadedModel == b.loadedModel &&
                a.lastUsedModel == b.lastUsedModel
        }
        is ModelLoadingState.Loaded -> {
            b is ModelLoadingState.Loaded &&
                a.model == b.model &&
                a.lastUsedModel == b.lastUsedModel &&
                a.detail == b.detail
        }
        is ModelLoadingState.Offloading -> {
            b is ModelLoadingState.Offloading &&
                a.loadedModel == b.loadedModel &&
                a.lastUsedModel == b.lastUsedModel &&
                a.reason == b.reason &&
                a.queued == b.queued
        }
        is ModelLoadingState.Error -> {
            b is ModelLoadingState.Error &&
                a.requestedModel == b.requestedModel &&
                a.loadedModel == b.loadedModel &&
                a.lastUsedModel == b.lastUsedModel &&
                a.message == b.message &&
                a.code == b.code &&
                a.detail == b.detail
        }
    }
}

private const val LOADING_PROGRESS_UI_EPSILON = 0.04f
