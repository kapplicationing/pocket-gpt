package com.pocketagent.runtime

import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState

enum class ChatKeepAlivePreference {
    AUTO,
    ALWAYS,
    ONE_MINUTE,
    FIVE_MINUTES,
    FIFTEEN_MINUTES,
    UNLOAD_IMMEDIATELY,
}

data class ChatStreamCommand(
    val sessionId: SessionId,
    val requestId: String,
    val messages: List<InteractionMessage>,
    val promptHint: String,
    val deviceState: DeviceState,
    val performanceProfile: RuntimePerformanceProfile,
    val gpuEnabled: Boolean,
    val gpuQualifiedLayers: Int,
    val modelIdHint: String? = null,
    val previousResponseId: String? = null,
    val keepAlivePreference: ChatKeepAlivePreference = ChatKeepAlivePreference.AUTO,
    val requestTimeoutOverrideMs: Long? = null,
    val samplingOverrides: SamplingOverrides? = null,
    val memoryRetention: RuntimeMemoryRetention = RuntimeMemoryRetention.RETAIN,
)

data class ChatStreamPlan(
    val requestId: String,
    val requestTimeoutMs: Long,
    val baseConfig: PerformanceRuntimeConfig,
    val effectiveConfig: PerformanceRuntimeConfig,
)

data class ResolvedPerformancePlan(
    val baseConfig: PerformanceRuntimeConfig,
    val effectiveConfig: PerformanceRuntimeConfig,
)

data class PreparedChatStream(
    val plan: ChatStreamPlan,
    val runtimeRequest: StreamChatRequestV2,
)

