package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState

enum class RuntimeMemoryRetention {
    RETAIN,
    EPHEMERAL,
}

const val DEFAULT_CHAT_MAX_TOKENS: Int = 64

data class RuntimeRequestContext(
    val deviceState: DeviceState,
    val maxTokens: Int = DEFAULT_CHAT_MAX_TOKENS,
    val keepModelLoaded: Boolean = false,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val requestId: String = defaultRequestId(),
    val previousResponseId: String? = null,
    val performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
    val residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
    val samplingOverrides: SamplingOverrides? = null,
    val memoryRetention: RuntimeMemoryRetention = RuntimeMemoryRetention.RETAIN,
)
