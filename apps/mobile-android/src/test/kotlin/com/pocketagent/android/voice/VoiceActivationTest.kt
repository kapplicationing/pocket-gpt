package com.pocketagent.android.voice

import android.media.AudioManager
import android.os.PowerManager
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
    fun `kws catalog matches verified production int8 bundle filenames`() {
        assertEquals(
            listOf(
                "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "tokens.txt",
            ),
            VoiceModelCatalog.requiredKwsFileNames(),
        )
    }

    @Test
    fun `dedicated kws requires only the Offas keyword`() {
        assertTrue(hasDedicatedOffasKeyword("▁OF F ▁US @OFFAS\n"))
        assertFalse(hasDedicatedOffasKeyword("▁OF F AS @OFFAS\n"))
        assertFalse(hasDedicatedOffasKeyword("▁HI ▁GOOGLE @HI_GOOGLE\n▁HEY ▁SIRI @HEY_SIRI\n"))
    }

    @Test
    fun `dedicated kws accepts only the supported wake phrase`() {
        assertTrue(canUseDedicatedOffasKeyword(" offas "))
        assertFalse(canUseDedicatedOffasKeyword("Jarvis"))
    }

    @Test
    fun `hands free stop command is narrow and rejects negation`() {
        assertTrue(isHandsFreeStopCommand("stop listening"))
        assertTrue(isHandsFreeStopCommand("Please turn off hands-free Offas"))
        assertTrue(isHandsFreeStopCommand("disable always-on listening"))
        assertFalse(isHandsFreeStopCommand("don't stop listening"))
        assertFalse(isHandsFreeStopCommand("stop the timer"))
    }

    @Test
    fun `locked voice session details never expose the transcript`() {
        assertEquals(
            "hidden",
            visibleVoiceSessionDetail(
                invocationSource = VoiceInvocationSource.LOCKED_ASSISTANT,
                deviceLocked = true,
                unlockedDetail = "private transcript",
                lockedDetail = "hidden",
            ),
        )
        assertEquals(
            "public transcript",
            visibleVoiceSessionDetail(
                invocationSource = VoiceInvocationSource.ASSISTANT,
                deviceLocked = false,
                unlockedDetail = "public transcript",
                lockedDetail = "hidden",
            ),
        )
    }

    @Test
    fun `external activity allowance is scoped to one background transition`() {
        VoiceSessionVisibility.beginSession()
        VoiceSessionVisibility.markExternalActivityStarting()

        assertTrue(VoiceSessionVisibility.consumeExternalActivityStarting())
        assertFalse(VoiceSessionVisibility.consumeExternalActivityStarting())

        VoiceSessionVisibility.markExternalActivityStarting()
        VoiceSessionVisibility.cancelExternalActivityStarting()
        assertFalse(VoiceSessionVisibility.consumeExternalActivityStarting())
    }

    @Test
    fun `voice session ids reject stale and unscoped events`() {
        val first = VoiceSessionSignals.newSessionId()
        val second = VoiceSessionSignals.newSessionId()

        assertTrue(second > first)
        assertTrue(VoiceSessionSignals.matches(second, second))
        assertFalse(VoiceSessionSignals.matches(second, first))
        assertFalse(VoiceSessionSignals.matches(VoiceSessionSignals.NO_SESSION_ID, second))
    }

    @Test
    fun `runtime retry uses capped backoff and opens after the failure limit`() {
        val policy = VoiceRuntimeRetryPolicy(maxFailures = 3)

        assertEquals(1_000L, policy.nextDelayMillis(1_000L))
        assertEquals(2_000L, policy.nextDelayMillis(2_000L))
        assertEquals(5_000L, policy.nextDelayMillis(3_000L))
        assertEquals(null, policy.nextDelayMillis(4_000L))

        policy.reset()
        assertEquals(1_000L, policy.nextDelayMillis(5_000L))
    }

    @Test
    fun `runtime pauses for calls severe heat and critical unplugged battery`() {
        assertEquals(
            VoiceRuntimePauseReason.PHONE_CALL,
            voiceRuntimePauseReason(
                audioMode = AudioManager.MODE_IN_CALL,
                batteryPercent = 80,
                charging = false,
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            ),
        )
        assertEquals(
            VoiceRuntimePauseReason.THERMAL,
            voiceRuntimePauseReason(
                audioMode = AudioManager.MODE_NORMAL,
                batteryPercent = 80,
                charging = false,
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            ),
        )
        assertEquals(
            VoiceRuntimePauseReason.CRITICAL_BATTERY,
            voiceRuntimePauseReason(
                audioMode = AudioManager.MODE_NORMAL,
                batteryPercent = 5,
                charging = false,
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            ),
        )
        assertEquals(
            null,
            voiceRuntimePauseReason(
                audioMode = AudioManager.MODE_NORMAL,
                batteryPercent = 5,
                charging = true,
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            ),
        )
    }

    @Test
    fun `ambient transcript removes leading wake phrase and common ASR spellings`() {
        assertEquals("open maps", removeLeadingWakePhrase("Off us, open maps", "Offas"))
        assertEquals("open maps", removeLeadingWakePhrase("Offas open maps", "Offas"))
        assertEquals("stop listening", removeLeadingWakePhrase("office stop listening", "Offas"))
        assertEquals("set a timer", removeLeadingWakePhrase("offers set a timer", "Offas"))
        assertEquals("tell me about Offas", removeLeadingWakePhrase("tell me about Offas", "Offas"))
        assertEquals("open maps", removeLeadingWakePhrase("office open maps", "Offas"))
    }

    @Test
    fun `only the non exported assistant alias receives trusted one shot policy`() {
        assertEquals(
            VoiceInvocationSource.ASSISTANT,
            assistInvocationSource(INTERNAL_ASSIST_ACTIVITY_CLASS),
        )
        assertEquals(
            VoiceInvocationSource.UNTRUSTED_ASSISTANT,
            assistInvocationSource("com.pocketagent.android.voice.AssistActivity"),
        )
        assertEquals(VoiceInvocationSource.UNTRUSTED_ASSISTANT, assistInvocationSource(null))
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
            "Microphone permission is required before hands-free voice can start.",
            store.state().lastError,
        )
    }

    @Test
    fun `enable voice activation blocks notifications and preserves support copy`() {
        val store = VoiceActivationSettingsStore(VoiceActivationTestStorage())

        val result = enableVoiceActivation(
            settingsStore = store,
            betaContract = VoiceBetaContract(blockingIssue = VoiceBetaBlockingIssue.NOTIFICATION_PERMISSION),
            startRuntime = { error("should not start") },
        )

        assertEquals(VoiceActivationEnableResult.BLOCKED_NOTIFICATION_PERMISSION, result)
        assertFalse(store.state().enabled)
        assertEquals(
            "Notification permission is required so Android can show when always-on listening uses the microphone.",
            store.state().lastError,
        )
    }

    @Test
    fun `enable voice activation blocks missing models and preserves support copy`() {
        val store = VoiceActivationSettingsStore(VoiceActivationTestStorage())

        val result = enableVoiceActivation(
            settingsStore = store,
            betaContract = VoiceBetaContract(blockingIssue = VoiceBetaBlockingIssue.MODELS_MISSING),
            startRuntime = { error("should not start") },
        )

        assertEquals(VoiceActivationEnableResult.BLOCKED_MODELS_MISSING, result)
        assertFalse(store.state().enabled)
        assertEquals(
            "Hands-free voice needs its local voice files before listening can start.",
            store.state().lastError,
        )
    }

    @Test
    fun `enable voice activation refuses speech recognition wake fallback`() {
        val store = VoiceActivationSettingsStore(VoiceActivationTestStorage())

        val result = enableVoiceActivation(
            settingsStore = store,
            betaContract = VoiceBetaContract(
                blockingIssue = VoiceBetaBlockingIssue.WAKE_WORD_MODEL_MISSING,
            ),
            startRuntime = { error("should not start") },
        )

        assertEquals(VoiceActivationEnableResult.BLOCKED_WAKE_WORD_MODEL_MISSING, result)
        assertFalse(store.state().enabled)
        assertTrue(store.state().lastError.orEmpty().contains("dedicated Offas wake-word model"))
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
            "Hands-free voice could not start: foreground service blocked",
            store.state().lastError,
        )
    }

    @Test
    fun `start failure message falls back to generic guidance when throwable is blank`() {
        assertEquals(
            "Hands-free voice could not start. Check microphone access and the local voice setup, then retry.",
            voiceActivationStartFailureMessage(IllegalStateException("  ")),
        )
    }

    @Test
    fun `new voice session clears pending work while confirmation continuation preserves it`() {
        assertTrue(
            shouldClearPendingInteractionForSessionStart(
                currentSessionId = 10L,
                nextSessionId = 11L,
            ),
        )
        assertFalse(
            shouldClearPendingInteractionForSessionStart(
                currentSessionId = 10L,
                nextSessionId = 10L,
            ),
        )
        assertFalse(
            shouldClearPendingInteractionForSessionStart(
                currentSessionId = 10L,
                nextSessionId = VoiceSessionSignals.NO_SESSION_ID,
            ),
        )
    }

    @Test
    fun `transient audio focus interruption pauses instead of disabling voice`() {
        assertEquals(
            VoiceSpeechFailureRecovery.PAUSE,
            voiceSpeechFailureRecovery(VoiceRuntimePauseReason.OTHER_MICROPHONE),
        )
        assertEquals(
            VoiceSpeechFailureRecovery.DISABLE,
            voiceSpeechFailureRecovery(null),
        )
    }

    @Test
    fun `every replacement loop prepares its direct or wake capture mode`() {
        val preparedModes = mutableListOf<Boolean>()
        val engine = object : OffasVoiceEngine {
            override suspend fun awaitWakeAndCommand(
                wakePhrase: String,
                silenceTimeoutSeconds: Int,
                directCapture: Boolean,
                onWakeWord: () -> Unit,
                onStateChanged: (VoiceServiceState) -> Unit,
                onPartialTranscript: (String) -> Unit,
            ): String? = null

            override fun prepareCapture(directCapture: Boolean) {
                preparedModes += directCapture
            }

            override fun release() = Unit
        }

        prepareVoiceEngineForLoop(engine, directCapture = true)
        prepareVoiceEngineForLoop(engine, directCapture = false)

        assertEquals(listOf(true, false), preparedModes)
    }
}

private class VoiceActivationTestStorage(
    val booleanValues: MutableMap<String, Boolean> = mutableMapOf(),
    val stringValues: MutableMap<String, String?> = mutableMapOf(),
    val intValues: MutableMap<String, Int> = mutableMapOf(),
    val longValues: MutableMap<String, Long> = mutableMapOf(),
) : VoiceActivationSettingsStorage {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleanValues[key] ?: defaultValue

    override fun getString(key: String, defaultValue: String?): String? = stringValues[key] ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int = intValues[key] ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long = longValues[key] ?: defaultValue

    override fun save(settings: VoiceActivationSettings) {
        booleanValues["enabled"] = settings.enabled
        stringValues["wake_phrase"] = settings.wakePhrase
        intValues["silence_timeout_seconds"] = settings.silenceTimeoutSeconds
        stringValues["service_state"] = settings.voiceServiceState.name
        stringValues["last_error"] = settings.lastError
        longValues["enabled_at_epoch_ms"] = settings.enabledAtEpochMs
    }
}
