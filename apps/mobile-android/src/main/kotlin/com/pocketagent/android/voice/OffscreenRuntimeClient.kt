package com.pocketagent.android.voice

import android.content.Context
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.RuntimeSessionCreationResult
import com.pocketagent.android.runtime.RuntimeSessionUnavailableException
import com.pocketagent.android.runtime.resolveAppForegroundRuntimeServices
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.core.ChatResponse
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatKeepAlivePreference
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.SamplingOverrides
import com.pocketagent.runtime.toLegacyString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class OffscreenRuntimeTurnResult(
    val assistantText: String,
    val toolOutputs: List<String>,
)

internal class OffscreenRuntimeClient(
    private val runtimeGateway: ChatRuntimeService,
    private val loadLastUsedModel: suspend () -> RuntimeModelLifecycleCommandResult,
    private val deviceStateProvider: () -> DeviceState,
) {
    suspend fun warmLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return loadLastUsedModel().also {
            runtimeGateway.touchKeepAlive()
        }
    }

    suspend fun runVoiceTurn(
        transcript: String,
        systemPrompt: String,
    ): OffscreenRuntimeTurnResult {
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
                ),
            )
            val response = awaitCompletedResponse(preparedStream)
            val toolOutputs = response.toolCalls.map { toolCall ->
                runtimeGateway.runTool(toolCall.name, toolCall.argumentsJson).toLegacyString()
            }
            runtimeGateway.touchKeepAlive()
            OffscreenRuntimeTurnResult(
                assistantText = response.text,
                toolOutputs = toolOutputs,
            )
        } finally {
            runtimeGateway.deleteSession(sessionId)
        }
    }

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
    )
}
