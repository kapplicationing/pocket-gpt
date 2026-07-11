package com.pocketagent.android.ui

import com.pocketagent.android.runtime.AppOperationTrace
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.ui.state.activeSession
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.ToolLoopOutcome
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RecoveryAction
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.StreamingState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.ChatToolCall
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.RuntimeArtifactVerificationException
import com.pocketagent.runtime.RuntimeGenerationCancelledException
import com.pocketagent.runtime.RuntimeGenerationFailureException
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import com.pocketagent.runtime.RuntimeImageAttachmentUnsupportedException
import com.pocketagent.runtime.RuntimeModelLoadPlanningException
import com.pocketagent.runtime.RuntimeTemplateUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
internal fun ChatViewModel.sendMessageInternal() {
    val initialSnapshot = _uiState.value
    val initialSession = initialSnapshot.activeSession() ?: return
    val prompt = initialSnapshot.composer.text.trim()
    if (prompt.isBlank() || initialSnapshot.composer.isSending) {
        return
    }

    if (!sendFlow.isRuntimeReadyForSend(initialSnapshot.runtime)) {
        val uiError = startupFlow.startupBlockError(initialSnapshot.runtime)
        applyBlockedRuntimeGuardrail(
            sessionId = initialSession.id,
            uiError = uiError,
        )
        return
    }

    val requestId = newRequestId()
    val sendOperation = SendOperationLease(
        operationId = requestId,
        sessionId = initialSession.id,
        initialRequestId = requestId,
    )
    if (!tryAcquireSendOperation(sendOperation)) {
        return
    }
    var snapshot = initialSnapshot
    var activeSession = initialSession
    var admitted = false
    var admissionAttemptsRemaining = SEND_ADMISSION_CAS_MAX_ATTEMPTS
    while (!admitted && admissionAttemptsRemaining > 0) {
        admissionAttemptsRemaining -= 1
        val current = _uiState.value
        val currentSession = current.activeSession()
        if (currentSession == null) {
            releaseSendOperation(sendOperation)
            return
        }
        if (
            current.composer.isSending ||
            current.composer.text.trim() != prompt ||
            currentSession.id != initialSession.id ||
            !sendFlow.isRuntimeReadyForSend(current.runtime)
        ) {
            releaseSendOperation(sendOperation)
            return
        }
        val next = current.copy(
            composer = current.composer.copy(isSending = true, isCancelling = false),
            runtime = sendReducer.onSendStarted(
                runtime = current.runtime,
                toolDriven = false,
            ),
        )
        if (_uiState.compareAndSet(current, next)) {
            snapshot = current
            activeSession = currentSession
            admitted = true
            break
        }
    }
    if (!admitted) {
        releaseSendOperation(sendOperation)
        return
    }

    val sendJob = viewModelScope.launch(ioDispatcher) {
        fun releaseCurrentRequest(expectedRequestId: String): Boolean {
            return sendOperation.currentRequestId() == expectedRequestId &&
                releaseSendOperation(sendOperation)
        }

        suspend fun ensureSendOperationActive(expectedRequestId: String) {
            currentCoroutineContext().ensureActive()
            if (
                sendOperation.isCancellationRequested() ||
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != expectedRequestId
            ) {
                throw CancellationException("Send operation is no longer active.")
            }
        }

        fun releasePreparation(
            uiError: UiError? = null,
            userCancelled: Boolean = false,
        ) {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != requestId
            ) {
                return
            }
            _uiState.update { state ->
                if (
                    !isActiveSendOperation(sendOperation) ||
                    sendOperation.currentRequestId() != requestId
                ) {
                    return@update state
                }
                val restoredRuntime = state.runtime.copy(
                    startupProbeState = snapshot.runtime.startupProbeState,
                    modelRuntimeStatus = snapshot.runtime.modelRuntimeStatus,
                    modelStatusDetail = when {
                        uiError != null -> uiError.userMessage
                        userCancelled -> "Generation cancelled."
                        else -> snapshot.runtime.modelStatusDetail
                    },
                    sendElapsedMs = null,
                    sendSlowState = null,
                )
                state.copy(
                    composer = state.composer.copy(isSending = false, isCancelling = false),
                    runtime = if (uiError == null) {
                        restoredRuntime.clearError()
                    } else {
                        restoredRuntime.withUiError(uiError)
                    },
                )
            }
            releaseCurrentRequest(requestId)
        }

        val preparation = try {
            val attachedImages = launchSafeSingleImagePaths(snapshot.composer.attachedImages)
            val userMessage = createMessage(
                role = MessageRole.USER,
                content = prompt,
                kind = if (attachedImages.isNotEmpty()) MessageKind.IMAGE else MessageKind.TEXT,
                imagePath = attachedImages.firstOrNull(),
                imagePaths = attachedImages,
            )
            val projectedMessages = activeSession.messages + userMessage
            val projectedSession = activeSession.copy(
                messages = projectedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
                title = deriveSessionTitle(projectedMessages),
            )
            val transcriptMessages = timelineProjector.toTranscript(projectedSession)
            val previousResponseId = timelineProjector.latestAssistantRequestId(projectedSession)
            AppOperationTrace.suspendSection(
                name = "chat.prepare_stream",
                detail = { "messages=${transcriptMessages.size}" },
            ) {
                InitialSendPreparation(
                    userMessage = userMessage,
                    assistantMessageId = newMessageId(prefix = "assistant-stream"),
                    preparedSendStream = sendFlow.prepareChatStream(
                        sessionId = SessionId(activeSession.id),
                        requestId = requestId,
                        messages = transcriptMessages,
                        promptHint = prompt,
                        previousResponseId = previousResponseId,
                        runtime = snapshot.runtime,
                        completionSettings = activeSession.completionSettings,
                        prepare = runtimeFacade::prepareChatStream,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            releasePreparation(uiError = error.toSendPreparationUiError())
            persistState()
            return@launch
        }

        val userMessage = preparation.userMessage
        val assistantMessageId = preparation.assistantMessageId
        val sendPreparation = preparation.preparedSendStream
        ensureSendOperationActive(requestId)

        val currentDeviceState = sendPreparation.deviceState
        val preparedStream = sendPreparation.preparedStream
        val performanceConfig = preparedStream.plan.effectiveConfig
        val targetPerformanceConfig = preparedStream.plan.baseConfig
        val requestTimeoutMs = preparedStream.plan.requestTimeoutMs
        val assistantPlaceholder = MessageUiModel(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestampEpochMs = System.currentTimeMillis(),
            kind = MessageKind.TEXT,
            isStreaming = true,
            requestId = requestId,
            finishReason = null,
            terminalEventSeen = false,
            interaction = PersistedInteractionMessage(
                role = MessageRole.ASSISTANT.name,
                parts = listOf(PersistedInteractionPart(type = "text", text = "")),
                metadata = mapOf("state" to "streaming"),
            ),
        )
        sendOperation.setCurrentMessageId(assistantMessageId)
        var committed = false
        while (!committed) {
            val state = _uiState.value
            val targetSession = state.sessions.firstOrNull { session -> session.id == activeSession.id }
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != requestId ||
                targetSession == null ||
                targetSession.messages != activeSession.messages ||
                !state.composer.isSending ||
                state.composer.isCancelling ||
                state.composer.text.trim() != prompt ||
                state.composer.attachedImages != snapshot.composer.attachedImages ||
                sendOperation.isCancellationRequested() ||
                sendOperation.isUserCancellationRequested()
            ) {
                break
            }
            val committedMessages = targetSession.messages + userMessage + assistantPlaceholder
            val nextState = state.copy(
                sessions = state.sessions.map { session ->
                    if (session.id == activeSession.id) {
                        sessionService.normalize(
                            session.copy(
                                messages = committedMessages,
                                updatedAtEpochMs = System.currentTimeMillis(),
                                title = deriveSessionTitle(committedMessages),
                            ),
                        )
                    } else {
                        session
                    }
                },
                composer = ComposerUiState(text = "", isSending = true, isCancelling = false),
            )
            committed = _uiState.compareAndSet(state, nextState)
        }
        if (!committed) {
            sendOperation.setCurrentMessageId(null)
            if (sendOperation.isCancellationRequested()) {
                throw CancellationException("Send operation was cancelled before commit.")
            }
            releasePreparation(
                uiError = UiErrorMapper.runtimeFailure(
                    "Conversation changed while the request was being prepared.",
                ),
            )
            persistState()
            return@launch
        }
        persistState()

        var pendingStreamingText: (() -> String)? = null
        var lastStreamingUiUpdateAtMs = 0L
        val sendStartedAtMs = System.currentTimeMillis()
        val streamReducer = StreamStateReducer(requestTimeoutMs = requestTimeoutMs)

        fun flushPendingStreamingText(
            force: Boolean = false,
            triggerToken: String? = null,
            isThinking: Boolean = false,
        ) {
            val snapshotText = pendingStreamingText ?: return
            val now = System.currentTimeMillis()
            val forceByToken = triggerToken?.let(::shouldFlushStreamingToken) ?: false
            val canFlush = force || lastStreamingUiUpdateAtMs == 0L ||
                (now - lastStreamingUiUpdateAtMs) >= STREAM_UI_UPDATE_MIN_INTERVAL_MS ||
                forceByToken
            if (!canFlush) {
                return
            }
            val text = snapshotText().trim()
            if (text.isBlank()) {
                return
            }
            updateStreamingMessage(
                sessionId = activeSession.id,
                messageId = assistantMessageId,
                text = text,
                isThinking = isThinking,
            )
            lastStreamingUiUpdateAtMs = now
        }

        fun finalizeWithRuntimeError(
            uiError: UiError,
            terminalReason: String,
            terminalRequestId: String = requestId,
            terminalEventSeen: Boolean = true,
            terminalModelId: String? = null,
            errorCode: String? = null,
            messageId: String = assistantMessageId,
            appliedConfig: PerformanceRuntimeConfig = performanceConfig,
            targetConfig: PerformanceRuntimeConfig = targetPerformanceConfig,
            deviceState: DeviceState = currentDeviceState,
            fallbackModelId: String? = snapshot.runtime.activeModelId,
        ) {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != terminalRequestId
            ) {
                return
            }
            val partialStreamingText = messageContent(
                sessionId = activeSession.id,
                messageId = messageId,
            ).orEmpty().trim()
            if (partialStreamingText.isNotBlank()) {
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = messageId,
                    finalText = partialStreamingText,
                    role = MessageRole.ASSISTANT,
                    requestId = terminalRequestId,
                    finishReason = terminalReason,
                    terminalEventSeen = terminalEventSeen,
                )
                appendSystemMessage(
                    sessionId = activeSession.id,
                    content = formatUserFacingError(uiError),
                    requestId = terminalRequestId,
                    finishReason = terminalReason,
                    terminalEventSeen = terminalEventSeen,
                )
            } else {
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = messageId,
                    finalText = formatUserFacingError(uiError),
                    role = MessageRole.ASSISTANT,
                    requestId = terminalRequestId,
                    finishReason = terminalReason,
                    terminalEventSeen = terminalEventSeen,
                )
            }
            val requestCanRetry = terminalReason == "timeout" ||
                uiError.recoveryAction == RecoveryAction.RETRY_LOAD
            _uiState.update { state ->
                if (
                    !isActiveSendOperation(sendOperation) ||
                    sendOperation.currentRequestId() != terminalRequestId
                ) {
                    state
                } else {
                    state.copy(
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = state.runtime.copy(
                            startupProbeState = if (requestCanRetry) {
                                StartupProbeState.READY
                            } else {
                                state.runtime.startupProbeState
                            },
                            modelRuntimeStatus = if (requestCanRetry) {
                                ModelRuntimeStatus.READY
                            } else {
                                ModelRuntimeStatus.ERROR
                            },
                            modelStatusDetail = uiError.userMessage,
                            sendElapsedMs = null,
                            sendSlowState = null,
                        ).withUiError(uiError),
                    )
                }
            }
            val released = releaseCurrentRequest(terminalRequestId)
            if (released) {
                runCatching {
                    runtimeTuning.recordFailure(
                        modelId = terminalModelId ?: fallbackModelId,
                        appliedConfig = appliedConfig,
                        targetConfig = targetConfig,
                        errorCode = errorCode ?: terminalReason.removePrefix("failed:"),
                        backendIdentityHint = runCatching {
                            runtimeFacade.runtimeDiagnosticsSnapshot().activeBackend
                        }.getOrNull() ?: snapshot.runtime.activeBackend,
                        thermalThrottled = deviceState.thermalLevel >= 5,
                    )
                }
                runCatching { runtimeFacade.gpuOffloadStatus() }
                    .getOrNull()
                    ?.let { probe -> updateRuntimeGpuProbeStateInternal(probe) }
                refreshRuntimeDiagnostics()
            }
            persistState()
        }

        fun finalizeWithCancellation(
            terminal: StreamTerminalState,
            messageId: String,
            userInitiated: Boolean,
        ) {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != terminal.requestId
            ) {
                return
            }
            val partialStreamingText = messageContent(
                sessionId = activeSession.id,
                messageId = messageId,
            ).orEmpty().trim()
            if (partialStreamingText.isNotBlank()) {
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = messageId,
                    finalText = partialStreamingText,
                    role = MessageRole.ASSISTANT,
                    requestId = terminal.requestId,
                    finishReason = terminal.finishReason,
                    terminalEventSeen = terminal.terminalEventSeen,
                )
            } else {
                updateActiveSession(activeSession.id) { session ->
                    session.copy(
                        messages = session.messages.filterNot { message -> message.id == messageId },
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
            _uiState.update { state ->
                if (
                    !isActiveSendOperation(sendOperation) ||
                    sendOperation.currentRequestId() != terminal.requestId
                ) {
                    state
                } else {
                    val nextRuntime = if (userInitiated) {
                        state.runtime.copy(
                            runtimeBackend = runtimeFacade.runtimeBackend(),
                            startupProbeState = StartupProbeState.READY,
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = "Generation cancelled.",
                            sendElapsedMs = null,
                            sendSlowState = null,
                        ).clearError()
                    } else {
                        state.runtime.copy(
                            sendElapsedMs = null,
                            sendSlowState = null,
                        )
                    }
                    state.copy(
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = nextRuntime,
                    )
                }
            }
            val released = releaseCurrentRequest(terminal.requestId)
            if (released) {
                refreshRuntimeDiagnostics()
            }
            persistState()
        }

        fun isNonTimeoutCancellation(terminal: StreamTerminalState): Boolean {
            val normalizedReason = terminal.finishReason.trim().lowercase()
            val isCancellation = normalizedReason.contains("cancel")
            val isTimeout = normalizedReason.contains("timeout")
            return isCancellation && !isTimeout
        }

        fun finalizeFromTerminal(
            terminal: StreamTerminalState,
            messageId: String,
            turnStartedAtMs: Long,
            appliedConfig: PerformanceRuntimeConfig,
            targetConfig: PerformanceRuntimeConfig,
            deviceState: DeviceState,
            fallbackModelId: String?,
        ): List<ChatToolCall> {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != terminal.requestId
            ) {
                return emptyList()
            }
            val userInitiatedCancellation = sendOperation.isUserCancellationRequested()
            val leaseCancellationRequested = sendOperation.isCancellationRequested()
            val shouldFinalizeAsCancellation = leaseCancellationRequested ||
                isNonTimeoutCancellation(terminal) ||
                (userInitiatedCancellation && terminal.uiError != null)
            if (shouldFinalizeAsCancellation) {
                val cancellationTerminal = if (
                    leaseCancellationRequested && !isNonTimeoutCancellation(terminal)
                ) {
                    terminal.copy(
                        finishReason = "cancelled",
                        terminalEventSeen = false,
                        uiError = null,
                        toolCalls = emptyList(),
                    )
                } else {
                    terminal
                }
                finalizeWithCancellation(
                    terminal = cancellationTerminal,
                    messageId = messageId,
                    userInitiated = userInitiatedCancellation,
                )
                return emptyList()
            }
            if (terminal.uiError != null) {
                finalizeWithRuntimeError(
                    uiError = terminal.uiError,
                    terminalReason = terminal.finishReason,
                    terminalRequestId = terminal.requestId,
                    terminalEventSeen = terminal.terminalEventSeen,
                    terminalModelId = terminal.responseModelId,
                    errorCode = terminal.errorCode,
                    messageId = messageId,
                    appliedConfig = appliedConfig,
                    targetConfig = targetConfig,
                    deviceState = deviceState,
                    fallbackModelId = fallbackModelId,
                )
                return emptyList()
            }
            val requestedToolCalls = if (
                terminal.finishReason == "tool_calls" &&
                terminal.toolCalls.isNotEmpty() &&
                !sendOperation.isCancellationRequested() &&
                isActiveSendOperation(sendOperation) &&
                sendOperation.currentRequestId() == terminal.requestId
            ) {
                terminal.toolCalls
            } else {
                emptyList()
            }
            val continuesWithTools = requestedToolCalls.isNotEmpty()
            sendOperation.trackToolCallIds(requestedToolCalls.map(ChatToolCall::id))
            val finalText = terminal.responseText?.trim().orEmpty()
            val effectiveFirstToken = terminal.firstTokenMs
            val effectiveCompletion = terminal.completionMs ?: (System.currentTimeMillis() - turnStartedAtMs)
            val runtimeStats = terminal.runtimeStats
            val effectivePrefill = runtimeStats?.prefillMs ?: effectiveFirstToken
            val effectiveDecode = runtimeStats?.decodeMs ?: if (effectiveFirstToken != null) {
                (effectiveCompletion - effectiveFirstToken).coerceAtLeast(0L)
            } else {
                null
            }
            val tokensPerSecEstimate = runtimeStats?.tokensPerSec ?: if (
                finalText.isNotBlank() && effectiveDecode != null && effectiveDecode > 0L
            ) {
                val approxTokens = finalText.split(WHITESPACE_REGEX).count { it.isNotBlank() }
                if (approxTokens > 0) {
                    approxTokens.toDouble() / (effectiveDecode.toDouble() / 1000.0)
                } else {
                    null
                }
            } else {
                null
            }
            // Finalize the message with per-message generation metrics
            AppOperationTrace.section(
                name = "chat.streaming_finalize",
                detail = { "chars=${finalText.length}|first_token_ms=${effectiveFirstToken ?: -1}" },
            ) {
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = messageId,
                    finalText = finalText,
                    reasoningContent = terminal.reasoningContent,
                    toolCalls = terminal.toolCalls.toPersistedToolCalls(),
                    requestId = terminal.requestId,
                    finishReason = terminal.finishReason,
                    terminalEventSeen = terminal.terminalEventSeen,
                    firstTokenMs = effectiveFirstToken,
                    tokensPerSec = tokensPerSecEstimate,
                    totalLatencyMs = effectiveCompletion,
                )
            }
            val resolvedRuntimeStats = runtimeStats ?: RuntimeExecutionStats(
                prefillMs = effectivePrefill,
                decodeMs = effectiveDecode,
                tokensPerSec = tokensPerSecEstimate,
            )
            val ownsOperationState = isActiveSendOperation(sendOperation) &&
                sendOperation.currentRequestId() == terminal.requestId
            if (!ownsOperationState) {
                return emptyList()
            }
            _uiState.update { state ->
                    if (
                        !isActiveSendOperation(sendOperation) ||
                        sendOperation.currentRequestId() != terminal.requestId
                    ) {
                        state
                    } else {
                        state.copy(
                            composer = state.composer.copy(
                                isSending = continuesWithTools,
                                isCancelling = false,
                            ),
                            runtime = state.runtime.copy(
                                runtimeBackend = runtimeFacade.runtimeBackend(),
                                startupProbeState = StartupProbeState.READY,
                                modelRuntimeStatus = ModelRuntimeStatus.READY,
                                modelStatusDetail = if (continuesWithTools) {
                                    "Running tools..."
                                } else {
                                    startupFlow.readyStatusDetail(runtimeFacade.runtimeBackend())
                                },
                                activeModelId = terminal.responseModelId,
                                lastFirstTokenLatencyMs = effectiveFirstToken,
                                lastTotalLatencyMs = effectiveCompletion,
                                lastModelLoadMs = runtimeStats?.modelLoadMs,
                                lastPrefillMs = effectivePrefill,
                                lastDecodeMs = effectiveDecode,
                                lastTokensPerSec = tokensPerSecEstimate,
                                lastPeakRssMb = runtimeStats?.peakRssMb,
                                lastResidentHit = runtimeStats?.residentHit,
                                lastResidentHitCount = runtimeStats?.residentHitCount,
                                lastReloadReason = runtimeStats?.reloadReason,
                                lastPrefixCacheHit = runtimeStats?.prefixCacheLastHit,
                                lastPrefixCacheReusedTokens = runtimeStats?.prefixCacheLastReusedTokens,
                                lastPrefixCacheHitRate = runtimeStats?.prefixCacheHitRate,
                                sendElapsedMs = null,
                                sendSlowState = null,
                            ).clearError(),
                        )
                    }
            }
            if (continuesWithTools) {
                sendOperation.setCurrentMessageId(null)
            } else if (!releaseCurrentRequest(terminal.requestId)) {
                return emptyList()
            }
            val runtimeGpuCeiling = listOfNotNull(
                resolvedRuntimeStats.estimatedMaxGpuLayers,
                resolvedRuntimeStats.modelLayerCount,
            ).minOrNull()
            val tunedAppliedConfig = appliedConfig.copy(
                gpuLayers = resolvedRuntimeStats.appliedGpuLayers ?: appliedConfig.gpuLayers,
                speculativeDraftGpuLayers =
                    resolvedRuntimeStats.appliedDraftGpuLayers ?: appliedConfig.speculativeDraftGpuLayers,
            )
            val tunedTargetConfig = targetConfig.copy(
                gpuLayers = runtimeGpuCeiling?.let { minOf(targetConfig.gpuLayers, it) }
                    ?: targetConfig.gpuLayers,
                speculativeDraftGpuLayers = runtimeGpuCeiling?.let {
                    minOf(targetConfig.speculativeDraftGpuLayers, it)
                } ?: targetConfig.speculativeDraftGpuLayers,
            )
            runtimeTuning.recordSuccess(
                modelId = terminal.responseModelId ?: fallbackModelId,
                appliedConfig = tunedAppliedConfig,
                targetConfig = tunedTargetConfig,
                runtimeStats = resolvedRuntimeStats,
                thermalThrottled = deviceState.thermalLevel >= 5,
            )
            refreshRuntimeDiagnostics()
            persistState()
            if (requestedToolCalls.isEmpty()) {
                maybeAdvanceAfterAssistantResponse()
            }
            return requestedToolCalls
        }

        fun finishToolChainWithError(
            requestId: String,
            uiError: UiError,
            toolCallId: String? = null,
        ) {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != requestId
            ) {
                return
            }
            val finishReason = "failed:${uiError.sourceCode ?: "tool_follow_up"}"
            val systemMessage = createMessage(
                role = MessageRole.SYSTEM,
                content = formatUserFacingError(uiError),
                kind = MessageKind.TEXT,
                requestId = requestId,
                finishReason = finishReason,
                terminalEventSeen = true,
            )
            val requestCanRetry = uiError.recoveryAction == RecoveryAction.RETRY_LOAD
            _uiState.update { state ->
                if (
                    !isActiveSendOperation(sendOperation) ||
                    sendOperation.currentRequestId() != requestId
                ) {
                    state
                } else {
                    state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == sendOperation.sessionId) {
                                val updatedMessages = toolCallId?.let { id ->
                                    session.messages.withToolCallStatus(
                                        toolCallId = id,
                                        status = PersistedToolCallStatus.FAILED,
                                    )
                                } ?: session.messages
                                sessionService.normalize(
                                    session.copy(
                                        messages = updatedMessages + systemMessage,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                    ),
                                )
                            } else {
                                session
                            }
                        },
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = state.runtime.copy(
                            startupProbeState = if (requestCanRetry) {
                                StartupProbeState.READY
                            } else {
                                state.runtime.startupProbeState
                            },
                            modelRuntimeStatus = if (requestCanRetry) {
                                ModelRuntimeStatus.READY
                            } else {
                                ModelRuntimeStatus.ERROR
                            },
                            modelStatusDetail = uiError.userMessage,
                            sendElapsedMs = null,
                            sendSlowState = null,
                        ).withUiError(uiError),
                    )
                }
            }
            releaseCurrentRequest(requestId)
            persistState()
        }

        fun finishToolChainWithoutFollowUp(requestId: String, detail: String) {
            if (
                !isActiveSendOperation(sendOperation) ||
                sendOperation.currentRequestId() != requestId
            ) {
                return
            }
            _uiState.update { state ->
                if (
                    !isActiveSendOperation(sendOperation) ||
                    sendOperation.currentRequestId() != requestId
                ) {
                    state
                } else {
                    state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == sendOperation.sessionId) {
                                session.copy(
                                    messages = session.messages.withOwnedActiveToolCallsFailed(
                                        sendOperation.ownedToolCallIds(),
                                    ),
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                )
                            } else {
                                session
                            }
                        },
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = state.runtime.copy(
                            startupProbeState = StartupProbeState.READY,
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = detail,
                            sendElapsedMs = null,
                            sendSlowState = null,
                        ).clearError(),
                    )
                }
            }
            releaseCurrentRequest(requestId)
            persistState()
        }

        suspend fun executeToolCalls(toolCalls: List<ChatToolCall>): Boolean {
            for (toolCall in toolCalls) {
                ensureSendOperationActive(sendOperation.currentRequestId())
                updateToolCallStatus(
                    sessionId = sendOperation.sessionId,
                    toolCallId = toolCall.id,
                    status = PersistedToolCallStatus.RUNNING,
                )
                val outcome = toolLoopUseCase.execute(
                    toolName = toolCall.name,
                    jsonArgs = toolCall.argumentsJson,
                )
                ensureSendOperationActive(sendOperation.currentRequestId())
                when (outcome) {
                    is ToolLoopOutcome.Success -> {
                        val toolMessage = createMessage(
                            role = MessageRole.TOOL,
                            content = outcome.content,
                            kind = MessageKind.TOOL,
                            toolName = toolCall.name,
                            toolCallId = toolCall.id,
                        )
                        updateActiveSession(sendOperation.sessionId) { session ->
                            session.copy(
                                messages = session.messages.withToolCallStatus(
                                    toolCallId = toolCall.id,
                                    status = PersistedToolCallStatus.COMPLETED,
                                ) + toolMessage,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        }
                    }

                    is ToolLoopOutcome.Failure -> {
                        finishToolChainWithError(
                            requestId = sendOperation.currentRequestId(),
                            uiError = outcome.uiError,
                            toolCallId = toolCall.id,
                        )
                        return false
                    }
                }
                persistState()
            }
            return true
        }

        @Suppress("LongMethod")
        suspend fun runAutomaticToolLoop(initialToolCalls: List<ChatToolCall>, initialPromptHint: String) {
            var pendingToolCalls = initialToolCalls
            var promptHint = initialPromptHint
            var round = 0
            while (pendingToolCalls.isNotEmpty() && round < MAX_AUTOMATIC_TOOL_LOOP_ROUNDS) {
                round += 1
                if (!executeToolCalls(pendingToolCalls)) {
                    return
                }
                val loopSnapshot = _uiState.value
                val loopSession = loopSnapshot.sessions
                    .firstOrNull { session -> session.id == sendOperation.sessionId }
                if (loopSession == null) {
                    finishToolChainWithError(
                        requestId = sendOperation.currentRequestId(),
                        uiError = UiErrorMapper.runtimeFailure(
                            "The conversation was removed while tools were running.",
                        ),
                    )
                    return
                }
                val followUpAssistantMessageId = newMessageId(prefix = "assistant-stream")
                val followUpRequestId = newRequestId()
                val previousRequestId = sendOperation.currentRequestId()
                ensureSendOperationActive(previousRequestId)
                if (!sendOperation.transitionRequest(previousRequestId, followUpRequestId)) {
                    finishToolChainWithError(
                        requestId = sendOperation.currentRequestId(),
                        uiError = UiErrorMapper.runtimeFailure(
                            "Tool follow-up request ownership changed unexpectedly.",
                        ),
                    )
                    return
                }
                ensureSendOperationActive(followUpRequestId)
                val followUpTranscript = timelineProjector.toTranscript(loopSession)
                val followUpSendPreparation = try {
                    AppOperationTrace.suspendSection(
                        name = "chat.prepare_stream.tool_follow_up",
                        detail = { "messages=${followUpTranscript.size}|round=$round" },
                    ) {
                        sendFlow.prepareChatStream(
                            sessionId = SessionId(sendOperation.sessionId),
                            requestId = followUpRequestId,
                            messages = followUpTranscript,
                            promptHint = promptHint,
                            previousResponseId = timelineProjector.latestAssistantRequestId(loopSession),
                            runtime = loopSnapshot.runtime,
                            completionSettings = loopSession.completionSettings,
                            prepare = runtimeFacade::prepareChatStream,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    finishToolChainWithError(
                        requestId = followUpRequestId,
                        uiError = error.toSendPreparationUiError(),
                    )
                    return
                }
                ensureSendOperationActive(followUpRequestId)
                val followUpDeviceState = followUpSendPreparation.deviceState
                val followUpPreparedStream = followUpSendPreparation.preparedStream
                val followUpPerformanceConfig = followUpPreparedStream.plan.effectiveConfig
                val followUpTargetPerformanceConfig = followUpPreparedStream.plan.baseConfig
                val followUpRequestTimeoutMs = followUpPreparedStream.plan.requestTimeoutMs
                val followUpStartedAtMs = System.currentTimeMillis()
                val followUpStreamReducer = StreamStateReducer(requestTimeoutMs = followUpRequestTimeoutMs)
                var followUpPendingStreamingText: (() -> String)? = null
                var followUpLastStreamingUiUpdateAtMs = 0L
                var followUpTerminal: StreamTerminalState? = null
                val followUpPlaceholder = MessageUiModel(
                    id = followUpAssistantMessageId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    timestampEpochMs = System.currentTimeMillis(),
                    kind = MessageKind.TEXT,
                    isStreaming = true,
                    requestId = followUpRequestId,
                    finishReason = null,
                    terminalEventSeen = false,
                    interaction = PersistedInteractionMessage(
                        role = MessageRole.ASSISTANT.name,
                        parts = listOf(PersistedInteractionPart(type = "text", text = "")),
                        metadata = mapOf("state" to "streaming"),
                    ),
                )
                sendOperation.setCurrentMessageId(followUpAssistantMessageId)
                var followUpCommitted = false
                while (!followUpCommitted) {
                    val state = _uiState.value
                    val pinnedSession = state.sessions
                        .firstOrNull { session -> session.id == sendOperation.sessionId }
                    if (
                        !isActiveSendOperation(sendOperation) ||
                        sendOperation.currentRequestId() != followUpRequestId ||
                        sendOperation.isCancellationRequested() ||
                        pinnedSession == null ||
                        pinnedSession.messages != loopSession.messages ||
                        !state.composer.isSending ||
                        state.composer.isCancelling
                    ) {
                        break
                    }
                    val nextState = state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == sendOperation.sessionId) {
                                sessionService.normalize(
                                    session.copy(
                                        messages = session.messages + followUpPlaceholder,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                    ),
                                )
                            } else {
                                session
                            }
                        },
                        composer = state.composer.copy(isSending = true, isCancelling = false),
                        runtime = sendReducer.onSendStarted(
                            runtime = state.runtime,
                            toolDriven = true,
                        ),
                    )
                    followUpCommitted = _uiState.compareAndSet(state, nextState)
                }
                if (!followUpCommitted) {
                    sendOperation.setCurrentMessageId(null)
                    if (sendOperation.isCancellationRequested()) {
                        throw CancellationException("Tool follow-up was cancelled before commit.")
                    }
                    finishToolChainWithError(
                        requestId = followUpRequestId,
                        uiError = UiErrorMapper.runtimeFailure(
                            "Conversation changed while the tool follow-up was being prepared.",
                        ),
                    )
                    return
                }
                persistState()

                fun flushFollowUpPendingStreamingText(
                    force: Boolean = false,
                    triggerToken: String? = null,
                    isThinking: Boolean = false,
                ) {
                    val snapshotText = followUpPendingStreamingText ?: return
                    val now = System.currentTimeMillis()
                    val forceByToken = triggerToken?.let(::shouldFlushStreamingToken) ?: false
                    val canFlush = force || followUpLastStreamingUiUpdateAtMs == 0L ||
                        (now - followUpLastStreamingUiUpdateAtMs) >= STREAM_UI_UPDATE_MIN_INTERVAL_MS ||
                        forceByToken
                    if (!canFlush) {
                        return
                    }
                    val text = snapshotText().trim()
                    if (text.isBlank()) {
                        return
                    }
                    updateStreamingMessage(
                        sessionId = sendOperation.sessionId,
                        messageId = followUpAssistantMessageId,
                        text = text,
                        isThinking = isThinking,
                    )
                    followUpLastStreamingUiUpdateAtMs = now
                }

                ensureSendOperationActive(followUpRequestId)
                streamCoordinator.collectStream(
                    runtimeService = runtimeFacade,
                    preparedStream = followUpPreparedStream,
                    streamReducer = followUpStreamReducer,
                    sendStartedAtMs = followUpStartedAtMs,
                    onEvent = { event, nextState ->
                        if (event is ChatStreamEvent.Delta) {
                            when (val delta = event.delta) {
                                is ChatStreamDelta.TextDelta -> {
                                    followUpPendingStreamingText = followUpStreamReducer::snapshotText
                                    flushFollowUpPendingStreamingText(
                                        triggerToken = delta.text,
                                        isThinking = nextState.isThinking,
                                    )
                                }
                            }
                        } else if (event is ChatStreamEvent.Thinking) {
                            updateStreamingMessage(
                                sessionId = sendOperation.sessionId,
                                messageId = followUpAssistantMessageId,
                                text = followUpStreamReducer.snapshotText(),
                                isThinking = nextState.isThinking,
                            )
                        }
                        val detail = sendReducer.statusDetailForEvent(event)
                        if (!detail.isNullOrBlank() && !sendOperation.isCancellationRequested()) {
                            _uiState.update { state ->
                                state.copy(
                                    runtime = state.runtime.copy(
                                        modelStatusDetail = detail,
                                    ),
                                )
                            }
                        }
                    },
                    onElapsed = { elapsed, slowState ->
                        _uiState.update { state ->
                            state.copy(
                                runtime = state.runtime.copy(
                                    sendElapsedMs = elapsed,
                                    sendSlowState = slowState,
                                ),
                            )
                        }
                    },
                    onBeforeTerminal = {
                        flushFollowUpPendingStreamingText(
                            force = true,
                            isThinking = false,
                        )
                    },
                    onTerminal = { terminal ->
                        if (
                            isActiveSendOperation(sendOperation) &&
                            sendOperation.currentRequestId() == terminal.requestId
                        ) {
                            followUpTerminal = terminal
                        }
                    },
                )
                val terminal = followUpTerminal
                if (terminal == null) {
                    finishToolChainWithError(
                        requestId = followUpRequestId,
                        uiError = UiErrorMapper.runtimeFailure(
                            "Tool follow-up stream ended without a terminal result.",
                        ),
                    )
                    return
                }
                pendingToolCalls = finalizeFromTerminal(
                    terminal = terminal,
                    messageId = followUpAssistantMessageId,
                    turnStartedAtMs = followUpStartedAtMs,
                    appliedConfig = followUpPerformanceConfig,
                    targetConfig = followUpTargetPerformanceConfig,
                    deviceState = followUpDeviceState,
                    fallbackModelId = loopSnapshot.runtime.activeModelId,
                )
                promptHint = terminal.responseText?.trim().orEmpty().ifBlank { promptHint }
            }

            if (pendingToolCalls.isNotEmpty()) {
                appendSystemMessage(
                    sessionId = sendOperation.sessionId,
                    content = "Tool loop stopped after $MAX_AUTOMATIC_TOOL_LOOP_ROUNDS rounds.",
                )
                finishToolChainWithoutFollowUp(
                    requestId = sendOperation.currentRequestId(),
                    detail = "Tool loop stopped after $MAX_AUTOMATIC_TOOL_LOOP_ROUNDS rounds.",
                )
            }
        }

        var pendingToolCallsFromTurn: List<ChatToolCall> = emptyList()
        ensureSendOperationActive(requestId)
        streamCoordinator.collectStream(
            runtimeService = runtimeFacade,
            preparedStream = preparedStream,
            streamReducer = streamReducer,
            sendStartedAtMs = sendStartedAtMs,
            onEvent = { event, nextState ->
                if (event is ChatStreamEvent.Delta) {
                    when (val delta = event.delta) {
                        is ChatStreamDelta.TextDelta -> {
                            pendingStreamingText = streamReducer::snapshotText
                            flushPendingStreamingText(
                                triggerToken = delta.text,
                                isThinking = nextState.isThinking,
                            )
                        }
                    }
                } else if (event is ChatStreamEvent.Thinking) {
                    updateStreamingMessage(
                        sessionId = activeSession.id,
                        messageId = assistantMessageId,
                        text = streamReducer.snapshotText(),
                        isThinking = nextState.isThinking,
                    )
                }
                val detail = sendReducer.statusDetailForEvent(event)
                if (!detail.isNullOrBlank() && !sendOperation.isCancellationRequested()) {
                    _uiState.update { state ->
                        state.copy(
                            runtime = state.runtime.copy(
                                modelStatusDetail = detail,
                            ),
                        )
                    }
                }
            },
            onElapsed = { elapsed, slowState ->
                _uiState.update { state ->
                    state.copy(
                        runtime = state.runtime.copy(
                            sendElapsedMs = elapsed,
                            sendSlowState = slowState,
                        ),
                    )
                }
            },
            onBeforeTerminal = {
                flushPendingStreamingText(
                    force = true,
                    isThinking = false,
                )
            },
            onTerminal = { terminal ->
                if (
                    isActiveSendOperation(sendOperation) &&
                    sendOperation.currentRequestId() == terminal.requestId
                ) {
                    pendingToolCallsFromTurn = finalizeFromTerminal(
                        terminal = terminal,
                        messageId = assistantMessageId,
                        turnStartedAtMs = sendStartedAtMs,
                        appliedConfig = performanceConfig,
                        targetConfig = targetPerformanceConfig,
                        deviceState = currentDeviceState,
                        fallbackModelId = snapshot.runtime.activeModelId,
                    )
                }
            },
        )
        if (pendingToolCallsFromTurn.isNotEmpty()) {
            runAutomaticToolLoop(
                initialToolCalls = pendingToolCallsFromTurn,
                initialPromptHint = prompt,
            )
        }
    }
    sendOperation.attachJob(sendJob)
    sendJob.invokeOnCompletion { cause ->
        when {
            cause is CancellationException || sendOperation.isCancellationRequested() ->
                finishCancelledSendOperation(sendOperation)
            cause != null -> finishFailedSendOperation(sendOperation, cause)
            isActiveSendOperation(sendOperation) -> finishFailedSendOperation(
                sendOperation = sendOperation,
                cause = IllegalStateException("Send operation completed without terminal cleanup."),
            )
        }
    }
}

