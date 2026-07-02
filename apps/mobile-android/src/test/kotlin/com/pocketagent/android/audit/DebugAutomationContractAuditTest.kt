package com.pocketagent.android.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DebugAutomationContractAuditTest {
    @Test
    fun debugAutomationIntentIsGuardedByDebugBuildChecks() {
        val source = resolveAppSource("src/main/kotlin/com/pocketagent/android/MainActivity.kt").readText()

        assertTrue(source.contains("if (!BuildConfig.DEBUG || intent == null)"))
        assertTrue(source.contains("intent.action == ACTION_DEBUG_OPEN_MODEL_LIBRARY"))
        assertTrue(source.contains("requestedSurface == DEBUG_OPEN_SURFACE_MODEL_LIBRARY"))
        assertTrue(
            source.contains("if (!BuildConfig.DEBUG || request == null)"),
            "Debug automation request handling must stay guarded by BuildConfig.DEBUG.",
        )
    }

    private fun resolveAppSource(relativePath: String): File {
        return sequenceOf(
            File(relativePath),
            File("apps/mobile-android/$relativePath"),
        ).firstOrNull(File::exists)
            ?: error("Unable to resolve $relativePath")
    }
}
