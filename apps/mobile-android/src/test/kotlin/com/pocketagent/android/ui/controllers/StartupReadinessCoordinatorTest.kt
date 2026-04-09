package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupReadinessCoordinatorTest {
    private val coordinator = StartupReadinessCoordinator()

    @Test
    fun `empty checks resolve ready state`() {
        val decision = coordinator.decide(
            startupChecks = emptyList(),
            runtimeBackend = "NATIVE_JNI",
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.READY, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.READY, decision.modelRuntimeStatus)
        assertTrue(decision.modelStatusDetail.contains("NATIVE_JNI"))
        assertNull(decision.startupError)
    }

    @Test
    fun `timeout only checks resolve blocked timeout with startup error`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Startup checks timed out after 30s."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.BLOCKED_TIMEOUT, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.NOT_READY, decision.modelRuntimeStatus)
        assertEquals(0, decision.startupWarnings.size)
        assertNotNull(decision.startupError)
    }

    @Test
    fun `optional model warnings resolve ready state with warnings`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Optional runtime model unavailable: qwen3-1.7b-q4_k_m."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.READY, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.READY, decision.modelRuntimeStatus)
        assertTrue(decision.modelStatusDetail.contains("optional model unavailable"))
        assertEquals(1, decision.startupWarnings.size)
        assertNull(decision.startupError)
    }

    @Test
    fun `optional warning details are summarized without verbose diagnostics`() {
        val decision = coordinator.decide(
            startupChecks = listOf(
                "Optional runtime model unavailable: qwen3.5-0.8b-q4. Artifact verification failed for qwen3.5-0.8b-q4: MODEL_ARTIFACT_VERIFICATION_ERROR:MISSING_PAYLOAD:model=qwen3.5-0.8b-q4;version=1;expected_sha=abc;actual_sha=none",
                "Optional runtime model unavailable: qwen3-1.7b-q4_k_m. Artifact verification failed for qwen3-1.7b-q4_k_m: MODEL_ARTIFACT_VERIFICATION_ERROR:MISSING_PAYLOAD:model=qwen3-1.7b-q4_k_m;version=1;expected_sha=def;actual_sha=none",
                "Optional runtime model unavailable: qwen3-0.6b-q4_k_m. MODEL_ARTIFACT_CONFIG_MISSING:model=qwen3-0.6b-q4_k_m;field=payload_or_path,sha256",
                "Optional runtime model unavailable: llama-3.2-1b-instruct-q4_k_m. MODEL_ARTIFACT_CONFIG_MISSING:model=llama-3.2-1b-instruct-q4_k_m;field=payload_or_path,sha256",
            ),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.READY, decision.startupProbeState)
        assertTrue(decision.modelStatusDetail.contains("optional model unavailable"))
        assertTrue(decision.modelStatusDetail.contains("+1 more"))
        assertTrue(!decision.modelStatusDetail.contains("MODEL_ARTIFACT_VERIFICATION_ERROR"))
        assertTrue(!decision.modelStatusDetail.contains("expected_sha"))
    }

    @Test
    fun `blocking startup checks return blocked state and mapped startup error`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Missing runtime model(s): qwen3.5-0.8b-q4."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.BLOCKED, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.NOT_READY, decision.modelRuntimeStatus)
        assertNotNull(decision.startupError)
        assertEquals("UI-STARTUP-001", decision.startupError?.code)
    }
}
