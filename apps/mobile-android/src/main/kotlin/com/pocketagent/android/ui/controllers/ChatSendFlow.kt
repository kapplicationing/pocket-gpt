package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatKeepAlivePreference
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.ResolvedPerformancePlan
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.SamplingOverrides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface DeviceStateProvider {
    fun current(): DeviceState

    companion object {
        val DEFAULT = DeviceStateProvider {
            DeviceState(
                batteryPercent = 85,
                thermalLevel = 3,
                ramClassGb = 8,
            )
        }
    }
}

class ChatSendFlow(
    private val runtimeGenerationTimeoutMs: Long,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
    private val preparationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class PreparedSendStream(
        val deviceState: DeviceState,
        val preparedStream: PreparedChatStream,
    )

    private val planner = ChatStreamRequestPlanner(
        runtimeGenerationTimeoutMs = runtimeGenerationTimeoutMs,
        recommendedConfig = { modelIdHint, baseConfig, gpuQualifiedLayers ->
            runtimeTuning.applyRecommendedConfig(
                modelIdHint = modelIdHint,
                baseConfig = baseConfig,
                gpuQualifiedLayers = gpuQualifiedLayers,
            )
        },
    )

    fun isRuntimeReadyForSend(runtime: RuntimeUiState): Boolean {
        return runtime.startupProbeState == StartupProbeState.READY &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    }

    fun resolveRequestTimeoutMs(performanceConfig: PerformanceRuntimeConfig): Long {
        return planner.resolveRequestTimeoutMs(performanceConfig)
    }

    fun resolvePerformancePlan(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): ResolvedPerformancePlan {
        return planner.resolvePerformancePlan(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
        )
    }

    fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): PerformanceRuntimeConfig {
        return planner.resolvePerformanceConfig(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
        )
    }

    suspend fun prepareChatStream(
        sessionId: SessionId,
        requestId: String,
        messages: List<InteractionMessage>,
        promptHint: String,
        previousResponseId: String?,
        runtime: RuntimeUiState,
        completionSettings: CompletionSettings? = null,
        prepare: (ChatStreamCommand) -> PreparedChatStream,
    ): PreparedSendStream = withContext(preparationDispatcher) {
        val deviceState = deviceStateProvider.current()
        val command = ChatStreamCommand(
            sessionId = sessionId,
            requestId = requestId,
            messages = messages,
            promptHint = promptHint,
            deviceState = deviceState,
            performanceProfile = runtime.performanceProfile,
            gpuEnabled = runtime.gpuAccelerationEnabled,
            gpuQualifiedLayers = runtime.gpuMaxQualifiedLayers.coerceAtLeast(0),
            modelIdHint = runtime.activeModelId,
            previousResponseId = previousResponseId,
            keepAlivePreference = runtime.keepAlivePreference.toRuntimePreference(),
            requestTimeoutOverrideMs = runtimeGenerationTimeoutMs.takeIf { it > 0L },
            samplingOverrides = completionSettings?.toSamplingOverrides(),
        )
        PreparedSendStream(
            deviceState = deviceState,
            preparedStream = prepare(command),
        )
    }

    private fun CompletionSettings.toSamplingOverrides(): SamplingOverrides {
        return SamplingOverrides(
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = maxTokens,
            repeatPenalty = repeatPenalty,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            systemPrompt = systemPrompt.ifBlank { null },
            showThinking = showThinking,
        )
    }

    private fun RuntimeKeepAlivePreference.toRuntimePreference(): ChatKeepAlivePreference {
        return when (this) {
            RuntimeKeepAlivePreference.AUTO -> ChatKeepAlivePreference.AUTO
            RuntimeKeepAlivePreference.ALWAYS -> ChatKeepAlivePreference.ALWAYS
            RuntimeKeepAlivePreference.ONE_MINUTE -> ChatKeepAlivePreference.ONE_MINUTE
            RuntimeKeepAlivePreference.FIVE_MINUTES -> ChatKeepAlivePreference.FIVE_MINUTES
            RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> ChatKeepAlivePreference.FIFTEEN_MINUTES
            RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> ChatKeepAlivePreference.UNLOAD_IMMEDIATELY
        }
    }
}
