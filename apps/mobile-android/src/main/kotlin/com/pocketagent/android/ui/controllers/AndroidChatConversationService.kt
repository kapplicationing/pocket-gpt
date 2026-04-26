package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.launchSafeSingleImagePaths
import com.pocketagent.android.ui.clearError
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.withUiError

internal data class ChatStateUpdate(
    val state: ChatUiState,
    val shouldPersist: Boolean,
    val hydrateSessionId: String? = null,
)

internal data class PreparedImageAnalysis(
    val state: ChatUiState,
    val sessionId: String,
)

internal data class PreparedToolExecution(
    val state: ChatUiState,
    val sessionId: String,
    val toolCallId: String,
)

class AndroidChatConversationService(
    private val sessionService: AndroidChatSessionService = AndroidChatSessionService(),
) {
    internal fun updateCompletionSettings(
        state: ChatUiState,
        settings: CompletionSettings,
    ): ChatStateUpdate? {
        val activeSession = state.activeSession ?: return null
        return ChatStateUpdate(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(completionSettings = settings)
            },
            shouldPersist = true,
        )
    }

    internal fun addAttachedImage(state: ChatUiState, imagePath: String): ChatUiState {
        val trimmed = imagePath.trim()
        if (trimmed.isBlank()) return state
        val current = state.composer.attachedImages.firstOrNull()
        if (current == trimmed) return state
        return state.copy(
            composer = state.composer.copy(
                attachedImages = listOf(trimmed),
            ),
        )
    }

    internal fun removeAttachedImage(state: ChatUiState, index: Int): ChatUiState {
        val current = state.composer.attachedImages.toMutableList()
        if (index !in current.indices) {
            return state
        }
        current.removeAt(index)
        return state.copy(
            composer = state.composer.copy(attachedImages = current),
        )
    }

    internal fun startEditing(state: ChatUiState, messageId: String): ChatUiState? {
        val activeSession = state.activeSession ?: return null
        val message = activeSession.messages.firstOrNull { it.id == messageId } ?: return null
        if (message.role != com.pocketagent.android.ui.state.MessageRole.USER) {
            return null
        }
        return state.copy(
            composer = state.composer.copy(
                text = message.content,
                editingMessageId = messageId,
                attachedImages = launchSafeSingleImagePaths(
                    imagePaths = message.imagePaths,
                    legacyImagePath = message.imagePath,
                ),
            ),
        )
    }

    internal fun cancelEditing(state: ChatUiState): ChatUiState {
        return state.copy(
            composer = state.composer.copy(
                text = "",
                editingMessageId = null,
                attachedImages = emptyList(),
            ),
        )
    }

    internal fun submitEdit(state: ChatUiState): ChatStateUpdate? {
        val activeSession = state.activeSession ?: return null
        val editingId = state.composer.editingMessageId ?: return null
        val editIndex = activeSession.messages.indexOfFirst { it.id == editingId }
        if (editIndex < 0) {
            return null
        }
        return ChatStateUpdate(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages.take(editIndex),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                composer = state.composer.copy(editingMessageId = null),
            ),
            shouldPersist = true,
        )
    }

    internal fun regenerateResponse(
        state: ChatUiState,
        messageId: String,
    ): ChatStateUpdate? {
        val activeSession = state.activeSession ?: return null
        val messageIndex = activeSession.messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) {
            return null
        }
        val message = activeSession.messages[messageIndex]
        if (message.role != com.pocketagent.android.ui.state.MessageRole.ASSISTANT) {
            return null
        }
        val userMessage = activeSession.messages.take(messageIndex)
            .lastOrNull { it.role == com.pocketagent.android.ui.state.MessageRole.USER }
            ?: return null
        return ChatStateUpdate(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages.take(messageIndex),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                composer = state.composer.copy(
                    text = userMessage.content,
                    attachedImages = launchSafeSingleImagePaths(
                        imagePaths = userMessage.imagePaths,
                        legacyImagePath = userMessage.imagePath,
                    ),
                ),
            ),
            shouldPersist = true,
        )
    }

    internal fun createSession(
        state: ChatUiState,
        sessionId: String,
        nowEpochMs: Long,
        title: String = "New chat",
    ): ChatStateUpdate {
        val mutation = sessionService.createSession(
            sessions = state.sessions,
            sessionId = sessionId,
            title = title,
            nowEpochMs = nowEpochMs,
        )
        return ChatStateUpdate(
            state = state.copy(
                sessions = mutation.sessions.map { session ->
                    if (session.id == mutation.activeSessionId) {
                        session.copy(
                            completionSettings = CompletionSettings(
                                showThinking = state.defaultThinkingEnabled,
                            ),
                        )
                    } else {
                        session
                    }
                },
                activeSessionId = mutation.activeSessionId,
                activeSurface = if (state.activeSurface is ModalSurface.SessionDrawer) {
                    ModalSurface.None
                } else {
                    state.activeSurface
                },
            ),
            shouldPersist = true,
            hydrateSessionId = mutation.hydrateSessionId,
        )
    }

    internal fun switchSession(
        state: ChatUiState,
        sessionId: String,
    ): ChatStateUpdate {
        val mutation = sessionService.switchSession(
            sessions = state.sessions,
            activeSessionId = state.activeSessionId,
            sessionId = sessionId,
        )
        return ChatStateUpdate(
            state = state.copy(
                sessions = mutation.sessions,
                activeSessionId = mutation.activeSessionId,
                activeSurface = if (state.activeSurface is ModalSurface.SessionDrawer) {
                    ModalSurface.None
                } else {
                    state.activeSurface
                },
            ),
            shouldPersist = mutation.shouldPersist,
            hydrateSessionId = mutation.hydrateSessionId,
        )
    }

    internal fun deleteSession(
        state: ChatUiState,
        sessionId: String,
        replacementSessionId: String? = null,
        nowEpochMs: Long,
        replacementTitle: String = "New chat",
    ): ChatStateUpdate {
        var mutation = sessionService.deleteSession(
            sessions = state.sessions,
            activeSessionId = state.activeSessionId,
            sessionId = sessionId,
        )
        if (mutation.shouldCreateReplacementSession && replacementSessionId != null) {
            mutation = sessionService.createSession(
                sessions = mutation.sessions,
                sessionId = replacementSessionId,
                title = replacementTitle,
                nowEpochMs = nowEpochMs,
            )
        }
        return ChatStateUpdate(
            state = state.copy(
                sessions = mutation.sessions,
                activeSessionId = mutation.activeSessionId,
            ),
            shouldPersist = mutation.shouldPersist,
            hydrateSessionId = mutation.hydrateSessionId,
        )
    }

    internal fun hydrateSession(
        state: ChatUiState,
        sessionId: String,
        messages: List<MessageUiModel>,
    ): ChatStateUpdate {
        val mutation = sessionService.hydrateSession(
            sessions = state.sessions,
            sessionId = sessionId,
            messages = messages,
        )
        return ChatStateUpdate(
            state = state.copy(sessions = mutation.sessions),
            shouldPersist = mutation.shouldPersist,
        )
    }

    internal fun prepareImageAnalysis(
        state: ChatUiState,
        imageMessage: MessageUiModel,
        loadingDetail: String = "Loading runtime model...",
    ): PreparedImageAnalysis? {
        val activeSession = state.activeSession ?: return null
        return PreparedImageAnalysis(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages + imageMessage,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                composer = state.composer.copy(isSending = true, isCancelling = false),
                runtime = state.runtime.copy(
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = loadingDetail,
                ).clearError(),
            ),
            sessionId = activeSession.id,
        )
    }

    internal fun applyImageAnalysisSuccess(
        state: ChatUiState,
        sessionId: String,
        assistantMessage: MessageUiModel,
        runtimeBackend: String?,
        statusDetail: String = "Image analysis completed",
    ): ChatUiState {
        return state.updateSession(sessionId) { session ->
            session.copy(
                messages = session.messages + assistantMessage,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }.copy(
            composer = state.composer.copy(isSending = false, isCancelling = false),
            runtime = state.runtime.copy(
                runtimeBackend = runtimeBackend,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = statusDetail,
            ).clearError(),
        )
    }

    internal fun applyImageAnalysisFailure(
        state: ChatUiState,
        sessionId: String,
        errorMessage: MessageUiModel,
        uiError: UiError,
    ): ChatUiState {
        return state.updateSession(sessionId) { session ->
            session.copy(
                messages = session.messages + errorMessage,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }.copy(
            composer = state.composer.copy(isSending = false, isCancelling = false),
            runtime = state.runtime.copy(
                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                modelStatusDetail = uiError.userMessage,
            ).withUiError(uiError),
        )
    }

    internal fun prepareToolExecution(
        state: ChatUiState,
        requestMessage: MessageUiModel,
        assistantToolCallMessage: MessageUiModel,
        toolCallId: String,
        loadingDetail: String = "Preparing local tool...",
    ): PreparedToolExecution? {
        val activeSession = state.activeSession ?: return null
        return PreparedToolExecution(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages + requestMessage + assistantToolCallMessage,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                composer = state.composer.copy(isSending = true, isCancelling = false),
                runtime = state.runtime.copy(
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = loadingDetail,
                ).clearError(),
            ),
            sessionId = activeSession.id,
            toolCallId = toolCallId,
        )
    }

    internal fun appendDiagnostics(
        state: ChatUiState,
        diagnosticsMessage: MessageUiModel,
    ): ChatStateUpdate? {
        val activeSession = state.activeSession ?: return null
        return ChatStateUpdate(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages + diagnosticsMessage,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                runtime = state.runtime.clearError(),
            ),
            shouldPersist = true,
        )
    }

    internal fun appendDiagnosticsFailure(
        state: ChatUiState,
        errorMessage: MessageUiModel,
        uiError: UiError,
    ): ChatStateUpdate? {
        val activeSession = state.activeSession ?: return null
        return ChatStateUpdate(
            state = state.updateSession(activeSession.id) { session ->
                session.copy(
                    messages = session.messages + errorMessage,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }.copy(
                runtime = state.runtime.withUiError(uiError),
            ),
            shouldPersist = true,
        )
    }

    internal fun activeSession(state: ChatUiState): ChatSessionUiModel? = state.activeSession

    private fun ChatUiState.updateSession(
        sessionId: String,
        transform: (ChatSessionUiModel) -> ChatSessionUiModel,
    ): ChatUiState {
        return copy(
            sessions = sessions.map { session ->
                if (session.id == sessionId) {
                    sessionService.normalize(transform(session))
                } else {
                    session
                }
            },
        )
    }

}