private fun ChatViewModel.finishCancelledSendOperation(sendOperation: SendOperationLease) {
    if (!isActiveSendOperation(sendOperation)) {
        return
    }
    val messageId = sendOperation.currentMessageId()
    val partialText = messageId?.let { currentMessageId ->
        messageContent(
            sessionId = sendOperation.sessionId,
            messageId = currentMessageId,
        ).orEmpty().trim()
    }.orEmpty()
    val userInitiated = sendOperation.isUserCancellationRequested()
    _uiState.update { state ->
        if (!isActiveSendOperation(sendOperation)) {
            state
        } else {
            val updatedSessions = state.sessions.map { session ->
                if (session.id == sendOperation.sessionId) {
                    sessionService.normalize(
                        session.copy(
                            messages = session.messages
                                .withOwnedActiveToolCallsFailed(sendOperation.ownedToolCallIds())
                                .withAbortedStreamingMessage(
                                    messageId = messageId,
                                    partialText = partialText,
                                    requestId = sendOperation.currentRequestId(),
                                    finishReason = "cancelled",
                                ),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    session
                }
            }
            val nextRuntime = if (userInitiated) {
                state.runtime.copy(
                    startupProbeState = StartupProbeState.READY,
                    modelRuntimeStatus = ModelRuntimeStatus.READY,
                    modelStatusDetail = "Generation cancelled.",
                    sendElapsedMs = null,
                    sendSlowState = null,
                ).clearError()
            } else {
                state.runtime.copy(
                    sendElapsedMs = null,
                    sendSlowState = null,
                )
            }
            state.copy(
                sessions = updatedSessions,
                streaming = state.streaming.clearedFor(sendOperation.sessionId, messageId),
                composer = state.composer.copy(isSending = false, isCancelling = false),
                runtime = nextRuntime,
            )
        }
    }
    if (releaseSendOperation(sendOperation)) {
        persistState()
    }
}

private fun ChatViewModel.finishFailedSendOperation(
    sendOperation: SendOperationLease,
    cause: Throwable,
) {
    if (!isActiveSendOperation(sendOperation)) {
        return
    }
    val requestId = sendOperation.currentRequestId()
    val messageId = sendOperation.currentMessageId()
    val partialText = messageId?.let { currentMessageId ->
        messageContent(
            sessionId = sendOperation.sessionId,
            messageId = currentMessageId,
        ).orEmpty().trim()
    }.orEmpty()
    val uiError = UiErrorMapper.runtimeFailure(
        errorCode = "send_operation_failed",
        detail = cause.message ?: cause::class.simpleName,
    )
    val finishReason = "failed:send_operation_failed"
    val systemMessage = createMessage(
        role = MessageRole.SYSTEM,
        content = formatUserFacingError(uiError),
        kind = MessageKind.TEXT,
        requestId = requestId,
        finishReason = finishReason,
        terminalEventSeen = false,
    )
    _uiState.update { state ->
        if (
            !isActiveSendOperation(sendOperation) ||
            sendOperation.currentRequestId() != requestId
        ) {
            state
        } else {
            val updatedSessions = state.sessions.map { session ->
                if (session.id == sendOperation.sessionId) {
                    sessionService.normalize(
                        session.copy(
                            messages = session.messages
                                .withOwnedActiveToolCallsFailed(sendOperation.ownedToolCallIds())
                                .withAbortedStreamingMessage(
                                    messageId = messageId,
                                    partialText = partialText,
                                    requestId = requestId,
                                    finishReason = finishReason,
                                ) + systemMessage,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    session
                }
            }
            state.copy(
                sessions = updatedSessions,
                streaming = state.streaming.clearedFor(sendOperation.sessionId, messageId),
                composer = state.composer.copy(isSending = false, isCancelling = false),
                runtime = state.runtime.copy(
                    modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                    modelStatusDetail = uiError.userMessage,
                    sendElapsedMs = null,
                    sendSlowState = null,
                ).withUiError(uiError),
            )
        }
    }
    if (releaseSendOperation(sendOperation)) {
        persistState()
    }
}

private fun List<ChatToolCall>.toPersistedToolCalls(): List<PersistedToolCall> {
    return map { call ->
        PersistedToolCall(
            id = call.id,
            name = call.name,
            argumentsJson = call.argumentsJson,
            status = PersistedToolCallStatus.PENDING,
        )
    }
}

private fun List<MessageUiModel>.withToolCallStatus(
    toolCallId: String,
    status: PersistedToolCallStatus,
): List<MessageUiModel> {
    return map { message ->
        val interaction = message.interaction ?: return@map message
        if (interaction.toolCalls.none { toolCall -> toolCall.id == toolCallId }) {
            return@map message
        }
        message.copy(
            interaction = interaction.copy(
                toolCalls = interaction.toolCalls.map { toolCall ->
                    if (toolCall.id == toolCallId) {
                        toolCall.copy(status = status)
                    } else {
                        toolCall
                    }
                },
            ),
        )
    }
}

private fun List<MessageUiModel>.withOwnedActiveToolCallsFailed(
    ownedToolCallIds: Set<String>,
): List<MessageUiModel> {
    if (ownedToolCallIds.isEmpty()) {
        return this
    }
    return map { message ->
        val interaction = message.interaction ?: return@map message
        val nextToolCalls = interaction.toolCalls.map { toolCall ->
            if (
                toolCall.id in ownedToolCallIds &&
                (
                    toolCall.status == PersistedToolCallStatus.PENDING ||
                        toolCall.status == PersistedToolCallStatus.RUNNING
                    )
            ) {
                toolCall.copy(status = PersistedToolCallStatus.FAILED)
            } else {
                toolCall
            }
        }
        if (nextToolCalls == interaction.toolCalls) {
            message
        } else {
            message.copy(interaction = interaction.copy(toolCalls = nextToolCalls))
        }
    }
}

private fun List<MessageUiModel>.withAbortedStreamingMessage(
    messageId: String?,
    partialText: String,
    requestId: String,
    finishReason: String,
): List<MessageUiModel> {
    if (messageId == null) {
        return this
    }
    return mapNotNull { message ->
        if (message.id != messageId || !message.isStreaming) {
            return@mapNotNull message
        }
        if (partialText.isBlank()) {
            return@mapNotNull null
        }
        val interaction = message.interaction ?: PersistedInteractionMessage(
            role = MessageRole.ASSISTANT.name,
            parts = emptyList(),
        )
        message.copy(
            content = partialText,
            isStreaming = false,
            isThinking = false,
            requestId = requestId,
            finishReason = finishReason,
            terminalEventSeen = false,
            interaction = interaction.copy(
                parts = listOf(PersistedInteractionPart(type = "text", text = partialText)),
                metadata = interaction.metadata + ("state" to "final"),
            ),
        )
    }
}

private fun StreamingState.clearedFor(sessionId: String, messageId: String?): StreamingState {
    return if (this.sessionId == sessionId && this.messageId == messageId) {
        StreamingState()
    } else {
        this
    }
}

private fun Exception.toSendPreparationUiError(): UiError {
    return when (this) {
        is RuntimeDomainException -> UiErrorMapper.runtimeFailure(
            errorCode = domainError.code,
            detail = domainError.technicalDetail ?: message,
        )
        is RuntimeModelLoadPlanningException -> UiErrorMapper.runtimeFailure(
            errorCode = errorCode,
            detail = message,
        )
        is RuntimeImageAttachmentUnsupportedException -> UiErrorMapper.runtimeFailure(
            errorCode = errorCode,
            detail = message,
        )
        is RuntimeArtifactVerificationException -> UiErrorMapper.runtimeFailure(
            errorCode = errorCode,
            detail = message,
        )
        is RuntimeGenerationFailureException -> UiErrorMapper.runtimeFailure(
            errorCode = errorCode,
            detail = message,
        )
        is RuntimeTemplateUnavailableException -> UiErrorMapper.runtimeFailure(
            errorCode = "template_unavailable",
            detail = message,
        )
        is RuntimeGenerationTimeoutException -> UiErrorMapper.runtimeTimeout(timeoutMs)
        is RuntimeGenerationCancelledException -> UiErrorMapper.runtimeCancelled(message)
        else -> UiErrorMapper.runtimeFailure(message ?: this::class.simpleName)
    }
}

private data class InitialSendPreparation(
    val userMessage: MessageUiModel,
    val assistantMessageId: String,
    val preparedSendStream: ChatSendFlow.PreparedSendStream,
)

private fun shouldFlushStreamingToken(token: String): Boolean {
    val trimmed = token.trim()
    return token.contains('\n') ||
        trimmed.endsWith(".") ||
        trimmed.endsWith("!") ||
        trimmed.endsWith("?")
}

private const val MAX_AUTOMATIC_TOOL_LOOP_ROUNDS = 2
private const val SEND_ADMISSION_CAS_MAX_ATTEMPTS = 8
