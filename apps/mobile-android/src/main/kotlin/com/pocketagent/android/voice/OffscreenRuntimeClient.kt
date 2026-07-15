package com.pocketagent.android.voice

import android.content.Context
import android.os.SystemClock
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.RuntimeSessionCreationResult
import com.pocketagent.android.runtime.RuntimeSessionUnavailableException
import com.pocketagent.android.runtime.resolveAppForegroundRuntimeServices
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ChatToolCall
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatKeepAlivePreference
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeMemoryRetention
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.SamplingOverrides
import com.pocketagent.runtime.toLegacyString
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class OffscreenRuntimeTurnResult(
    val assistantText: String,
    val toolOutputs: List<String>,
    val requiresFollowUpCapture: Boolean = false,
)

internal class OffscreenRuntimeClient(
    private val runtimeGateway: ChatRuntimeService,
    private val loadLastUsedModel: suspend () -> RuntimeModelLifecycleCommandResult,
    private val deviceStateProvider: () -> DeviceState,
    private val isVisibleVoiceSession: () -> Boolean = { false },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val localDateTimeNow: () -> LocalDateTime = LocalDateTime::now,
) {
    private data class PendingAction(
        val toolCall: ChatToolCall,
        val validation: VoiceActionValidation.Valid,
        val expiresAtElapsedMillis: Long,
        val source: VoiceInvocationSource,
        val voiceSessionId: Long,
        val attempts: Int = 0,
    )

    private var pendingAction: PendingAction? = null

    private data class PendingClarification(
        val clarification: VoiceActionClarification,
        val expiresAtElapsedMillis: Long,
        val source: VoiceInvocationSource,
        val voiceSessionId: Long,
        val attempts: Int = 0,
    )

    private var pendingClarification: PendingClarification? = null

    suspend fun warmLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return loadLastUsedModel().also {
            runtimeGateway.touchKeepAlive()
        }
    }

    suspend fun runVoiceTurn(
        transcript: String,
        systemPrompt: String,
        invocationSource: VoiceInvocationSource = VoiceInvocationSource.ASSISTANT,
        voiceSessionId: Long = VoiceSessionSignals.NO_SESSION_ID,
    ): OffscreenRuntimeTurnResult {
        pendingAction?.let { pending ->
            return handlePendingAction(transcript, pending, invocationSource, voiceSessionId)
        }
        pendingClarification?.let { pending ->
            return handlePendingClarification(transcript, pending, invocationSource, voiceSessionId)
        }
        when (val localAction = DeterministicVoiceActionParser.parse(transcript)) {
            is DeterministicVoiceActionParseResult.Proposal -> {
                val actionResult = handleProposedActions(
                    toolCalls = listOf(localAction.toolCall),
                    source = invocationSource,
                    voiceSessionId = voiceSessionId,
                    currentTranscript = transcript,
                    origin = VoiceActionProposalOrigin.DETERMINISTIC,
                )
                return OffscreenRuntimeTurnResult(
                    assistantText = "",
                    toolOutputs = actionResult.toolOutputs,
                    requiresFollowUpCapture = actionResult.requiresFollowUpCapture,
                )
            }
            is DeterministicVoiceActionParseResult.Clarification -> {
                localAction.followUp?.let { followUp ->
                    pendingClarification = PendingClarification(
                        clarification = followUp,
                        expiresAtElapsedMillis = Long.MAX_VALUE,
                        source = invocationSource,
                        voiceSessionId = voiceSessionId,
                    )
                }
                return OffscreenRuntimeTurnResult(
                    assistantText = localAction.message,
                    toolOutputs = emptyList(),
                    requiresFollowUpCapture = localAction.followUp != null,
                )
            }
            DeterministicVoiceActionParseResult.NoMatch -> Unit
        }
        if (invocationSource == VoiceInvocationSource.LOCKED_ASSISTANT) {
            return OffscreenRuntimeTurnResult(
                assistantText = "Unlock your phone for personal questions and other conversational requests.",
                toolOutputs = emptyList(),
            )
        }
        val modelReadiness = warmLastUsedModel()
        if (!modelReadiness.success || modelReadiness.queued) {
            return OffscreenRuntimeTurnResult(
                assistantText = "Open PocketAgent and load a chat model, then ask again.",
                toolOutputs = emptyList(),
            )
        }
        runtimeGateway.touchKeepAlive()
        val sessionId = when (val creation = runtimeGateway.createRuntimeSession()) {
            is RuntimeSessionCreationResult.Created -> creation.sessionId
            is RuntimeSessionCreationResult.Unavailable -> throw RuntimeSessionUnavailableException(creation)
        }
        return try {
            val preparedStream = runtimeGateway.prepareChatStream(
                ChatStreamCommand(
                    sessionId = sessionId,
                    requestId = "voice-${System.currentTimeMillis()}",
                    messages = listOf(
                        InteractionMessage(
                            role = InteractionRole.USER,
                            parts = listOf(InteractionContentPart.Text(transcript)),
                        ),
                    ),
                    promptHint = VOICE_PROMPT_HINT,
                    deviceState = deviceStateProvider(),
                    performanceProfile = RuntimePerformanceProfile.BALANCED,
                    gpuEnabled = false,
                    gpuQualifiedLayers = 0,
                    keepAlivePreference = ChatKeepAlivePreference.AUTO,
                    samplingOverrides = SamplingOverrides(
                        systemPrompt = systemPrompt,
                        temperature = 0.2f,
                        topP = 0.9f,
                        topK = 30,
                        maxTokens = 160,
                    ),
                    memoryRetention = RuntimeMemoryRetention.EPHEMERAL,
                ),
            )
            val response = awaitCompletedResponse(preparedStream)
            val actionResult = handleProposedActions(
                toolCalls = response.toolCalls,
                source = invocationSource,
                voiceSessionId = voiceSessionId,
                currentTranscript = transcript,
                origin = VoiceActionProposalOrigin.MODEL,
            )
            runtimeGateway.touchKeepAlive()
            OffscreenRuntimeTurnResult(
                assistantText = response.text,
                toolOutputs = actionResult.toolOutputs,
                requiresFollowUpCapture = actionResult.requiresFollowUpCapture,
            )
        } finally {
            runtimeGateway.deleteSession(sessionId)
        }
    }

    private fun handleProposedActions(
        toolCalls: List<ChatToolCall>,
        source: VoiceInvocationSource,
        voiceSessionId: Long,
        currentTranscript: String,
        origin: VoiceActionProposalOrigin,
    ): ActionHandlingResult {
        if (toolCalls.isEmpty()) {
            return ActionHandlingResult(emptyList())
        }
        if (toolCalls.size != 1) {
            return ActionHandlingResult(
                listOf(
                    "I won't run multiple phone actions from one voice request. " +
                        "Please ask for one action at a time.",
                ),
            )
        }
        val toolCall = toolCalls.single()
        val validation = when (
            val result = VoiceActionCatalog.validateLegacy(toolCall.name, toolCall.argumentsJson)
        ) {
            is VoiceActionValidation.Valid -> result
            is VoiceActionValidation.Invalid -> {
                return ActionHandlingResult(listOf(result.userMessage))
            }
        }
        if (
            source == VoiceInvocationSource.LOCKED_ASSISTANT &&
            validation.risk == VoiceActionRisk.APP_LAUNCH
        ) {
            return ActionHandlingResult(
                listOf("Unlock your phone before asking Offas to open an app."),
            )
        }
        if (
            source == VoiceInvocationSource.LOCKED_ASSISTANT &&
            validation.request.name == AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE
        ) {
            return ActionHandlingResult(
                listOf("Unlock your phone before asking Offas to change the flashlight."),
            )
        }
        if (
            VoiceActionCatalog.requiresVisibleSession(source, validation.risk) &&
            !isVisibleVoiceSession()
        ) {
            return ActionHandlingResult(
                listOf(
                    "Tap Talk now in the PocketAgent notification and ask again " +
                        "so the action stays visible.",
                ),
            )
        }
        if (
            VoiceActionCatalog.requiresConfirmation(
                source = source,
                action = validation,
                transcript = currentTranscript,
                origin = origin,
            )
        ) {
            pendingAction = PendingAction(
                toolCall = toolCall,
                validation = validation,
                expiresAtElapsedMillis = Long.MAX_VALUE,
                source = source,
                voiceSessionId = voiceSessionId,
            )
            return ActionHandlingResult(
                toolOutputs = listOf("${validation.preview} Say yes to confirm or no to cancel."),
                requiresFollowUpCapture = true,
            )
        }
        return ActionHandlingResult(listOf(executeOnce(toolCall)))
    }

    private fun handlePendingAction(
        transcript: String,
        pending: PendingAction,
        source: VoiceInvocationSource,
        voiceSessionId: Long,
    ): OffscreenRuntimeTurnResult {
        if (source != pending.source ||
            voiceSessionId != pending.voiceSessionId ||
            elapsedRealtimeMillis() > pending.expiresAtElapsedMillis
        ) {
            pendingAction = null
            return OffscreenRuntimeTurnResult(
                assistantText = "",
                toolOutputs = listOf("That action expired. Please ask again."),
            )
        }
        return when (parseVoiceConfirmation(transcript)) {
            VoiceConfirmationDecision.CONFIRM -> {
                pendingAction = null
                if (
                    VoiceActionCatalog.requiresVisibleSession(source, pending.validation.risk) &&
                    !isVisibleVoiceSession()
                ) {
                    OffscreenRuntimeTurnResult(
                        assistantText = "",
                        toolOutputs = listOf(
                            "Open PocketAgent and ask again so Android can safely show the requested app.",
                        ),
                    )
                } else {
                    OffscreenRuntimeTurnResult(
                        assistantText = "",
                        toolOutputs = listOf(executeOnce(pending.toolCall)),
                    )
                }
            }
            VoiceConfirmationDecision.CANCEL -> {
                pendingAction = null
                OffscreenRuntimeTurnResult(assistantText = "", toolOutputs = listOf("Cancelled."))
            }
            VoiceConfirmationDecision.UNKNOWN -> {
                val attempts = pending.attempts + 1
                if (attempts >= MAX_CONFIRMATION_ATTEMPTS) {
                    pendingAction = null
                    OffscreenRuntimeTurnResult(
                        assistantText = "",
                        toolOutputs = listOf("I didn't hear a clear yes or no, so I cancelled the action."),
                    )
                } else {
                    pendingAction = pending.copy(attempts = attempts)
                    OffscreenRuntimeTurnResult(
                        assistantText = "",
                        toolOutputs = listOf("Please say yes to confirm or no to cancel."),
                        requiresFollowUpCapture = true,
                    )
                }
            }
        }
    }

    private fun handlePendingClarification(
        transcript: String,
        pending: PendingClarification,
        source: VoiceInvocationSource,
        voiceSessionId: Long,
    ): OffscreenRuntimeTurnResult {
        if (source != pending.source ||
            voiceSessionId != pending.voiceSessionId ||
            elapsedRealtimeMillis() > pending.expiresAtElapsedMillis
        ) {
            pendingClarification = null
            return OffscreenRuntimeTurnResult(
                assistantText = "That clarification expired. Please ask again.",
                toolOutputs = emptyList(),
            )
        }
        if (parseVoiceConfirmation(transcript) == VoiceConfirmationDecision.CANCEL) {
            pendingClarification = null
            return OffscreenRuntimeTurnResult(assistantText = "Cancelled.", toolOutputs = emptyList())
        }
        return when (
            val completed = DeterministicVoiceActionParser.completeClarification(
                clarification = pending.clarification,
                transcript = transcript,
                now = localDateTimeNow(),
            )
        ) {
            is DeterministicVoiceActionParseResult.Proposal -> {
                pendingClarification = null
                val transcriptWithPeriod = when (val clarification = pending.clarification) {
                    is VoiceActionClarification.AlarmPeriod -> {
                        val spokenTime = if (clarification.minute == 0) {
                            clarification.hour.toString()
                        } else {
                            "${clarification.hour}:${clarification.minute.toString().padStart(2, '0')}"
                        }
                        "set alarm at $spokenTime $transcript"
                    }
                }
                val actionResult = handleProposedActions(
                    toolCalls = listOf(completed.toolCall),
                    source = source,
                    voiceSessionId = voiceSessionId,
                    currentTranscript = transcriptWithPeriod,
                    origin = VoiceActionProposalOrigin.DETERMINISTIC,
                )
                OffscreenRuntimeTurnResult(
                    assistantText = "",
                    toolOutputs = actionResult.toolOutputs,
                    requiresFollowUpCapture = actionResult.requiresFollowUpCapture,
                )
            }
            is DeterministicVoiceActionParseResult.Clarification -> {
                val attempts = pending.attempts + 1
                if (attempts >= MAX_CONFIRMATION_ATTEMPTS) {
                    pendingClarification = null
                    OffscreenRuntimeTurnResult(
                        assistantText = "I didn't hear AM or PM, so I cancelled the alarm.",
                        toolOutputs = emptyList(),
                    )
                } else {
                    pendingClarification = pending.copy(attempts = attempts)
                    OffscreenRuntimeTurnResult(
                        assistantText = completed.message,
                        toolOutputs = emptyList(),
                        requiresFollowUpCapture = true,
                    )
                }
            }
            DeterministicVoiceActionParseResult.NoMatch -> error("Clarification completion returned no match")
        }
    }

    private fun executeOnce(toolCall: ChatToolCall): String {
        // This suppresses replay only inside the live session; a durable action ledger remains roadmap work.
        return VoiceActionExecutionAuthorization.runAuthorized(toolCall.name, toolCall.argumentsJson) {
            runtimeGateway.runTool(toolCall.name, toolCall.argumentsJson).toLegacyString()
        }
    }

    fun startConfirmationWindow() {
        pendingAction = pendingAction?.copy(
            expiresAtElapsedMillis = elapsedRealtimeMillis() + CONFIRMATION_WINDOW_MS,
        )
        pendingClarification = pendingClarification?.copy(
            expiresAtElapsedMillis = elapsedRealtimeMillis() + CONFIRMATION_WINDOW_MS,
        )
    }

    /** Fail closed if the user could not receive the preview that their confirmation would authorize. */
    internal fun cancelPendingInteraction(): Boolean {
        val hadPendingAction = pendingAction != null || pendingClarification != null
        pendingAction = null
        pendingClarification = null
        return hadPendingAction
    }

    private data class ActionHandlingResult(
        val toolOutputs: List<String>,
        val requiresFollowUpCapture: Boolean = false,
    )

    private suspend fun awaitCompletedResponse(preparedStream: PreparedChatStream): ChatResponse {
        return when (
            val terminalEvent = runtimeGateway.streamPreparedChat(preparedStream).first { event ->
                event is ChatStreamEvent.Completed ||
                    event is ChatStreamEvent.Failed ||
                    event is ChatStreamEvent.Cancelled ||
                    event is ChatStreamEvent.Interrupted
            }
        ) {
            is ChatStreamEvent.Completed -> terminalEvent.response
            is ChatStreamEvent.Cancelled -> throw CancellationException("Voice turn cancelled: ${terminalEvent.reason}")
            is ChatStreamEvent.Interrupted -> error(
                "Voice turn interrupted: ${terminalEvent.reason.name.lowercase()}",
            )
            is ChatStreamEvent.Failed -> error(
                "Voice turn failed: ${terminalEvent.errorCode}: ${terminalEvent.message}",
            )
            else -> error("Unreachable terminal stream event: $terminalEvent")
        }
    }

    private companion object {
        private const val VOICE_PROMPT_HINT = "voice"
        private const val CONFIRMATION_WINDOW_MS = 30_000L
        private const val MAX_CONFIRMATION_ATTEMPTS = 2
    }
}

internal fun createOffscreenRuntimeClient(context: Context): OffscreenRuntimeClient {
    val appContext = context.applicationContext
    val foregroundRuntimeServices = resolveAppForegroundRuntimeServices(appContext)
    return OffscreenRuntimeClient(
        runtimeGateway = foregroundRuntimeServices.runtimeGateway,
        loadLastUsedModel = foregroundRuntimeServices.provisioningGateway::loadLastUsedModel,
        deviceStateProvider = {
            AndroidTelemetryDeviceStateProvider(appContext).current()
        },
        isVisibleVoiceSession = VoiceSessionVisibility::isVisible,
    )
}
