package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OffasTranscriptOutcomeTest {
    @Test
    fun `prefers tool outputs over assistant text`() {
        val outcome = resolveOffasTranscriptOutcome(
            toolOutputs = listOf("Timer set", "Alarm scheduled"),
            assistantText = "I can help with that.",
            voiceActivationEnabled = true,
        )

        assertEquals("Timer set Alarm scheduled", outcome.spokenResponse)
        assertEquals(VoiceServiceState.LISTENING, outcome.nextServiceState)
    }

    @Test
    fun `uses assistant text when no tool outputs exist`() {
        val outcome = resolveOffasTranscriptOutcome(
            toolOutputs = emptyList(),
            assistantText = "Opening Maps.",
            voiceActivationEnabled = true,
        )

        assertEquals("Opening Maps.", outcome.spokenResponse)
        assertEquals(VoiceServiceState.LISTENING, outcome.nextServiceState)
    }

    @Test
    fun `falls back to done when tool outputs and assistant text are blank`() {
        val outcome = resolveOffasTranscriptOutcome(
            toolOutputs = emptyList(),
            assistantText = "",
            voiceActivationEnabled = false,
        )

        assertEquals("Done.", outcome.spokenResponse)
        assertEquals(VoiceServiceState.DISABLED, outcome.nextServiceState)
    }

    @Test
    fun `voice service continues while always on listening is enabled`() {
        assertTrue(
            shouldContinueVoiceService(
                nextServiceState = VoiceServiceState.LISTENING,
                voiceActivationEnabled = true,
            ),
        )
    }

    @Test
    fun `voice service stops when next state is not listening or beta is off`() {
        assertFalse(
            shouldContinueVoiceService(
                nextServiceState = VoiceServiceState.DISABLED,
                voiceActivationEnabled = true,
            ),
        )
        assertFalse(
            shouldContinueVoiceService(
                nextServiceState = VoiceServiceState.LISTENING,
                voiceActivationEnabled = false,
            ),
        )
    }
}
