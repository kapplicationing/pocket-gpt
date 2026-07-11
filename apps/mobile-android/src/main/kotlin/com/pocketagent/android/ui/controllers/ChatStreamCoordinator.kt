package com.pocketagent.android.ui.controllers

import android.util.Log
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamInterruptionReason
import com.pocketagent.runtime.PreparedChatStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatStreamCoordinator(
    private val terminalWatchdogGraceMs: Long = 10_000L,
    private val sendElapsedUpdateIntervalMs: Long = 1_000L,
    private val noFirstTokenWarnMs: Long = 150_000L,
    private val noFirstTokenStallMs: Long = 600_000L,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun collectStream(
        runtimeService: ChatRuntimeService,
        preparedStream: PreparedChatStream,
        streamReducer: StreamStateReducer,
        sendStartedAtMs: Long,
        onEvent: (ChatStreamEvent, StreamReducerState) -> Unit,
        onElapsed: (Long, String?) -> Unit,
        onBeforeTerminal: () -> Unit,
        onTerminal: (StreamTerminalState) -> Unit,
    ) = coroutineScope {
        val request = preparedStream.runtimeRequest
        val requestTimeoutMs = preparedStream.plan.requestTimeoutMs
        val terminalWatchdogPollMs = terminalWatchdogGraceMs
            .coerceIn(TERMINAL_WATCHDOG_POLL_MIN_MS, TERMINAL_WATCHDOG_POLL_MAX_MS)
        val streamReducerLock = Any()
        var streamState = StreamReducerState.initial(requestId = request.requestId)
        var lastEventElapsedMs = 0L

        fun reduce(
            block: (StreamReducerState) -> StreamReducerState,
        ): Pair<StreamTerminalState?, StreamReducerState> {
            synchronized(streamReducerLock) {
                val previous = streamState.terminal
                streamState = block(streamState)
                return previous to streamState
            }
        }

        fun reduceOnEvent(
            elapsedMs: Long,
            block: (StreamReducerState) -> StreamReducerState,
        ): Pair<StreamTerminalState?, StreamReducerState> {
            synchronized(streamReducerLock) {
                val previous = streamState.terminal
                streamState = block(streamState)
                lastEventElapsedMs = elapsedMs
                return previous to streamState
            }
        }

        fun hasTerminal(): Boolean = synchronized(streamReducerLock) { streamState.terminal != null }

        fun streamFirstTokenMs(): Long? = synchronized(streamReducerLock) { streamState.firstTokenMs }

        val elapsedTicker = launch {
            while (!hasTerminal()) {
                val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                val slowState = when {
                    streamFirstTokenMs() != null -> null
                    elapsed >= noFirstTokenStallMs ->
                        "Still working on this device. You can keep waiting, or tap Cancel to stop."
                    elapsed >= noFirstTokenWarnMs ->
                        "Loading model and prefill can take longer on older phones. " +
                            "You can keep waiting or cancel."
                    else -> null
                }
                onElapsed(elapsed, slowState)
                delay(sendElapsedUpdateIntervalMs)
            }
        }

        var streamCollector: Job? = null
        suspend fun dispatchTerminal(
            previousTerminal: StreamTerminalState?,
            nextState: StreamReducerState,
            cancelRequest: Boolean = false,
            cancelCollector: Boolean = false,
        ) {
            if (previousTerminal != null) {
                return
            }
            val terminal = nextState.terminal ?: return
            elapsedTicker.cancel()
            if (cancelRequest) {
                runtimeService.cancelGenerationByRequest(request.requestId)
            }
            onBeforeTerminal()
            onTerminal(terminal)
            if (cancelCollector) {
                streamCollector?.cancel()
            }
        }
        var firstTokenLogged = false
        val terminalTimeoutWatchdog = launch {
            var postTokenIdleObservationCount = 0
            delay(requestTimeoutMs + terminalWatchdogGraceMs)
            while (!hasTerminal()) {
                var interruptedEvent: ChatStreamEvent.Interrupted? = null
                val (previousTerminal, nextState) = reduce { state ->
                    if (state.terminal != null) {
                        state
                    } else {
                        val watchdogElapsedMs =
                            (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                        val activeGenerationCount = runCatching {
                            runtimeService.activeGenerationCount()
                        }.getOrDefault(0)
                        val postTokenSilenceMs = (watchdogElapsedMs - lastEventElapsedMs).coerceAtLeast(0L)
                        when {
                            state.firstTokenMs == null -> {
                                postTokenIdleObservationCount = 0
                                streamReducer.onWatchdogTimeout(state)
                            }

                            streamReducer.hasVisibleText() &&
                                (activeGenerationCount <= 0 || postTokenSilenceMs >= terminalWatchdogGraceMs) -> {
                                postTokenIdleObservationCount += 1
                                if (postTokenIdleObservationCount < POST_TOKEN_IDLE_OBSERVATIONS_REQUIRED) {
                                    state
                                } else {
                                    runCatching {
                                        Log.w(
                                            "PocketGPTStream",
                                            "Stream exceeded timeout without terminal; " +
                                                "preserving interrupted partial text. " +
                                                "request_id=${request.requestId}|" +
                                                "active_generations=$activeGenerationCount|" +
                                                "post_token_silence_ms=$postTokenSilenceMs",
                                        )
                                    }
                                    val event = ChatStreamEvent.Interrupted(
                                        requestId = request.requestId,
                                        reason = ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL,
                                        partialText = streamReducer.snapshotText(),
                                        firstTokenMs = state.firstTokenMs,
                                        completionMs = watchdogElapsedMs,
                                    )
                                    interruptedEvent = event
                                    streamReducer.onEvent(
                                        state = state,
                                        event = event,
                                        elapsedMs = watchdogElapsedMs,
                                    )
                                }
                            }

                            activeGenerationCount <= 0 -> {
                                postTokenIdleObservationCount = 0
                                streamReducer.onWatchdogTimeout(state)
                            }

                            else -> {
                                postTokenIdleObservationCount = 0
                                state
                            }
                        }
                    }
                }
                if (previousTerminal == null && nextState.terminal != null) {
                    interruptedEvent?.let { event -> onEvent(event, nextState) }
                    dispatchTerminal(
                        previousTerminal = previousTerminal,
                        nextState = nextState,
                        cancelRequest = true,
                        cancelCollector = true,
                    )
                    return@launch
                }
                delay(terminalWatchdogPollMs)
            }
        }

        streamCollector = launch {
            runCatching {
                runtimeService.streamPreparedChat(preparedStream).collect { event ->
                    if (hasTerminal()) {
                        this.cancel()
                        return@collect
                    }
                    val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                    val (previousTerminal, nextState) = reduceOnEvent(elapsedMs = elapsed) { state ->
                        streamReducer.onEvent(state = state, event = event, elapsedMs = elapsed)
                    }
                    if (previousTerminal != null) return@collect
                    if (nextState.firstTokenMs != null) {
                        if (!firstTokenLogged) {
                            firstTokenLogged = true
                            runCatching {
                                Log.i(
                                    "PocketGPTPerf",
                                    "PERF_OP|name=chat.first_token|duration_ms=${nextState.firstTokenMs}|" +
                                        "request_id=${request.requestId}",
                                )
                            }
                        }
                    }
                    onEvent(event, nextState)
                    nextState.terminal?.let {
                        terminalTimeoutWatchdog.cancel()
                        dispatchTerminal(
                            previousTerminal = previousTerminal,
                            nextState = nextState,
                        )
                        this.cancel()
                    }
                }
            }.onSuccess {
                if (hasTerminal()) {
                    return@onSuccess
                }
                val completionMs = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                var interruptedEvent: ChatStreamEvent.Interrupted? = null
                val (previousTerminal, nextState) = reduce { state ->
                    if (state.terminal != null) {
                        state
                    } else {
                        val accumulatedText = streamReducer.snapshotText()
                        if (accumulatedText.isNotBlank()) {
                            runCatching {
                                Log.w(
                                    "PocketGPTStream",
                                    "Stream closed without terminal event; preserving interrupted partial text. " +
                                        "request_id=${request.requestId}",
                                )
                            }
                            val event = ChatStreamEvent.Interrupted(
                                requestId = request.requestId,
                                reason = ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL,
                                partialText = accumulatedText,
                                completionMs = completionMs,
                                firstTokenMs = state.firstTokenMs,
                            )
                            interruptedEvent = event
                            streamReducer.onEvent(
                                state = state,
                                event = event,
                                elapsedMs = completionMs,
                            )
                        } else {
                            runCatching {
                                Log.w(
                                    "PocketGPTStream",
                                    "Stream closed without terminal event or visible text. " +
                                        "request_id=${request.requestId}",
                                )
                            }
                            state.copy(
                                isThinking = false,
                                terminal = StreamTerminalState(
                                    requestId = request.requestId,
                                    finishReason = "runtime_error",
                                    terminalEventSeen = false,
                                    uiError = UiErrorMapper.runtimeFailure("Stream closed before a terminal event."),
                                    completionMs = completionMs,
                                    firstTokenMs = state.firstTokenMs,
                                ),
                            )
                        }
                    }
                }
                terminalTimeoutWatchdog.cancel()
                interruptedEvent?.let { event -> onEvent(event, nextState) }
                dispatchTerminal(
                    previousTerminal = previousTerminal,
                    nextState = nextState,
                    cancelRequest = interruptedEvent != null,
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                val (previousTerminal, nextState) = reduce { state ->
                    streamReducer.onFailure(state = state, error = error)
                }
                if (previousTerminal != null || nextState.terminal == null) return@onFailure
                terminalTimeoutWatchdog.cancel()
                dispatchTerminal(
                    previousTerminal = previousTerminal,
                    nextState = nextState,
                    cancelRequest = true,
                )
            }
        }
        streamCollector.join()
        terminalTimeoutWatchdog.cancel()
    }

}

private const val TERMINAL_WATCHDOG_POLL_MIN_MS = 100L
private const val TERMINAL_WATCHDOG_POLL_MAX_MS = 1_000L
private const val POST_TOKEN_IDLE_OBSERVATIONS_REQUIRED = 2
