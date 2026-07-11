package com.pocketagent.android.ui

import androidx.lifecycle.viewModelScope
import com.pocketagent.android.runtime.RuntimeSessionCreationResult
import com.pocketagent.android.ui.controllers.ChatStateUpdate
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.android.ui.state.activeSession
import com.pocketagent.core.SessionId
import kotlinx.coroutines.CancellationException
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
    launchRuntimeSessionMutation(
        unexpectedFailure = UiErrorMapper::runtimeSessionCreationFailure,
    ) {
        when (val creation = runtimeFacade.createRuntimeSession()) {
            is RuntimeSessionCreationResult.Created -> applyConversationUpdate(
                conversationService.createSession(
                    state = _uiState.value,
                    sessionId = creation.sessionId.value,
                    nowEpochMs = System.currentTimeMillis(),
                ),
                clearRuntimeSessionError = true,
            )
            is RuntimeSessionCreationResult.Unavailable -> {
                applyRuntimeSessionError(UiErrorMapper.fromRuntimeSessionUnavailable(creation))
            }
        }
    }
}

internal fun ChatViewModel.switchSessionInternal(sessionId: String) {
    applyConversationUpdate(conversationService.switchSession(_uiState.value, sessionId))
}

@Suppress("TooGenericExceptionCaught")
internal fun ChatViewModel.deleteSessionInternal(sessionId: String) {
    val snapshot = _uiState.value
    if (snapshot.sessions.none { session -> session.id == sessionId }) {
        return
    }
    val requiresReplacement = snapshot.sessions.size == 1
    launchRuntimeSessionMutation(
        unexpectedFailure = UiErrorMapper::runtimeSessionDeletionFailure,
    ) {
        val replacementSessionId = if (requiresReplacement) {
            when (val creation = runtimeFacade.createRuntimeSession()) {
                is RuntimeSessionCreationResult.Created -> creation.sessionId
                is RuntimeSessionCreationResult.Unavailable -> {
                    applyRuntimeSessionError(UiErrorMapper.fromRuntimeSessionUnavailable(creation))
                    return@launchRuntimeSessionMutation
                }
            }
        } else {
            null
        }
        val deleted = try {
            runtimeFacade.deleteSession(SessionId(sessionId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RuntimeException) {
            val rollbackDetail = replacementSessionId?.let { rollbackRuntimeSession(it) }
            applyRuntimeSessionError(
                UiErrorMapper.runtimeSessionDeletionFailure(
                    listOfNotNull(error.message ?: error::class.simpleName, rollbackDetail)
                        .joinToString(separator = "; "),
                ),
            )
            return@launchRuntimeSessionMutation
        }
        if (!deleted) {
            val rollbackDetail = replacementSessionId?.let { rollbackRuntimeSession(it) }
            applyRuntimeSessionError(
                UiErrorMapper.runtimeSessionDeletionFailure(
                    listOfNotNull("runtime delete rejected for $sessionId", rollbackDetail)
                        .joinToString(separator = "; "),
                ),
            )
            return@launchRuntimeSessionMutation
        }
        applyConversationUpdate(
            conversationService.deleteSession(
                state = _uiState.value,
                sessionId = sessionId,
                replacementSessionId = replacementSessionId?.value,
                nowEpochMs = System.currentTimeMillis(),
            ),
            clearRuntimeSessionError = true,
        )
    }
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
    if (_uiState.value.activeSession() == null) {
        return
    }
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

@Suppress("TooGenericExceptionCaught")
private fun ChatViewModel.launchRuntimeSessionMutation(
    unexpectedFailure: (String?) -> UiError,
    operation: suspend ChatViewModel.() -> Unit,
) {
    if (!sessionMutationInFlight.compareAndSet(false, true)) {
        applyRuntimeSessionError(UiErrorMapper.runtimeSessionOperationInProgress())
        return
    }
    viewModelScope.launch(ioDispatcher) {
        try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RuntimeException) {
            applyRuntimeSessionError(unexpectedFailure(error.message ?: error::class.simpleName))
        } finally {
            sessionMutationInFlight.set(false)
        }
    }
}

private fun ChatViewModel.rollbackRuntimeSession(sessionId: SessionId): String {
    val rollback = runCatching { runtimeFacade.deleteSession(sessionId) }
    return when {
        rollback.isFailure -> "replacement rollback failed: ${rollback.exceptionOrNull()?.message.orEmpty()}"
        rollback.getOrDefault(false) -> "replacement rollback completed"
        else -> "replacement rollback rejected"
    }
}

private fun ChatViewModel.applyRuntimeSessionError(error: UiError) {
    _uiState.update { state ->
        state.copy(runtime = state.runtime.withUiError(error))
    }
}

private fun ChatViewModel.applyConversationUpdate(
    update: ChatStateUpdate,
    clearRuntimeSessionError: Boolean = false,
) {
    val nextState = if (
        clearRuntimeSessionError && UiErrorMapper.isRuntimeSessionError(update.state.runtime.lastErrorCode)
    ) {
        update.state.copy(runtime = update.state.runtime.clearError())
    } else {
        update.state
    }
    _uiState.value = nextState
    update.hydrateSessionId?.let(::hydrateSessionMessagesIfNeeded)
    if (update.shouldPersist) {
        persistState()
    }
}
