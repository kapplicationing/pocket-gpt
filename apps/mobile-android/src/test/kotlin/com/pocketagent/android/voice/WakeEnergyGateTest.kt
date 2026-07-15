package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class WakeEnergyGateTest {
    @Test
    fun `quiet audio is skipped until speech opens the decoder with preroll`() {
        val gate = WakeEnergyGate(rmsThreshold = 0.003f, trailingSilenceMs = 1_200L)

        assertEquals(WakeEnergyGateDecision.SKIP, gate.onChunk(rms = 0.001f, nowMs = 0L))
        assertEquals(
            WakeEnergyGateDecision.OPEN_WITH_PREROLL,
            gate.onChunk(rms = 0.003f, nowMs = 100L),
        )
        assertEquals(WakeEnergyGateDecision.PROCESS, gate.onChunk(rms = 0.001f, nowMs = 1_299L))
        assertEquals(
            WakeEnergyGateDecision.PROCESS_AND_CLOSE,
            gate.onChunk(rms = 0.001f, nowMs = 1_300L),
        )
        assertEquals(WakeEnergyGateDecision.SKIP, gate.onChunk(rms = 0.001f, nowMs = 1_301L))
    }

    @Test
    fun `speech between words extends the trailing decoder window`() {
        val gate = WakeEnergyGate(rmsThreshold = 0.003f, trailingSilenceMs = 1_200L)

        assertEquals(
            WakeEnergyGateDecision.OPEN_WITH_PREROLL,
            gate.onChunk(rms = 0.004f, nowMs = 100L),
        )
        assertEquals(WakeEnergyGateDecision.PROCESS, gate.onChunk(rms = 0.004f, nowMs = 1_000L))
        assertEquals(WakeEnergyGateDecision.PROCESS, gate.onChunk(rms = 0.001f, nowMs = 2_199L))
        assertEquals(
            WakeEnergyGateDecision.PROCESS_AND_CLOSE,
            gate.onChunk(rms = 0.001f, nowMs = 2_200L),
        )
    }

    @Test
    fun `reset closes an active gate immediately`() {
        val gate = WakeEnergyGate(rmsThreshold = 0.003f, trailingSilenceMs = 1_200L)

        gate.onChunk(rms = 0.004f, nowMs = 100L)
        gate.reset()

        assertEquals(WakeEnergyGateDecision.SKIP, gate.onChunk(rms = 0.001f, nowMs = 101L))
    }
}
