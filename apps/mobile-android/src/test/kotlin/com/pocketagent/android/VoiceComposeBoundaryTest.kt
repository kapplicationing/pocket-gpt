package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class VoiceComposeBoundaryTest {
    @Test
    fun `root app does not observe high frequency voice state`() {
        val repoRoot = File(checkNotNull(System.getProperty("user.dir"))).resolve("../..").canonicalFile
        val source = File(
            repoRoot,
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt",
        ).readText()
        val rootApp = source.substringBefore("private fun ChatComposerDock")

        assertFalse(rootApp.contains("dictationController.observe()"))
        assertFalse(rootApp.contains("playbackController.observe()"))
    }
}
