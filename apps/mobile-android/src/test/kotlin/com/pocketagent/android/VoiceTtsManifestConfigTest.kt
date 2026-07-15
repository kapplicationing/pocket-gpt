package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class VoiceTtsManifestConfigTest {
    @Test
    fun `manifest declares text to speech service visibility`() {
        val repoRoot = File(checkNotNull(System.getProperty("user.dir"))).resolve("../..").canonicalFile
        val manifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"))
    }
}
