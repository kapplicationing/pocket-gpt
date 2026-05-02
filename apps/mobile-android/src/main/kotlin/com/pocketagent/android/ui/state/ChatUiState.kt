package com.pocketagent.android.ui.state

import androidx.compose.runtime.Immutable
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
}

enum class MessageKind {
    TEXT,
    IMAGE,
    TOOL,
    DIAGNOSTIC,
}

@Immutable
data class MessageUiModel(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestampEpochMs: Long,
    val kind: MessageKind = MessageKind.TEXT,
    val imagePath: String? = null,
    val imagePaths: List<String> = emptyList(),
    val toolName: String? = null,
    val isStreaming: Boolean = false,
    val isThinking: Boolean = false,
    val requestId: String? = null,
    val finishReason: String? = null,
    val terminalEventSeen: Boolean = false,
    val interaction: PersistedInteractionMessage? = null,
    val reasoningContent: String? = null,
    val firstTokenMs: Long? = null,
    val tokensPerSec: Double? = null,
    val totalLatencyMs: Long? = null,
)

@Immutable
data class PersistedInteractionMessage(
    val role: String,
    val parts: List<PersistedInteractionPart> = emptyList(),
    val toolCalls: List<PersistedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Immutable
data class PersistedInteractionPart(
    val type: String,
    val text: String? = null,
)

@Immutable
data class PersistedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val status: PersistedToolCallStatus = PersistedToolCallStatus.PENDING,
)

enum class PersistedToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Immutable
data class CompletionSettings(
    val temperature: Float = 0.6f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val repeatPenalty: Float = 1.1f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val systemPrompt: String = "",
    val showThinking: Boolean = false,
)

@Immutable
data class ChatSessionUiModel(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<MessageUiModel>,
    val completionSettings: CompletionSettings = CompletionSettings(),
    val messagesLoaded: Boolean = true,
    val messageCount: Int = messages.size,
)

@Immutable
data class ComposerUiState(
    val text: String = "",
    val isSending: Boolean = false,
    val editingMessageId: String? = null,
    val attachedImages: List<String> = emptyList(),
    val isCancelling: Boolean = false,
)

@Immutable
data class RuntimeUiState(
    val offlineOnly: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.AUTO,
    val performanceProfile: RuntimePerformanceProfile = RuntimePerformanceProfile.BALANCED,
    val keepAlivePreference: RuntimeKeepAlivePreference = RuntimeKeepAlivePreference.AUTO,
    val gpuAccelerationEnabled: Boolean = false,
    val gpuAccelerationSupported: Boolean = false,
    val gpuManualOverrideAllowed: Boolean = false,
    val gpuProbeStatus: GpuProbeStatus = GpuProbeStatus.PENDING,
    val gpuProbeFailureReason: String? = null,
    val gpuProbeDetail: String? = null,
    val gpuMaxQualifiedLayers: Int = 0,
    val runtimeBackend: String? = null,
    val activeBackend: String? = null,
    val backendProfile: String? = null,
    val compiledBackend: String? = null,
    val activeModelQuantization: String? = null,
    val modelMemoryMode: String? = null,
    val prefixCacheMode: String? = null,
    val nativeRuntimeSupported: Boolean? = null,
    val strictAcceleratorFailFast: Boolean? = null,
    val autoBackendCpuFallback: Boolean? = null,
    val startupProbeState: StartupProbeState = StartupProbeState.IDLE,
    val modelRuntimeStatus: ModelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
    val modelStatusDetail: String? = null,
    val activeModelId: String? = null,
    val lastFirstTokenLatencyMs: Long? = null,
    val lastTotalLatencyMs: Long? = null,
    val lastModelLoadMs: Long? = null,
    val lastPrefillMs: Long? = null,
    val lastDecodeMs: Long? = null,
    val lastTokensPerSec: Double? = null,
    val lastPeakRssMb: Double? = null,
    val lastResidentHit: Boolean? = null,
    val lastResidentHitCount: Long? = null,
    val lastReloadReason: String? = null,
    val lastPrefixCacheHit: Boolean? = null,
    val lastPrefixCacheReusedTokens: Int? = null,
    val lastPrefixCacheHitRate: Double? = null,
    val startupChecks: List<String> = emptyList(),
    val startupWarnings: List<String> = emptyList(),
    val lastErrorCode: String? = null,
    val lastErrorUserMessage: String? = null,
    val lastErrorTechnicalDetail: String? = null,
    val lastError: String? = null,
    val sendElapsedMs: Long? = null,
    val sendSlowState: String? = null,
)

@Immutable
data class StreamingState(
    val sessionId: String? = null,
    val messageId: String? = null,
    val text: String = "",
    val isThinking: Boolean = false,
)

enum class RuntimeKeepAlivePreference {
    AUTO,
    ALWAYS,
    ONE_MINUTE,
    FIVE_MINUTES,
    FIFTEEN_MINUTES,
    UNLOAD_IMMEDIATELY,
}

enum class ModelRuntimeStatus {
    NOT_READY,
    LOADING,
    READY,
    ERROR,
}

enum class ModelLoadingSubState(val displayText: String) {
    CHECKING_AVAILABILITY("Checking model availability..."),
    RELEASING_PREVIOUS("Releasing previous model..."),
    INITIALIZING_RUNTIME("Initializing runtime..."),
    LOADING("Loading model..."),
    WARMING_UP("Warming up...");

    companion object {
        fun fromDetail(detail: String?): ModelLoadingSubState? {
            if (detail == null) return null
            return entries.firstOrNull { it.displayText == detail }
        }
    }
}

enum class StartupProbeState {
    IDLE,
    RUNNING,
    READY,
    DEGRADED,
    BLOCKED_TIMEOUT,
    BLOCKED,
}

enum class FirstSessionStage {
    ONBOARDING,
    GET_READY,
    READY_TO_CHAT,
    FIRST_ANSWER_DONE,
    FOLLOW_UP_DONE,
    ADVANCED_UNLOCKED,
}

@Immutable
data class FirstSessionTelemetryEvent(
    val eventName: String,
    val eventTimeUtc: String,
)

@Immutable
data class ChatUiState(
    val bootstrapCompleted: Boolean = false,
    val sessions: List<ChatSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val composer: ComposerUiState = ComposerUiState(),
    val runtime: RuntimeUiState = RuntimeUiState(),
    val defaultThinkingEnabled: Boolean = false,
    val streaming: StreamingState = StreamingState(),
    val activeSurface: ModalSurface = ModalSurface.None,
    val onboardingPage: Int = 0,
    val firstSessionStage: FirstSessionStage = FirstSessionStage.ONBOARDING,
    val advancedUnlocked: Boolean = false,
    val firstAnswerCompleted: Boolean = false,
    val followUpCompleted: Boolean = false,
    val firstSessionTelemetryEvents: List<FirstSessionTelemetryEvent> = emptyList(),
)

// Intentionally an extension function, NOT a property getter on @Immutable ChatUiState.
// A custom getter on a data class breaks the Compose @Immutable contract (same
// equality must imply same outputs) and runs an O(N) scan on every access — including
// hot paths like _uiState.map { it.activeSession }. Callers should hold activeSessionId
// + sessions narrowly via dedicated flows (see ChatViewModel.activeSessionFlow) and
// only resolve when they truly need the resolved session.
fun ChatUiState.activeSession(): ChatSessionUiModel? =
    sessions.firstOrNull { it.id == activeSessionId }
