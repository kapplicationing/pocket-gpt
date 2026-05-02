package com.pocketagent.android.ui.controllers

import android.util.Log
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
        val streamReducerLock = Any()
        var streamState = StreamReducerState.initial(requestId = request.requestId)

        fun reduce(block: (StreamReducerState) -> StreamReducerState): Pair<StreamTerminalState?, StreamReducerState> {
            synchronized(streamReducerLock) {
                val previous = streamState.terminal
                streamState = block(streamState)
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
                    elapsed >= noFirstTokenStallMs -> "Still working on this device. You can keep waiting, or tap Cancel to stop."
                    elapsed >= noFirstTokenWarnMs -> "Loading model and prefill can take longer on older phones. You can keep waiting or cancel."
                    else -> null
                }
                onElapsed(elapsed, slowState)
                delay(sendElapsedUpdateIntervalMs)
            }
        }

        var streamCollector: Job? = null
        var firstTokenLogged = false
        val prefillTimeoutWatchdog = launch {
            delay(requestTimeoutMs + terminalWatchdogGraceMs)
            if (streamFirstTokenMs() != null || hasTerminal()) {
                return@launch
            }
            val (previousTerminal, nextState) = reduce { state ->
                streamReducer.onWatchdogTimeout(state)
            }
            if (previousTerminal != null || nextState.terminal == null || nextState.firstTokenMs != null) {
                return@launch
            }
            elapsedTicker.cancel()
            runtimeService.cancelGenerationByRequest(request.requestId)
            val terminal = nextState.terminal ?: return@launch
            onBeforeTerminal()
            onTerminal(terminal)
            streamCollector?.cancel()
        }

        streamCollector = launch {
            runCatching {
                runtimeService.streamPreparedChat(preparedStream).collect { event ->
                    if (hasTerminal()) {
                        this.cancel()
                        return@collect
                    }
                    val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                    val (previousTerminal, nextState) = reduce { state ->
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
                        prefillTimeoutWatchdog.cancel()
                    }
                    onEvent(event, nextState)
                    nextState.terminal?.let { terminal ->
                        prefillTimeoutWatchdog.cancel()
                        elapsedTicker.cancel()
                        onBeforeTerminal()
                        onTerminal(terminal)
                        this.cancel()
                    }
                }
            }.onFailure { error ->
                val (previousTerminal, nextState) = reduce { state ->
                    streamReducer.onFailure(state = state, error = error)
                }
                if (previousTerminal != null || nextState.terminal == null) return@onFailure
                prefillTimeoutWatchdog.cancel()
                elapsedTicker.cancel()
                runtimeService.cancelGenerationByRequest(request.requestId)
                val terminal = nextState.terminal ?: return@onFailure
                onBeforeTerminal()
                onTerminal(terminal)
            }
        }
        streamCollector.join()
        prefillTimeoutWatchdog.cancel()
    }

}
