package com.pocketagent.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.runtime.AppDispatchers
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.UiError
import java.time.Instant
import java.util.UUID
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlinx.coroutines.flow.update

internal const val LOG_TAG = "ChatViewModel"
internal const val TITLE_MAX_CHARS = 42
internal const val ONBOARDING_LAST_PAGE = 2
internal const val DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS = 30_000L
internal const val COMPOSER_KEEP_ALIVE_TOUCH_DEBOUNCE_MS = 30_000L
internal const val CHAT_PERSIST_DEBOUNCE_MS = 120L
internal const val GPU_PROBE_REFRESH_INTERVAL_MS = 700L
internal const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 80L
internal const val TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED = "simple_first_entered"
internal const val TELEMETRY_EVENT_GET_READY_STARTED = "get_ready_started"
internal const val TELEMETRY_EVENT_FIRST_ANSWER_COMPLETED = "first_answer_completed"
internal const val TELEMETRY_EVENT_FOLLOW_UP_COMPLETED = "follow_up_completed"
internal const val TELEMETRY_EVENT_ADVANCED_UNLOCKED = "advanced_unlocked"
internal val WHITESPACE_REGEX = Regex("\\s+")

internal fun addTelemetryEventIfMissing(
    events: List<FirstSessionTelemetryEvent>,
    eventName: String,
): List<FirstSessionTelemetryEvent> {
    if (events.any { it.eventName == eventName }) {
        return events
    }
    return (events + FirstSessionTelemetryEvent(eventName = eventName, eventTimeUtc = Instant.now().toString()))
        .takeLast(64)
}

internal fun RuntimeUiState.clearError(): RuntimeUiState {
    return copy(
        lastErrorCode = null,
        lastErrorUserMessage = null,
        lastErrorTechnicalDetail = null,
        lastError = null,
    )
}

internal fun RuntimeUiState.withUiError(error: UiError?): RuntimeUiState {
    if (error == null) {
        return clearError()
    }
    return copy(
        lastErrorCode = error.code,
        lastErrorUserMessage = error.userMessage,
        lastErrorTechnicalDetail = error.technicalDetail,
        lastError = error.technicalDetail ?: error.userMessage,
    )
}

internal fun formatUserFacingError(error: UiError): String {
    return "${error.userMessage} (${error.code})"
}

internal fun ChatViewModel.applyBlockedRuntimeGuardrail(
    sessionId: String,
    uiError: UiError,
) {
    appendSystemMessage(
        sessionId = sessionId,
        content = formatUserFacingError(uiError),
    )
    _uiState.update { state ->
        state.copy(
            runtime = state.runtime.copy(
                modelRuntimeStatus = if (state.runtime.startupProbeState == StartupProbeState.RUNNING) {
                    ModelRuntimeStatus.LOADING
                } else {
                    ModelRuntimeStatus.NOT_READY
                },
            ).withUiError(uiError),
        )
    }
    persistState()
}

internal fun performanceProfileStatusDetail(
    profile: RuntimePerformanceProfile,
    gpuEnabled: Boolean,
    gpuSupported: Boolean,
): String {
    val profileLabel = profile.name.lowercase().replaceFirstChar { it.uppercase() }
    val gpuLabel = when {
        gpuEnabled && gpuSupported -> "GPU enabled"
        gpuEnabled && !gpuSupported -> "GPU unavailable (fails fast)"
        else -> "GPU off"
    }
    return "Speed & Battery: $profileLabel ($gpuLabel)"
}

internal fun deriveSessionTitle(messages: List<MessageUiModel>): String {
    val firstUserMessage = messages.firstOrNull { it.role == MessageRole.USER } ?: return "New chat"
    val normalized = firstUserMessage.content
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .take(TITLE_MAX_CHARS)
    return normalized.ifBlank { "New chat" }
}

internal fun newRequestId(): String = "req-${UUID.randomUUID()}"

internal fun newToolCallId(): String = "toolcall-${UUID.randomUUID()}"

internal fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

class ChatViewModelFactory internal constructor(
    private val runtimeFacade: ChatRuntimeService,
    private val sessionPersistence: SessionPersistence,
    private val presetBackingStore: PresetBackingStore,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
    private val dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(
                runtimeFacade = runtimeFacade,
                sessionPersistence = sessionPersistence,
                presetBackingStore = presetBackingStore,
                dispatchers = dispatchers,
                deviceStateProvider = deviceStateProvider,
                runtimeTuning = runtimeTuning,
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}
