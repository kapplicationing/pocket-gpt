package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.activeSession
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.controllers.ChatStateUpdate
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.SessionId
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ChatViewModel.editMessageInternal(messageId: String) {
    conversationService.startEditing(_uiState.value, messageId)?.let { nextState ->
        _uiState.value = nextState
        applyComposerDraft(nextState.composer.text)
    }
}

internal fun ChatViewModel.cancelEditInternal() {
    _uiState.value = conversationService.cancelEditing(_uiState.value)
    applyComposerDraft("")
}

internal fun ChatViewModel.submitEditInternal() {
    val update = conversationService.submitEdit(_uiState.value) ?: return
    _uiState.value = update.state
    if (update.shouldPersist) {
        persistState()
    }
    sendMessage()
}

internal fun ChatViewModel.regenerateResponseInternal(messageId: String) {
    val update = conversationService.regenerateResponse(_uiState.value, messageId) ?: return
    _uiState.value = update.state
    applyComposerDraft(update.state.composer.text)
    if (update.shouldPersist) {
        persistState()
    }
    sendMessage()
}

internal fun ChatViewModel.updateSessionCompletionSettingsInternal(settings: CompletionSettings) {
    val update = conversationService.updateCompletionSettings(_uiState.value, settings) ?: return
    _uiState.value = update.state
    if (update.shouldPersist) {
        persistState()
    }
}

internal fun ChatViewModel.addAttachedImageInternal(imagePath: String) {
    _uiState.value = conversationService.addAttachedImage(_uiState.value, imagePath)
}

internal fun ChatViewModel.removeAttachedImageInternal(index: Int) {
    _uiState.value = conversationService.removeAttachedImage(_uiState.value, index)
}

internal fun ChatViewModel.createSessionInternal() {
    applyConversationUpdate(
        conversationService.createSession(
            state = _uiState.value,
            sessionId = runtimeFacade.createSession().value,
            nowEpochMs = System.currentTimeMillis(),
        ),
    )
}

internal fun ChatViewModel.switchSessionInternal(sessionId: String) {
    applyConversationUpdate(conversationService.switchSession(_uiState.value, sessionId))
}

internal fun ChatViewModel.deleteSessionInternal(sessionId: String) {
    runtimeFacade.deleteSession(SessionId(sessionId))
    applyConversationUpdate(
        conversationService.deleteSession(
            state = _uiState.value,
            sessionId = sessionId,
            replacementSessionId = runtimeFacade.createSession().value,
            nowEpochMs = System.currentTimeMillis(),
        ),
    )
}

internal fun ChatViewModel.attachImageInternal(imagePath: String) {
    val snapshot = _uiState.value
    val activeSession = snapshot.activeSession() ?: return
    if (!sendFlow.isRuntimeReadyForSend(snapshot.runtime)) {
        applyBlockedRuntimeGuardrail(
            sessionId = activeSession.id,
            uiError = startupFlow.startupBlockError(snapshot.runtime),
        )
        return
    }
    val prepared = conversationService.prepareImageAnalysis(
        state = snapshot,
        imageMessage = createMessage(
            role = MessageRole.USER,
            content = "Analyze attached image",
            kind = MessageKind.IMAGE,
            imagePath = imagePath,
        ),
    ) ?: return
    _uiState.value = prepared.state
    persistState()

    viewModelScope.launch(ioDispatcher) {
        runCatching {
            sendController.analyzeImage(imagePath = imagePath, prompt = "Describe this image.")
        }.onSuccess { result ->
            val mappedError = UiErrorMapper.fromImageResult(result)
            if (mappedError != null) {
                _uiState.value = conversationService.applyImageAnalysisFailure(
                    state = _uiState.value,
                    sessionId = prepared.sessionId,
                    errorMessage = createMessage(
                        role = MessageRole.SYSTEM,
                        content = formatUserFacingError(mappedError),
                        kind = MessageKind.TEXT,
                    ),
                    uiError = mappedError,
                )
                persistState()
                return@onSuccess
            }
            val responseText = when (result) {
                is com.pocketagent.runtime.ImageAnalysisResult.Success -> result.content
                is com.pocketagent.runtime.ImageAnalysisResult.Failure ->
                    result.failure.technicalDetail ?: result.failure.userMessage
            }
            _uiState.value = conversationService.applyImageAnalysisSuccess(
                state = _uiState.value,
                sessionId = prepared.sessionId,
                assistantMessage = createMessage(
                    role = MessageRole.ASSISTANT,
                    content = responseText,
                    kind = MessageKind.IMAGE,
                ),
                runtimeBackend = runtimeFacade.runtimeBackend(),
            )
            persistState()
        }.onFailure { error ->
            val uiError = UiErrorMapper.runtimeFailure(error.message ?: "Image analysis failed.")
            _uiState.value = conversationService.applyImageAnalysisFailure(
                state = _uiState.value,
                sessionId = prepared.sessionId,
                errorMessage = createMessage(
                    role = MessageRole.SYSTEM,
                    content = formatUserFacingError(uiError),
                    kind = MessageKind.TEXT,
                ),
                uiError = uiError,
            )
            persistState()
        }
    }
}

