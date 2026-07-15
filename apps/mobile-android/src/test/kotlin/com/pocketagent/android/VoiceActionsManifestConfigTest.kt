package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceActionsManifestConfigTest {
    @Test
    fun `manifest declares bounded clock and flashlight capabilities without legacy receivers`() {
        val repoRoot = File(checkNotNull(System.getProperty("user.dir"))).resolve("../..").canonicalFile
        val manifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()
        val voiceStack = File(
            repoRoot,
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/voice/OffasVoiceStack.kt",
        ).readText()
        val voiceInteractionConfig = File(
            repoRoot,
            "apps/mobile-android/src/main/res/xml/voice_interaction_service.xml",
        ).readText()

        assertTrue(manifest.contains("com.android.alarm.permission.SET_ALARM"))
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.intent.action.SET_ALARM"))
        assertTrue(manifest.contains("android.intent.action.SET_TIMER"))
        assertTrue(manifest.contains("android.hardware.camera.flash"))
        assertTrue(manifest.contains("android:required=\"false\""))
        assertTrue(manifest.contains(".voice.InternalAssistActivity"))
        assertTrue(manifest.contains(".voice.PocketAgentVoiceInteractionService"))
        assertTrue(manifest.contains("android.permission.BIND_VOICE_INTERACTION"))
        assertTrue(manifest.contains("android.service.voice.VoiceInteractionService"))
        assertTrue(voiceInteractionConfig.contains("android:supportsAssist=\"true\""))
        assertTrue(voiceInteractionConfig.contains("android:supportsLaunchVoiceAssistFromKeyguard=\"true\""))
        assertTrue(
            Regex(
                "<activity-alias[\\s\\S]*?InternalAssistActivity[\\s\\S]*?" +
                    "android:exported=\\\"false\\\"",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                "<activity[\\s\\S]*?AssistActivity[\\s\\S]*?" +
                    "android:exported=\\\"true\\\"[\\s\\S]*?" +
                    "android:permission=\\\"android.permission.BIND_VOICE_INTERACTION\\\"",
            ).containsMatchIn(manifest),
        )
        assertTrue(manifest.contains("android.intent.action.ASSIST"))
        assertFalse(manifest.contains(".voice.OffasWatchdogReceiver"))
        assertFalse(manifest.contains(".voice.OffasAlarmReceiver"))
        assertFalse(voiceStack.contains("STOP_FOREGROUND_DETACH"))
    }
}
