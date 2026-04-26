package com.pocketagent.android.ui.controllers

import com.pocketagent.android.BuildConfig
import com.pocketagent.android.data.chat.StoredChatMessage
import com.pocketagent.android.data.chat.StoredChatSession
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.RuntimePerformanceProfile
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher

data class StartupBootstrapResult(
    val state: ChatUiState,
    val hydrateSessionId: String?,
    val shouldRunStartupProbe: Boolean,
    val shouldPersist: Boolean,
)

data class StartupProbeOutcome(
    val startupChecks: List<String>,
    val runtimeBackend: String?,
    val gpuProbeResult: com.pocketagent.android.runtime.GpuProbeResult,
    val readinessDecision: StartupReadinessDecision,
)

class ChatStartupFlow(
    private val runtimeGateway: ChatRuntimeService,
    private val startupProbeController: StartupProbeController,
    private val startupReadinessCoordinator: StartupReadinessCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeStartupProbeTimeoutMs: Long,
    private val nativeRuntimeLibraryPackaged: Boolean,
    private val sessionService: AndroidChatSessionService = AndroidChatSessionService(),
    private val timelineProjector: TimelineProjector = TimelineProjector(),
) {
    fun bootstrap(loadedState: PersistenceBootstrapState): StartupBootstrapResult {
        val persisted = loadedState.persisted
        val loadError = loadedState.loadError
        val runtimeBackend = runtimeGateway.runtimeBackend()

        val restoredRoutingMode = RoutingMode.valueOf(persisted.routingMode)
        val effectiveRoutingMode = coerceSupportedRoutingModeForStartup(restoredRoutingMode)
        val routingModeAdjusted = restoredRoutingMode != effectiveRoutingMode
        val restoredPerformanceProfile = RuntimePerformanceProfile.valueOf(persisted.performanceProfile)
        val restoredKeepAlivePreference = RuntimeKeepAlivePreference.valueOf(persisted.keepAlivePreference)
        val restoredFirstSessionStage = FirstSessionStage.valueOf(persisted.firstSessionStage)
        val restoredAdvancedUnlocked = persisted.advancedUnlocked
        val gpuManualOverrideAllowed = BuildConfig.DEBUG
        val initialFirstSessionStage = when {
            !persisted.onboardingCompleted -> FirstSessionStage.ONBOARDING
            restoredFirstSessionStage == FirstSessionStage.ONBOARDING -> FirstSessionStage.GET_READY
            else -> restoredFirstSessionStage
        }
        val gpuProbe = runtimeGateway.gpuOffloadStatus()
        val gpuSupported = gpuProbe.status == GpuProbeStatus.QUALIFIED && gpuProbe.maxStableGpuLayers > 0
        val restoredGpuEnabled = persisted.gpuAccelerationEnabled && (gpuSupported || gpuManualOverrideAllowed)
        runtimeGateway.setRoutingMode(effectiveRoutingMode)

        val bootstrapRuntimeState = if (loadError == null) {
            RuntimeUiState(
                routingMode = effectiveRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                keepAlivePreference = restoredKeepAlivePreference,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                gpuManualOverrideAllowed = gpuManualOverrideAllowed,
                gpuProbeStatus = gpuProbe.status,
                gpuProbeFailureReason = gpuProbe.failureReason?.name,
                gpuProbeDetail = gpuProbe.detail,
                gpuMaxQualifiedLayers = gpuProbe.maxStableGpuLayers,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = STARTUP_PROBE_RUNNING_DETAIL,
            ).clearRuntimeError()
        } else {
            RuntimeUiState(
                routingMode = effectiveRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                keepAlivePreference = restoredKeepAlivePreference,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                gpuManualOverrideAllowed = gpuManualOverrideAllowed,
                gpuProbeStatus = gpuProbe.status,
                gpuProbeFailureReason = gpuProbe.failureReason?.name,
                gpuProbeDetail = gpuProbe.detail,
                gpuMaxQualifiedLayers = gpuProbe.maxStableGpuLayers,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.BLOCKED,
                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                modelStatusDetail = loadError.userMessage,
                startupChecks = listOf(loadError.technicalDetail ?: loadError.userMessage),
            ).withRuntimeUiError(loadError)
        }

        val restoredSessions = persisted.sessions.map { storedSession ->
            val session = storedSession.toStartupUiSession()
            if (storedSession.messagesLoaded) {
                val turns = timelineProjector.toTurns(session)
                runtimeGateway.restoreSession(sessionId = SessionId(session.id), turns = turns)
            }
            session
        }
        val sessionBootstrap = sessionService.bootstrap(
            sessions = restoredSessions,
            persistedActiveSessionId = persisted.activeSessionId,
        )
        val resolvedSessions = if (sessionBootstrap.shouldCreateInitialSession) {
            val now = System.currentTimeMillis()
            sessionService.createSession(
                sessions = emptyList(),
                sessionId = runtimeGateway.createSession().value,
                title = "New chat",
                nowEpochMs = now,
                ).sessions.map { session ->
                session.copy(
                    completionSettings = CompletionSettings(showThinking = persisted.defaultThinkingEnabled),
                )
            }
        } else {
            sessionBootstrap.sessions
        }
        val activeSessionId = when {
            sessionBootstrap.shouldCreateInitialSession -> resolvedSessions.lastOrNull()?.id
            else -> sessionBootstrap.activeSessionId
        }
        return StartupBootstrapResult(
            state = ChatUiState(
                sessions = resolvedSessions,
                activeSessionId = activeSessionId,
                runtime = bootstrapRuntimeState,
                defaultThinkingEnabled = persisted.defaultThinkingEnabled,
                activeSurface = if (persisted.onboardingCompleted) {
                    ModalSurface.None
                } else {
                    ModalSurface.Onboarding
                },
                firstSessionStage = initialFirstSessionStage,
                advancedUnlocked = restoredAdvancedUnlocked,
                firstAnswerCompleted = persisted.firstAnswerCompleted,
                followUpCompleted = persisted.followUpCompleted,
                firstSessionTelemetryEvents = persisted.firstSessionTelemetryEvents,
            ),
            hydrateSessionId = if (sessionBootstrap.shouldCreateInitialSession) null else sessionBootstrap.hydrateSessionId,
            shouldRunStartupProbe = loadedState.shouldRunStartupProbe,
            shouldPersist = routingModeAdjusted || sessionBootstrap.shouldPersist || sessionBootstrap.shouldCreateInitialSession,
        )
    }

    fun markProbeRunning(state: ChatUiState): ChatUiState {
        val runtime = state.runtime
        val alreadyReady = runtime.activeModelId?.isNotBlank() == true &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY &&
            runtime.startupProbeState == StartupProbeState.READY
        if (alreadyReady) {
            return state.copy(runtime = runtime.clearRuntimeError())
        }
        return state.copy(
            runtime = runtime.copy(
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = STARTUP_PROBE_RUNNING_DETAIL,
            ).clearRuntimeError(),
        )
    }

    suspend fun evaluateStartup(statusDetailOverride: String?): StartupProbeOutcome {
        val startupChecks = if (!nativeRuntimeLibraryPackaged) {
            listOf(MISSING_NATIVE_RUNTIME_BUILD_CHECK)
        } else {
            startupProbeController.runStartupChecks(
                runtimeGateway = runtimeGateway,
                ioDispatcher = ioDispatcher,
                timeoutMs = runtimeStartupProbeTimeoutMs,
            )
        }
        val runtimeBackend = runtimeGateway.runtimeBackend()
        val gpuProbe = runtimeGateway.gpuOffloadStatus()
        return StartupProbeOutcome(
            startupChecks = startupChecks,
            runtimeBackend = runtimeBackend,
            gpuProbeResult = gpuProbe,
            readinessDecision = startupReadinessCoordinator.decide(
                startupChecks = startupChecks,
                runtimeBackend = runtimeBackend,
                statusDetailOverride = statusDetailOverride,
            ),
        )
    }

    fun applyProbeOutcome(
        state: ChatUiState,
        outcome: StartupProbeOutcome,
    ): ChatUiState {
        val probe = outcome.gpuProbeResult
        val gpuSupported = probe.status == GpuProbeStatus.QUALIFIED && probe.maxStableGpuLayers > 0
        val nextRuntime = state.runtime.copy(
            runtimeBackend = outcome.runtimeBackend,
            gpuAccelerationSupported = gpuSupported,
            gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled && gpuSupported,
            gpuProbeStatus = probe.status,
            gpuProbeFailureReason = probe.failureReason?.name,
            gpuMaxQualifiedLayers = probe.maxStableGpuLayers,
            startupProbeState = outcome.readinessDecision.startupProbeState,
            modelRuntimeStatus = outcome.readinessDecision.modelRuntimeStatus,
            modelStatusDetail = outcome.readinessDecision.modelStatusDetail,
            startupChecks = outcome.startupChecks,
            startupWarnings = outcome.readinessDecision.startupWarnings,
        ).withRuntimeUiError(outcome.readinessDecision.startupError)
        val sendAllowed = outcome.readinessDecision.startupProbeState == StartupProbeState.READY
        val blocked = outcome.readinessDecision.startupProbeState == StartupProbeState.BLOCKED ||
            outcome.readinessDecision.startupProbeState == StartupProbeState.BLOCKED_TIMEOUT
        val nextStage = when {
            state.activeSurface is ModalSurface.Onboarding -> FirstSessionStage.ONBOARDING
            state.firstAnswerCompleted -> state.firstSessionStage
            sendAllowed -> FirstSessionStage.READY_TO_CHAT
            blocked -> FirstSessionStage.GET_READY
            else -> state.firstSessionStage
        }
        val completedGetReadyNow = state.firstSessionStage == FirstSessionStage.GET_READY &&
            nextStage == FirstSessionStage.READY_TO_CHAT
        val telemetry = if (completedGetReadyNow) {
            addTelemetryEventIfMissingForStartup(
                events = state.firstSessionTelemetryEvents,
                eventName = TELEMETRY_EVENT_GET_READY_COMPLETED,
            )
        } else {
            state.firstSessionTelemetryEvents
        }
        return state.copy(
            runtime = nextRuntime,
            firstSessionStage = nextStage,
            firstSessionTelemetryEvents = telemetry,
        )
    }

    fun startupBlockError(runtime: RuntimeUiState): com.pocketagent.android.ui.state.UiError {
        val checks = runtime.startupChecks.ifEmpty {
            listOf(runtime.modelStatusDetail ?: "Runtime startup checks are still running.")
        }
        return com.pocketagent.android.ui.state.UiErrorMapper.startupFailure(checks)
            ?: com.pocketagent.android.ui.state.UiErrorMapper.runtimeFailure(
                runtime.modelStatusDetail ?: "Runtime is not ready yet.",
            )
    }

    fun readyStatusDetail(runtimeBackend: String?): String {
        return if (runtimeBackend.isNullOrBlank()) {
            "Runtime model ready"
        } else {
            "Runtime model ready ($runtimeBackend)"
        }
    }

    private companion object {
        private const val TELEMETRY_EVENT_GET_READY_COMPLETED = "get_ready_completed"
        private const val STARTUP_PROBE_RUNNING_DETAIL = "Running startup checks..."
        private const val MISSING_NATIVE_RUNTIME_BUILD_CHECK =
            "Build is missing native runtime library (libpocket_llama.so). " +
                "Install an app build that packages native runtime."
    }
}

