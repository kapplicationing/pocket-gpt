package com.pocketagent.android.ui

import com.pocketagent.android.R
import com.pocketagent.android.voice.VoiceActivationEnableResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatAppLaunchersVoiceTest {
    @Test
    fun `maps blocked voice activation results to support copy`() {
        assertEquals(
            R.string.ui_voice_activation_microphone_required,
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION,
            )?.messageResId,
        )
        assertEquals(
            R.string.ui_voice_activation_models_missing,
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.BLOCKED_MODELS_MISSING,
            )?.messageResId,
        )
    }

    @Test
    fun `uses stored start failure text for immediate support feedback`() {
        assertEquals(
            "Voice beta could not start: foreground service blocked",
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.START_FAILED,
                lastError = "Voice beta could not start: foreground service blocked",
            )?.messageText,
        )
    }

    @Test
    fun `does not show snackbar for successful or manual disable results`() {
        assertNull(voiceActivationFeedback(VoiceActivationEnableResult.ENABLED))
        assertNull(voiceActivationFeedback(VoiceActivationEnableResult.DISABLED))
        assertNull(voiceActivationFeedback(VoiceActivationEnableResult.START_FAILED))
    }
}