internal fun ChatViewModel.runToolInternal(toolName: String, jsonArgs: String) {
    val toolCallId = newToolCallId()
    val prepared = conversationService.prepareToolExecution(
        state = _uiState.value,
        requestMessage = createMessage(
            role = MessageRole.USER,
            content = "Run tool: $toolName",
            kind = MessageKind.TEXT,
        ),
        assistantToolCallMessage = createMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            kind = MessageKind.TOOL,
            toolName = toolName,
            toolArgsJson = jsonArgs,
            toolCallId = toolCallId,
            toolCallStatus = PersistedToolCallStatus.RUNNING,
        ),
        toolCallId = toolCallId,
    ) ?: return
    _uiState.value = prepared.state
    persistState()
    executeToolCommand(
        sessionId = prepared.sessionId,
        toolName = toolName,
        jsonArgs = jsonArgs,
        toolCallId = prepared.toolCallId,
    )
}

internal fun ChatViewModel.exportDiagnosticsInternal() {
    val activeSessionId = _uiState.value.activeSession()?.id ?: return
    viewModelScope.launch(ioDispatcher) {
        runCatching { runtimeFacade.exportDiagnostics() }
            .onSuccess { diagnostics ->
                val update = conversationService.appendDiagnostics(
                    state = _uiState.value,
                    diagnosticsMessage = createMessage(
                        role = MessageRole.SYSTEM,
                        content = diagnostics,
                        kind = MessageKind.DIAGNOSTIC,
                    ),
                ) ?: return@onSuccess
                _uiState.value = update.state
                if (update.shouldPersist) {
                    persistState()
                }
            }
            .onFailure { error ->
                val uiError = UiErrorMapper.runtimeFailure(error.message ?: "Diagnostics export failed.")
                val update = conversationService.appendDiagnosticsFailure(
                    state = _uiState.value,
                    errorMessage = createMessage(
                        role = MessageRole.SYSTEM,
                        content = formatUserFacingError(uiError),
                        kind = MessageKind.TEXT,
                    ),
                    uiError = uiError,
                ) ?: return@onFailure
                _uiState.value = update.state
                if (update.shouldPersist) {
                    persistState()
                }
            }
    }
}

internal fun ChatViewModel.hydrateSessionMessagesIfNeeded(sessionId: String) {
    val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
    if (session.messagesLoaded) {
        return
    }
    viewModelScope.launch(ioDispatcher) {
        val messages = persistenceFlow.loadSessionMessages(sessionId).orEmpty()
        val hydratedSession = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return@launch
        if (hydratedSession.messagesLoaded) {
            return@launch
        }
        val update = conversationService.hydrateSession(
            state = _uiState.value,
            sessionId = sessionId,
            messages = messages,
        )
        val restoredSession = update.state.sessions.firstOrNull { it.id == sessionId } ?: return@launch
        runtimeFacade.restoreSession(
            sessionId = SessionId(sessionId),
            turns = timelineProjector.toTurns(restoredSession),
        )
        _uiState.value = update.state
        if (update.shouldPersist) {
            persistState()
        }
    }
}

private fun ChatViewModel.applyConversationUpdate(update: ChatStateUpdate) {
    _uiState.value = update.state
    update.hydrateSessionId?.let(::hydrateSessionMessagesIfNeeded)
    if (update.shouldPersist) {
        persistState()
    }
}
