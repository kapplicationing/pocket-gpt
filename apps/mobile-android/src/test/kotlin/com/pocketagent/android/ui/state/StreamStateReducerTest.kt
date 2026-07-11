package com.pocketagent.android.ui.state

import com.pocketagent.core.ChatResponse
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamInterruptionReason
import com.pocketagent.runtime.RuntimeRecoveryDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamStateReducerTest {
    private val reducer = StreamStateReducer(requestTimeoutMs = 30_000L)

    @Test
    fun `reducer accepts only first terminal event`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val afterCompleted = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Completed(
                requestId = "req-1",
                response = ChatResponse(
                    sessionId = com.pocketagent.core.SessionId("s1"),
                    modelId = "qwen",
                    text = "hello",
                    firstTokenLatencyMs = 10,
                    totalLatencyMs = 20,
                    requestId = "req-1",
                    finishReason = "completed",
                ),
                finishReason = "completed",
            ),
            elapsedMs = 20L,
        )
        val afterFailed = reducer.onEvent(
            state = afterCompleted,
            event = ChatStreamEvent.Failed(
                requestId = "req-1",
                errorCode = "runtime_error",
                message = "late failure",
            ),
            elapsedMs = 25L,
        )

        assertNotNull(afterCompleted.terminal)
        assertEquals("completed", afterCompleted.terminal?.finishReason)
        assertEquals(afterCompleted, afterFailed)
    }

    @Test
    fun `reducer maps timeout cancellation deterministically`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val terminal = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Cancelled(
                requestId = "req-1",
                reason = "timeout",
            ),
            elapsedMs = 100L,
        ).terminal

        assertNotNull(terminal)
        assertEquals("timeout", terminal.finishReason)
        assertEquals("UI-RUNTIME-001", terminal.uiError?.code)
    }

    @Test
    fun `reducer keeps user cancellation non-error`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val terminal = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Cancelled(
                requestId = "req-1",
                reason = "cancelled",
            ),
            elapsedMs = 80L,
        ).terminal

        assertNotNull(terminal)
        assertEquals("cancelled", terminal.finishReason)
        assertNull(terminal.uiError)
    }

    @Test
    fun `reducer preserves partial text and marks interrupted stream as retryable failure`() {
        val reasons = mapOf(
            ChatStreamInterruptionReason.CLOSED_WITHOUT_TERMINAL to "stream_closed_without_terminal",
            ChatStreamInterruptionReason.STALLED_WITHOUT_TERMINAL to "stream_stalled_without_terminal",
        )

        reasons.forEach { (reason, errorCode) ->
            val event = ChatStreamEvent.Interrupted(
                requestId = "req-${reason.name}",
                reason = reason,
                partialText = "useful partial response",
                firstTokenMs = 12L,
                completionMs = 55L,
            )
            val state = reducer.onEvent(
                state = StreamReducerState.initial(event.requestId),
                event = event,
                elapsedMs = 55L,
            )
            val terminal = state.terminal

            assertNotNull(terminal)
            assertEquals("interrupted:${reason.name.lowercase()}", terminal.finishReason)
            assertEquals(false, terminal.terminalEventSeen)
            assertEquals("useful partial response", reducer.snapshotText())
            assertEquals("useful partial response", terminal.responseText)
            assertEquals(errorCode, terminal.errorCode)
            assertEquals(RecoveryAction.RETRY_LOAD, terminal.uiError?.recoveryAction)
            assertEquals(12L, terminal.firstTokenMs)
            assertEquals(55L, terminal.completionMs)
        }
    }

    @Test
    fun `reducer tracks phase updates without creating terminal state`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val afterPhase = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Phase(
                requestId = "req-1",
                phase = com.pocketagent.runtime.ChatStreamPhase.PROMPT_PROCESSING,
                detail = "prefill",
            ),
            elapsedMs = 10L,
        )

        assertEquals("prompt_processing", afterPhase.lastPhase)
        assertEquals(null, afterPhase.terminal)
    }

    @Test
    fun `thinking event does not mark first token before visible output`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val afterThinking = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Thinking(
                requestId = "req-1",
                active = true,
            ),
            elapsedMs = 150L,
        )

        assertEquals(true, afterThinking.isThinking)
        assertNull(afterThinking.firstTokenMs)
        assertEquals(null, afterThinking.terminal)
    }

    @Test
    fun `reducer accumulates delta-only text and snapshots it at the boundary`() {
        var state = StreamReducerState.initial(requestId = "req-linear")

        state = reducer.onEvent(
            state = state,
            event = ChatStreamEvent.Delta(
                requestId = state.requestId,
                delta = ChatStreamDelta.TextDelta("   "),
            ),
            elapsedMs = 5L,
        )
        assertFalse(reducer.hasVisibleText())

        repeat(4_000) { index ->
            state = reducer.onEvent(
                state = state,
                event = ChatStreamEvent.Delta(
                    requestId = state.requestId,
                    delta = ChatStreamDelta.TextDelta("x"),
                ),
                elapsedMs = 10L + index,
            )
        }

        assertTrue(reducer.hasVisibleText())
        assertEquals(4_003, reducer.snapshotText().length)
        assertEquals(5L, state.firstTokenMs)

        val cancelled = reducer.onEvent(
            state = state,
            event = ChatStreamEvent.Cancelled(
                requestId = state.requestId,
                reason = "cancelled",
            ),
            elapsedMs = 5_000L,
        )
        assertEquals(reducer.snapshotText(), cancelled.terminal?.responseText)
    }

    @Test
    fun `failed event preserves stable code and typed recovery disposition`() {
        data class Case(
            val errorCode: String,
            val disposition: RuntimeRecoveryDisposition,
            val recoveryAction: RecoveryAction,
        )

        val cases = listOf(
            Case(
                "runtime_incompatible_model_format",
                RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL,
                RecoveryAction.CHANGE_MODEL,
            ),
            Case(
                "model_file_unavailable",
                RuntimeRecoveryDisposition.REPROVISION_MODEL,
                RecoveryAction.REDOWNLOAD_MODEL,
            ),
            Case("out_of_memory", RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL, RecoveryAction.CHANGE_MODEL),
            Case("backend_init_failed", RuntimeRecoveryDisposition.RESTART_RUNTIME, RecoveryAction.RESTART_APP),
            Case("busy_generation", RuntimeRecoveryDisposition.RETRY_REQUEST, RecoveryAction.RETRY_LOAD),
            Case(
                "template_unavailable",
                RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL,
                RecoveryAction.CHANGE_MODEL,
            ),
            Case("unknown_code", RuntimeRecoveryDisposition.RETRY_REQUEST, RecoveryAction.RETRY_LOAD),
        )

        cases.forEach { case ->
            val event = ChatStreamEvent.Failed(
                requestId = "req-${case.errorCode}",
                errorCode = case.errorCode,
                message = "Opaque runtime detail that must not determine recovery.",
            )
            val terminal = reducer.onEvent(
                state = StreamReducerState.initial(event.requestId),
                event = event,
                elapsedMs = 25L,
            ).terminal

            assertNotNull(terminal)
            assertEquals(case.disposition, event.recoveryDisposition, case.errorCode)
            assertEquals(case.errorCode, terminal.errorCode, case.errorCode)
            assertEquals(case.errorCode, terminal.uiError?.sourceCode, case.errorCode)
            assertEquals(case.recoveryAction, terminal.uiError?.recoveryAction, case.errorCode)
        }
    }
}
