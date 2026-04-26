package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceActivationTest {
    @Test
    fun `maps samsung manufacturer to samsung guide`() {
        assertEquals(OemBatteryGuide.SAMSUNG, OemBatteryGuide.fromManufacturer("Samsung"))
    }

    @Test
    fun `maps poco manufacturer to xiaomi guide`() {
        assertEquals(OemBatteryGuide.XIAOMI, OemBatteryGuide.fromManufacturer("POCO"))
    }

    @Test
    fun `falls back to generic guide`() {
        assertEquals(OemBatteryGuide.GENERIC, OemBatteryGuide.fromManufacturer("Google"))
    }

    @Test
    fun `normalizes pcm samples into float chunk`() {
        val chunk = shortArrayOf(0, 16384, -16384, 32767).toFloatChunk()
        assertEquals(0f, chunk[0])
        assertTrue(chunk[1] > 0.49f && chunk[1] < 0.51f)
        assertTrue(chunk[2] < -0.49f && chunk[2] > -0.51f)
        assertTrue(chunk[3] > 0.99f)
    }

    @Test
    fun `computes rms for audio chunk`() {
        val rms = floatArrayOf(1f, -1f, 1f, -1f).rms()
        assertEquals(1f, rms)
    }

    @Test
    fun `enable voice activation blocks microphone and preserves support copy`() {
        val store = VoiceActivationSettingsStore(VoiceActivationTestStorage())

        val result = enableVoiceActivation(
            settingsStore = store,
            betaContract = VoiceBetaContract(blockingIssue = VoiceBetaBlockingIssue.MICROPHONE_PERMISSION),
            startRuntime = { error("should not start") },
        )

        assertEquals(VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION, result)
        assertFalse(store.state().enabled)
        assertEquals(
            "Microphone permission is required before voice beta can start.",
            store.state().lastError,
        )
    }

    @Test
    fun `enable voice activation rolls back when service start fails`() {
        val store = VoiceActivationSettingsStore(VoiceActivationTestStorage())

        val result = enableVoiceActivation(
            settingsStore = store,
            betaContract = VoiceBetaContract(),
            startRuntime = { throw IllegalStateException("foreground service blocked") },
        )

        assertEquals(VoiceActivationEnableResult.START_FAILED, result)
        assertFalse(store.state().enabled)
        assertEquals(VoiceServiceState.DISABLED, store.state().voiceServiceState)
        assertEquals(
            "Voice beta could not start: foreground service blocked",
            store.state().lastError,
        )
    }

    @Test
    fun `start failure message falls back to generic guidance when throwable is blank`() {
        assertEquals(
            "Voice beta could not start. Try again after checking microphone permission and local voice models.",
            voiceActivationStartFailureMessage(IllegalStateException("  ")),
        )
    }
}

private class VoiceActivationTestStorage(
    val booleanValues: MutableMap<String, Boolean> = mutableMapOf(),
    val stringValues: MutableMap<String, String?> = mutableMapOf(),
    val intValues: MutableMap<String, Int> = mutableMapOf(),
) : VoiceActivationSettingsStorage {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleanValues[key] ?: defaultValue

    override fun getString(key: String, defaultValue: String?): String? = stringValues[key] ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int = intValues[key] ?: defaultValue

    override fun save(settings: VoiceActivationSettings) {
        booleanValues["enabled"] = settings.enabled
        stringValues["wake_phrase"] = settings.wakePhrase
        intValues["silence_timeout_seconds"] = settings.silenceTimeoutSeconds
        stringValues["service_state"] = settings.voiceServiceState.name
        stringValues["last_error"] = settings.lastError
    }
}
