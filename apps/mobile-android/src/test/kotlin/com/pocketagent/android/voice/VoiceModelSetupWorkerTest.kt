package com.pocketagent.android.voice

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceModelSetupWorkerTest {
    @Test
    fun `only the newest durable enable token can enable voice`() {
        var generation = 0L
        var requestedToken = VoiceModelSetupEnableRequest.NO_TOKEN
        val gate = VoiceModelSetupEnableTokenGate(
            generation = { generation },
            requestedToken = { requestedToken },
            saveRequest = { savedGeneration, savedToken ->
                generation = savedGeneration
                requestedToken = savedToken
            },
            clearRequest = { requestedToken = VoiceModelSetupEnableRequest.NO_TOKEN },
        )
        var enableCalls = 0

        val first = gate.request()
        val replacement = gate.request()

        assertFalse(gate.runIfCurrent(first) { enableCalls += 1 })
        assertTrue(gate.runIfCurrent(replacement) { enableCalls += 1 })
        assertEquals(1, enableCalls)
        assertFalse(gate.hasPendingRequest())
    }

    @Test
    fun `user opt out prevents a finishing worker from enabling voice`() {
        var generation = 0L
        var requestedToken = VoiceModelSetupEnableRequest.NO_TOKEN
        val gate = VoiceModelSetupEnableTokenGate(
            generation = { generation },
            requestedToken = { requestedToken },
            saveRequest = { savedGeneration, savedToken ->
                generation = savedGeneration
                requestedToken = savedToken
            },
            clearRequest = { requestedToken = VoiceModelSetupEnableRequest.NO_TOKEN },
        )
        val token = gate.request()
        gate.clear()

        assertFalse(gate.runIfCurrent(token) { error("Cancelled setup must not enable") })
    }

    @Test
    fun `only transient setup failures use WorkManager backoff`() {
        assertTrue(shouldRetryVoiceModelSetup(IOException("connection reset"), runAttemptCount = 0))
        assertTrue(
            shouldRetryVoiceModelSetup(
                IllegalStateException("Voice model download failed with HTTP 503."),
                runAttemptCount = 1,
            ),
        )
        assertFalse(
            shouldRetryVoiceModelSetup(
                IllegalStateException("Voice model download failed with HTTP 404."),
                runAttemptCount = 0,
            ),
        )
        assertFalse(
            shouldRetryVoiceModelSetup(
                IllegalStateException("Downloaded model failed its integrity check."),
                runAttemptCount = 0,
            ),
        )
        assertFalse(shouldRetryVoiceModelSetup(IOException("still offline"), runAttemptCount = 4))
    }
}
