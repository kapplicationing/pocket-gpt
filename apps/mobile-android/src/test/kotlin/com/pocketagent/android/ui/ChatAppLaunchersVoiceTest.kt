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
            voiceActivationFeedbackMessageResId(VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION),
        )
        assertEquals(
            R.string.ui_voice_activation_models_missing,
            voiceActivationFeedbackMessageResId(VoiceActivationEnableResult.BLOCKED_MODELS_MISSING),
        )
    }

    @Test
    fun `does not show snackbar for successful or manual disable results`() {
        assertNull(voiceActivationFeedbackMessageResId(VoiceActivationEnableResult.ENABLED))
        assertNull(voiceActivationFeedbackMessageResId(VoiceActivationEnableResult.DISABLED))
        assertNull(voiceActivationFeedbackMessageResId(VoiceActivationEnableResult.START_FAILED))
    }
}
