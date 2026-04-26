package com.pocketagent.android.data.chat

import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.PersistedInteractionMessage

data class StoredChatState(
    val sessions: List<StoredChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val routingMode: String = "AUTO",
    val performanceProfile: String = "BALANCED",
    val keepAlivePreference: String = RuntimeKeepAlivePreference.AUTO.name,
    val gpuAccelerationEnabled: Boolean = false,
    val defaultThinkingEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val firstSessionStage: String = FirstSessionStage.ONBOARDING.name,
    val advancedUnlocked: Boolean = false,
    val firstAnswerCompleted: Boolean = false,
    val followUpCompleted: Boolean = false,
    val firstSessionTelemetryEvents: List<FirstSessionTelemetryEvent> = emptyList(),
)

data class StoredChatSession(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<StoredChatMessage>,
    val completionSettings: CompletionSettings = CompletionSettings(),
    val messagesLoaded: Boolean = true,
    val messageCount: Int = messages.size,
)

data class StoredChatMessage(
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

interface ChatSessionRepository {
    fun hasAnyPersistedState(): Boolean
    fun loadBootstrapState(): StoredChatState
    fun loadFullState(): StoredChatState
    fun loadSessionMessages(sessionId: String): List<StoredChatMessage>?
    fun saveState(state: StoredChatState)
    fun clearAll()
}
