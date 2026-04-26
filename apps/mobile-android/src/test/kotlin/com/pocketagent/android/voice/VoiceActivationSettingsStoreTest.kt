package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceActivationSettingsStoreTest {
    @Test
    fun `reads defaults when storage is empty`() {
        val store = VoiceActivationSettingsStore(FakeVoiceActivationSettingsStorage())

        assertEquals(VoiceActivationSettings(), store.state())
        assertEquals(VoiceActivationSettings(), store.observe().value)
    }

    @Test
    fun `clamps timeout and falls back invalid service state`() {
        val storage = FakeVoiceActivationSettingsStorage(
            stringValues = mutableMapOf(
                "service_state" to "NOT_A_STATE",
                "wake_phrase" to "Jarvis",
            ),
            intValues = mutableMapOf("silence_timeout_seconds" to 99),
        )

        val store = VoiceActivationSettingsStore(storage)

        assertEquals("Jarvis", store.state().wakePhrase)
        assertEquals(10, store.state().silenceTimeoutSeconds)
        assertEquals(VoiceServiceState.DISABLED, store.state().voiceServiceState)
    }

    @Test
    fun `setEnabled clears stale error and persists startup state`() {
        val storage = FakeVoiceActivationSettingsStorage(
            stringValues = mutableMapOf("last_error" to "stale error"),
        )
        val store = VoiceActivationSettingsStore(storage)

        store.setEnabled(true)

        assertEquals(true, store.state().enabled)
        assertEquals(VoiceServiceState.STARTING, store.state().voiceServiceState)
        assertNull(store.state().lastError)
        assertEquals(true, storage.booleanValues["enabled"])
        assertEquals(VoiceServiceState.STARTING.name, storage.stringValues["service_state"])
        assertNull(storage.stringValues["last_error"])
    }

    @Test
    fun `updateServiceState clears stale error on healthy states and replaces on error`() {
        val storage = FakeVoiceActivationSettingsStorage(
            stringValues = mutableMapOf("last_error" to "microphone missing"),
        )
        val store = VoiceActivationSettingsStore(storage)

        store.updateServiceState(VoiceServiceState.CAPTURING)
        assertNull(store.state().lastError)

        store.updateServiceState(VoiceServiceState.ERROR, error = "models missing")
        assertEquals(VoiceServiceState.ERROR, store.state().voiceServiceState)
        assertEquals("models missing", store.state().lastError)
    }

    @Test
    fun `setLastError clears error without changing service state`() {
        val storage = FakeVoiceActivationSettingsStorage(
            stringValues = mutableMapOf(
                "last_error" to "network issue",
                "service_state" to VoiceServiceState.PROCESSING.name,
            ),
        )
        val store = VoiceActivationSettingsStore(storage)

        store.setLastError(null)

        assertEquals(VoiceServiceState.PROCESSING, store.state().voiceServiceState)
        assertNull(store.state().lastError)
        assertNull(storage.stringValues["last_error"])
    }

    @Test
    fun `disableWithError turns voice beta off and preserves action needed message`() {
        val storage = FakeVoiceActivationSettingsStorage(
            booleanValues = mutableMapOf("enabled" to true),
            stringValues = mutableMapOf("service_state" to VoiceServiceState.LISTENING.name),
        )
        val store = VoiceActivationSettingsStore(storage)

        store.disableWithError("Voice models missing.")

        assertEquals(false, store.state().enabled)
        assertEquals(VoiceServiceState.DISABLED, store.state().voiceServiceState)
        assertEquals("Voice models missing.", store.state().lastError)
        assertEquals(false, storage.booleanValues["enabled"])
        assertEquals(VoiceServiceState.DISABLED.name, storage.stringValues["service_state"])
    }

    @Test
    fun `manual disable clears stale error`() {
        val storage = FakeVoiceActivationSettingsStorage(
            booleanValues = mutableMapOf("enabled" to true),
            stringValues = mutableMapOf(
                "service_state" to VoiceServiceState.ERROR.name,
                "last_error" to "stale error",
            ),
        )
        val store = VoiceActivationSettingsStore(storage)

        store.setEnabled(false)

        assertEquals(false, store.state().enabled)
        assertEquals(VoiceServiceState.DISABLED, store.state().voiceServiceState)
        assertNull(store.state().lastError)
    }
}

private class FakeVoiceActivationSettingsStorage(
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