private fun coerceSupportedRoutingModeForStartup(mode: RoutingMode): RoutingMode {
    if (mode == RoutingMode.AUTO) {
        return mode
    }
    val modelId = ModelCatalog.modelIdForRoutingMode(mode) ?: return RoutingMode.AUTO
    return if (ModelCatalog.descriptorFor(modelId)?.bridgeSupported == true) {
        mode
    } else {
        RoutingMode.AUTO
    }
}

private fun RuntimeUiState.clearRuntimeError(): RuntimeUiState {
    return copy(
        lastErrorCode = null,
        lastErrorUserMessage = null,
        lastErrorTechnicalDetail = null,
        lastError = null,
    )
}

private fun RuntimeUiState.withRuntimeUiError(error: UiError?): RuntimeUiState {
    if (error == null) {
        return clearRuntimeError()
    }
    return copy(
        lastErrorCode = error.code,
        lastErrorUserMessage = error.userMessage,
        lastErrorTechnicalDetail = error.technicalDetail,
        lastError = error.technicalDetail ?: error.userMessage,
    )
}

private fun addTelemetryEventIfMissingForStartup(
    events: List<FirstSessionTelemetryEvent>,
    eventName: String,
): List<FirstSessionTelemetryEvent> {
    if (events.any { it.eventName == eventName }) {
        return events
    }
    return (events + FirstSessionTelemetryEvent(eventName = eventName, eventTimeUtc = Instant.now().toString()))
        .takeLast(64)
}

private fun StoredChatSession.toStartupUiSession(): ChatSessionUiModel {
    return ChatSessionUiModel(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages.map(StoredChatMessage::toStartupUiMessage),
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}

private fun StoredChatMessage.toStartupUiMessage(): MessageUiModel {
    return MessageUiModel(
        id = id,
        role = role,
        content = content,
        timestampEpochMs = timestampEpochMs,
        kind = kind,
        imagePath = imagePath,
        imagePaths = imagePaths,
        toolName = toolName,
        isStreaming = isStreaming,
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
