package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlamaRuntimeExecutionGateTest {
    @Test
    fun `non-streaming blocks probe and generation until fully drained`() {
        val gate = LlamaRuntimeExecutionGate()

        assertTrue(gate.tryBeginNonStreaming())
        assertTrue(gate.tryBeginNonStreaming())
        assertFalse(gate.tryBeginProbe())
        assertNull(gate.tryBeginGeneration("request-a"))

        gate.endNonStreaming()
        assertFalse(gate.tryBeginProbe())
        assertNull(gate.tryBeginGeneration("request-a"))

        gate.endNonStreaming()
        assertTrue(gate.tryBeginProbe())
        gate.endProbe()
        val generation = gate.tryBeginGeneration("request-a")
        assertNotNull(generation)
        assertTrue(gate.endGeneration(generation))
    }

    @Test
    fun `config path is busy only for active generation or probe`() {
        val gate = LlamaRuntimeExecutionGate()

        assertFalse(gate.isBusyForConfig())
        assertTrue(gate.tryBeginNonStreaming())
        assertFalse(gate.isBusyForConfig())
        gate.endNonStreaming()

        val generation = gate.tryBeginGeneration("request-a")
        assertNotNull(generation)
        assertTrue(gate.isBusyForConfig())
        assertTrue(gate.endGeneration(generation))
        assertFalse(gate.isBusyForConfig())

        assertTrue(gate.tryBeginProbe())
        assertTrue(gate.isBusyForConfig())
        gate.endProbe()
        assertFalse(gate.isBusyForConfig())
    }

    @Test
    fun `request A owns cancellation while request B is rejected without changing ownership`() {
        val gate = LlamaRuntimeExecutionGate()
        val requestA = gate.tryBeginGeneration("request-a")

        assertNotNull(requestA)
        assertNull(gate.tryBeginGeneration("request-b"))
        assertEquals(
            LlamaRuntimeExecutionGate.GenerationCancelDecision.OWNER,
            gate.generationCancelDecision("request-a"),
        )
        val wrongRequest = gate.generationCancelDecision("request-b")
        assertEquals(LlamaRuntimeExecutionGate.GenerationCancelDecision.NOT_OWNER, wrongRequest)
        assertEquals(REMOTE_ERROR_NOT_GENERATION_OWNER, wrongRequest.errorCode)
        assertEquals(
            LlamaRuntimeExecutionGate.GenerationCancelDecision.OWNER,
            gate.generationCancelDecision("request-a"),
        )
    }

    @Test
    fun `blank and global cancellation are rejected with request-id-required code`() {
        val gate = LlamaRuntimeExecutionGate()
        assertNotNull(gate.tryBeginGeneration("request-a"))

        listOf(null, "", "   ").forEach { requestId ->
            val decision = gate.generationCancelDecision(requestId)
            assertEquals(LlamaRuntimeExecutionGate.GenerationCancelDecision.REQUEST_ID_REQUIRED, decision)
            assertEquals(REMOTE_ERROR_REQUEST_ID_REQUIRED, decision.errorCode)
        }
    }

    @Test
    fun `stale cleanup cannot clear a newer generation owner`() {
        val gate = LlamaRuntimeExecutionGate()
        val requestA = assertNotNull(gate.tryBeginGeneration("request-a"))
        assertTrue(gate.endGeneration(requestA))
        val requestB = assertNotNull(gate.tryBeginGeneration("request-b"))

        assertFalse(gate.endGeneration(requestA))
        assertEquals(
            LlamaRuntimeExecutionGate.GenerationCancelDecision.OWNER,
            gate.generationCancelDecision("request-b"),
        )
        assertTrue(gate.endGeneration(requestB))
    }

    @Test
    fun `lease identity protects a reused request id from stale cleanup`() {
        val gate = LlamaRuntimeExecutionGate()
        val firstLease = assertNotNull(gate.tryBeginGeneration("request-a"))
        assertTrue(gate.endGeneration(firstLease))
        val secondLease = assertNotNull(gate.tryBeginGeneration("request-a"))

        assertFalse(gate.endGeneration(firstLease))
        assertEquals(
            LlamaRuntimeExecutionGate.GenerationCancelDecision.OWNER,
            gate.generationCancelDecision("request-a"),
        )
        assertTrue(gate.endGeneration(secondLease))
    }

    @Test
    fun `image generation remains explicitly unsupported by service IPC`() {
        assertFalse(LlamaRuntimeIpc.SUPPORTS_IMAGE_GENERATION)
    }
}
