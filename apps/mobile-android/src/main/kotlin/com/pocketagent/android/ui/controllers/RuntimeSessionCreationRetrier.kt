package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.RuntimeSessionCreationResult
import kotlinx.coroutines.delay

fun interface RuntimeSessionRetryDelay {
    suspend fun await(delayMs: Long)
}

class RuntimeSessionCreationRetrier(
    private val runtimeGateway: ChatRuntimeService,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val retryDelay: RuntimeSessionRetryDelay = RuntimeSessionRetryDelay { delayMs ->
        delay(delayMs)
    },
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(retryDelayMs >= 0L) { "retryDelayMs must not be negative" }
    }

    suspend fun createSession(): RuntimeSessionCreationResult {
        repeat(maxAttempts) { attempt ->
            when (val result = runtimeGateway.createRuntimeSession()) {
                is RuntimeSessionCreationResult.Created -> return result
                is RuntimeSessionCreationResult.Unavailable -> {
                    val shouldRetry = result.reason.isTransient && attempt < maxAttempts - 1
                    if (!shouldRetry) {
                        return result
                    }
                    retryDelay.await(retryDelayMs)
                }
            }
        }
        error("Runtime session creation retry loop exhausted without a result.")
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 21
        const val DEFAULT_RETRY_DELAY_MS = 100L
    }
}
