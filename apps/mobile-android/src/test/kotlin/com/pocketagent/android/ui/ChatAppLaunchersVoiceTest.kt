package com.pocketagent.android.ui

import com.pocketagent.android.R
import com.pocketagent.android.voice.VoiceActivationEnableResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatAppLaunchersVoiceTest {
    @Test
    fun `android 13 requests notifications before microphone for always on voice`() {
        assertEquals(
            VoiceActivationPermissionStep.REQUEST_NOTIFICATIONS,
            nextVoiceActivationPermissionStep(
                sdkInt = 33,
                notificationPermissionGranted = false,
                microphonePermissionGranted = false,
            ),
        )
        assertEquals(
            VoiceActivationPermissionStep.REQUEST_MICROPHONE,
            nextVoiceActivationPermissionStep(
                sdkInt = 33,
                notificationPermissionGranted = true,
                microphonePermissionGranted = false,
            ),
        )
        assertEquals(
            VoiceActivationPermissionStep.ENABLE,
            nextVoiceActivationPermissionStep(
                sdkInt = 32,
                notificationPermissionGranted = false,
                microphonePermissionGranted = true,
            ),
        )
    }

    @Test
    fun `maps blocked voice activation results to support copy`() {
        assertEquals(
            R.string.ui_voice_activation_notifications_required,
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.BLOCKED_NOTIFICATION_PERMISSION,
            )?.messageResId,
        )
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
        assertEquals(
            R.string.ui_voice_activation_assistant_required,
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.BLOCKED_ASSISTANT_NOT_SELECTED,
            )?.messageResId,
        )
        assertEquals(
            R.string.ui_voice_activation_setup_started,
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.SETUP_STARTED,
            )?.messageResId,
        )
    }

    @Test
    fun `uses stored start failure text for immediate support feedback`() {
        assertEquals(
            "Hands-free voice could not start: foreground service blocked",
            voiceActivationFeedback(
                result = VoiceActivationEnableResult.START_FAILED,
                lastError = "Hands-free voice could not start: foreground service blocked",
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
