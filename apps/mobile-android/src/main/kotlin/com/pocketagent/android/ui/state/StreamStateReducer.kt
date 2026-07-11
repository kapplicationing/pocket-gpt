package com.pocketagent.android.ui.state

import androidx.compose.runtime.Immutable
import com.pocketagent.core.ChatToolCall
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamInterruptionReason
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

@Immutable
data class StreamReducerState(
    val requestId: String,
    val isThinking: Boolean = false,
    val firstTokenMs: Long? = null,
    val lastPhase: String? = null,
    val terminal: StreamTerminalState? = null,
) {
    companion object {
        fun initial(requestId: String): StreamReducerState = StreamReducerState(requestId = requestId)
    }
}

@Immutable
data class StreamTerminalState(
    val requestId: String,
    val finishReason: String,
    val terminalEventSeen: Boolean,
    val uiError: UiError? = null,
    val responseText: String? = null,
    val responseModelId: String? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val completionMs: Long? = null,
    val firstTokenMs: Long? = null,
    val errorCode: String? = null,
    val runtimeStats: RuntimeExecutionStats? = null,
)

class StreamStateReducer(
    private val requestTimeoutMs: Long,
) {
    private val textAccumulator = StreamTextAccumulator()

    fun snapshotText(): String = textAccumulator.snapshot()

    fun hasVisibleText(): Boolean = textAccumulator.hasVisibleText()

    @Suppress("CyclomaticComplexMethod")
    fun onEvent(
        state: StreamReducerState,
        event: ChatStreamEvent,
        elapsedMs: Long,
    ): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        return when (event) {
            is ChatStreamEvent.Started -> state
            is ChatStreamEvent.Phase -> state.copy(lastPhase = event.phase.name.lowercase())
            is ChatStreamEvent.Thinking -> {
                state.copy(
                    isThinking = event.active,
                )
            }
            is ChatStreamEvent.Delta -> reduceDelta(state, event, elapsedMs)
            is ChatStreamEvent.Completed -> {
                textAccumulator.replace(event.response.text)
                state.copy(
                    isThinking = false,
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = event.finishReason,
                        terminalEventSeen = event.terminalEventSeen,
                        responseText = event.response.text,
                        responseModelId = event.response.modelId,
                        reasoningContent = event.response.reasoningContent,
                        toolCalls = event.response.toolCalls,
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                        runtimeStats = event.response.runtimeStats,
                    ),
                )
            }
            is ChatStreamEvent.Interrupted -> {
                val errorCode = when (event.reason) {
                    ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL -> "stream_closed_without_terminal"
                    ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL -> "stream_stalled_without_terminal"
                }
                val detail = when (event.reason) {
                    ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL ->
                        "Response stream closed before the runtime sent a terminal event."
                    ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL ->
                        "Response stream stalled before the runtime sent a terminal event."
                }
                textAccumulator.replace(event.partialText)
                state.copy(
                    isThinking = false,
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = "interrupted:${event.reason.name.lowercase()}",
                        terminalEventSeen = false,
                        uiError = UiErrorMapper.runtimeFailure(
                            errorCode = errorCode,
                            detail = detail,
                        ),
                        responseText = event.partialText,
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                        errorCode = errorCode,
                    ),
                )
            }
            is ChatStreamEvent.Cancelled -> {
                val timedOut = event.reason.equals("timeout", ignoreCase = true)
                val uiError = if (timedOut) {
                    UiErrorMapper.runtimeTimeout(requestTimeoutMs)
                } else {
                    null
                }
                state.copy(
                    isThinking = false,
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = event.reason,
                        terminalEventSeen = event.terminalEventSeen,
                        uiError = uiError,
                        responseText = snapshotText(),
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                    ),
                )
            }
            is ChatStreamEvent.Failed -> {
                state.copy(
                    isThinking = false,
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = "failed:${event.errorCode}",
                        terminalEventSeen = event.terminalEventSeen,
                        uiError = UiErrorMapper.runtimeFailure(
                            errorCode = event.errorCode,
                            detail = event.message,
                            recoveryDisposition = event.recoveryDisposition,
                        ),
                        responseText = snapshotText(),
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                        errorCode = event.errorCode,
                    ),
                )
            }
        }
    }

    private fun reduceDelta(
        state: StreamReducerState,
        event: ChatStreamEvent.Delta,
        elapsedMs: Long,
    ): StreamReducerState {
        return when (val delta = event.delta) {
            is ChatStreamDelta.TextDelta -> {
                textAccumulator.append(delta.text)
                val firstToken = if (state.firstTokenMs == null && delta.text.isNotEmpty()) {
                    elapsedMs.coerceAtLeast(0L)
                } else {
                    state.firstTokenMs
                }
                state.copy(
                    isThinking = state.isThinking,
                    firstTokenMs = firstToken,
                )
            }
        }
    }

    fun onFailure(
        state: StreamReducerState,
        error: Throwable,
    ): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        val timedOut = error is TimeoutCancellationException || error is RuntimeGenerationTimeoutException
        val uiError = if (timedOut) {
            UiErrorMapper.runtimeTimeout(requestTimeoutMs)
        } else {
            UiErrorMapper.runtimeFailure(error.message)
        }
        val reason = if (timedOut) "timeout" else "runtime_error"
        return state.copy(
            isThinking = false,
            terminal = StreamTerminalState(
                requestId = state.requestId,
                finishReason = reason,
                terminalEventSeen = true,
                uiError = uiError,
                responseText = snapshotText(),
            ),
        )
    }

    fun onWatchdogTimeout(state: StreamReducerState): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        return state.copy(
            isThinking = false,
            terminal = StreamTerminalState(
                requestId = state.requestId,
                finishReason = "timeout",
                terminalEventSeen = true,
                uiError = UiErrorMapper.runtimeTimeout(requestTimeoutMs),
                responseText = snapshotText(),
            ),
        )
    }
}

private class StreamTextAccumulator {
    private val lock = Any()
    private val text = StringBuilder()
    private var containsVisibleText = false

    fun append(delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            text.append(delta)
            if (!containsVisibleText && delta.any { character -> !character.isWhitespace() }) {
                containsVisibleText = true
            }
        }
    }

    fun replace(value: String) {
        synchronized(lock) {
            text.setLength(0)
            text.append(value)
            containsVisibleText = value.any { character -> !character.isWhitespace() }
        }
    }

    fun snapshot(): String = synchronized(lock) { text.toString() }

    fun hasVisibleText(): Boolean = synchronized(lock) { containsVisibleText }
}
