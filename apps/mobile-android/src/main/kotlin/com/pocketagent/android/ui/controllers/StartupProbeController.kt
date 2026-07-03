package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

open class StartupProbeController {
    open suspend fun runStartupChecks(
        runtimeGateway: ChatRuntimeService,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        return try {
            withTimeout(timeoutMs) {
                runInterruptible(ioDispatcher) { runtimeGateway.runStartupChecks() }
            }
        } catch (_: TimeoutCancellationException) {
            val timeoutSeconds = (timeoutMs / 1000L).coerceAtLeast(1L)
            listOf("Startup checks timed out after ${timeoutSeconds}s.")
        } catch (error: RuntimeException) {
            if (error is CancellationException) {
                throw error
            }
            val detail = error.startupFailureDetail()
            listOf("Startup checks failed unexpectedly: $detail")
        }
    }
}

private fun Throwable.startupFailureDetail(): String {
    val messages = generateSequence(this) { error -> error.cause }
        .mapNotNull { error -> error.message?.trim()?.takeUnless { it.isEmpty() } }
        .distinct()
        .toList()
    if (messages.isNotEmpty()) {
        return messages.joinToString(": ")
    }
    return generateSequence(this) { error -> error.cause }
        .mapNotNull { error -> error::class.simpleName?.takeUnless { it.isEmpty() } }
        .firstOrNull()
        ?: "unknown error"
}