class ChatStreamRequestPlanner(
    private val runtimeGenerationTimeoutMs: Long,
    private val availableCpuThreadsProvider: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val recommendedConfig: (String?, PerformanceRuntimeConfig, Int) -> PerformanceRuntimeConfig =
        { _, baseConfig, _ -> baseConfig },
) {
    fun prepare(command: ChatStreamCommand): PreparedChatStream {
        val performancePlan = resolvePerformancePlan(
            profile = command.performanceProfile,
            gpuEnabled = command.gpuEnabled,
            gpuLayers = command.gpuQualifiedLayers,
            modelIdHint = command.modelIdHint,
        )
        return prepare(
            command = command,
            performancePlan = performancePlan,
            requestTimeoutOverrideMs = resolveRequestTimeoutMs(
                performanceConfig = performancePlan.effectiveConfig,
                overrideTimeoutMs = command.requestTimeoutOverrideMs,
            ),
        )
    }

    fun prepare(
        command: ChatStreamCommand,
        performanceConfig: PerformanceRuntimeConfig,
        requestTimeoutMs: Long,
    ): PreparedChatStream {
        val performancePlan = ResolvedPerformancePlan(
            baseConfig = performanceConfig,
            effectiveConfig = performanceConfig,
        )
        return prepare(
            command = command,
            performancePlan = performancePlan,
            requestTimeoutOverrideMs = requestTimeoutMs,
        )
    }

    private fun prepare(
        command: ChatStreamCommand,
        performancePlan: ResolvedPerformancePlan,
        requestTimeoutOverrideMs: Long,
    ): PreparedChatStream {
        val effectiveConfig = performancePlan.effectiveConfig
            .withSamplingOverrides(command.samplingOverrides)
        val requestTimeoutMs = resolveRequestTimeoutMs(
            performanceConfig = effectiveConfig,
            overrideTimeoutMs = requestTimeoutOverrideMs,
        )
        val messages = buildList {
            val userSystemPrompt = command.samplingOverrides?.systemPrompt
            if (!userSystemPrompt.isNullOrBlank()) {
                add(
                    InteractionMessage(
                        id = "system-user-prompt",
                        role = InteractionRole.SYSTEM,
                        parts = listOf(InteractionContentPart.Text(userSystemPrompt)),
                    ),
                )
            }
            addAll(command.messages)
        }
        val runtimeRequest = StreamChatRequestV2(
            sessionId = command.sessionId,
            requestId = command.requestId,
            messages = messages,
            taskType = resolveTaskType(command.promptHint),
            maxTokens = resolveMaxTokens(command.promptHint, effectiveConfig),
            deviceState = command.deviceState,
            requestTimeoutMs = requestTimeoutMs,
            previousResponseId = command.previousResponseId,
            performanceConfig = effectiveConfig,
            residencyPolicy = resolveResidencyPolicy(command.keepAlivePreference),
            samplingOverrides = command.samplingOverrides,
            memoryRetention = command.memoryRetention,
        )
        return PreparedChatStream(
            plan = ChatStreamPlan(
                requestId = command.requestId,
                requestTimeoutMs = requestTimeoutMs,
                baseConfig = performancePlan.baseConfig,
                effectiveConfig = effectiveConfig,
            ),
            runtimeRequest = runtimeRequest,
        )
    }

    fun resolveRequestTimeoutMs(
        performanceConfig: PerformanceRuntimeConfig,
        overrideTimeoutMs: Long? = null,
    ): Long {
        if ((overrideTimeoutMs ?: runtimeGenerationTimeoutMs) > 0L) {
            return overrideTimeoutMs ?: runtimeGenerationTimeoutMs
        }
        return performanceConfig.requestTimeoutMs
    }

    fun resolvePerformancePlan(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): ResolvedPerformancePlan {
        val cpuThreads = availableCpuThreadsProvider().coerceAtLeast(1)
        val safeBaseConfig = enforceGpuSafeBatch(
            PerformanceRuntimeConfig.forProfile(
                profile = profile,
                availableCpuThreads = cpuThreads,
                gpuEnabled = gpuEnabled,
                gpuLayers = gpuLayers.coerceAtLeast(0),
            ),
        )
        val tunedConfig = enforceGpuSafeBatch(
            recommendedConfig(
                modelIdHint,
                safeBaseConfig,
                gpuLayers.coerceAtLeast(0),
            ),
        )
        return ResolvedPerformancePlan(
            baseConfig = safeBaseConfig,
            effectiveConfig = tunedConfig,
        )
    }

    fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): PerformanceRuntimeConfig {
        return resolvePerformancePlan(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
        ).effectiveConfig
    }

    private fun enforceGpuSafeBatch(config: PerformanceRuntimeConfig): PerformanceRuntimeConfig {
        if (!config.gpuEnabled || config.gpuLayers <= 0) {
            return config
        }
        return config.copy(
            nBatch = minOf(config.nBatch, GPU_SAFE_BATCH_CAP),
            nUbatch = minOf(config.nUbatch, GPU_SAFE_BATCH_CAP),
        )
    }

    private fun resolveResidencyPolicy(preference: ChatKeepAlivePreference): ModelResidencyPolicy {
        return when (preference) {
            ChatKeepAlivePreference.AUTO -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS,
                warmupOnStartup = true,
                adaptiveIdleTtl = true,
            )
            ChatKeepAlivePreference.ALWAYS -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = ALWAYS_KEEP_ALIVE_TTL_MS,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            ChatKeepAlivePreference.ONE_MINUTE -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            ChatKeepAlivePreference.FIVE_MINUTES -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 5 * 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            ChatKeepAlivePreference.FIFTEEN_MINUTES -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 15 * 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            ChatKeepAlivePreference.UNLOAD_IMMEDIATELY -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = false,
                idleUnloadTtlMs = 1L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
        }
    }

    private fun resolveTaskType(prompt: String): String {
        return if (prompt.length >= LONG_PROMPT_LENGTH) "long_text" else "short_text"
    }

    private fun resolveMaxTokens(prompt: String, performanceConfig: PerformanceRuntimeConfig): Int {
        val maxAllowed = (performanceConfig.nCtx / 2).coerceAtLeast(MIN_MAX_TOKENS)
        return performanceConfig.maxTokensDefault.coerceIn(MIN_MAX_TOKENS, maxAllowed)
    }

    private companion object {
        private const val LONG_PROMPT_LENGTH = 160
        private const val MIN_MAX_TOKENS = 16
        private const val ALWAYS_KEEP_ALIVE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
