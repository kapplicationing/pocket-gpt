package com.pocketagent.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugAutomationEntryPointInstrumentationTest {
    @Test
    fun debugIntentOpensModelLibraryAndSkipsOnboarding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        device.pressHome()
        device.executeShellCommand(
            "am start -W " +
                "-n ${context.packageName}/.MainActivity " +
                "-a $ACTION_DEBUG_OPEN_MODEL_LIBRARY " +
                "--ez $EXTRA_DEBUG_SKIP_ONBOARDING true " +
                "--es $EXTRA_DEBUG_OPEN_SURFACE $DEBUG_OPEN_SURFACE_MODEL_LIBRARY " +
                "--ez $EXTRA_DEBUG_CLEAR_RECENT_HF true",
        )

        assertTrue(
            "Expected debug launch to open the Model library sheet.",
            device.wait(Until.hasObject(By.text("Model library")), 30_000),
        )
        assertTrue(
            "Expected debug readiness tag on the Model library sheet.",
            device.wait(Until.hasObject(By.res("debug_model_library_ready")), 10_000),
        )
        assertTrue(
            "Expected My models to be the default Model library section after debug launch.",
            device.wait(Until.hasObject(By.res("model_library_tab_my_models")), 10_000),
        )
        assertFalse("Onboarding next button should not be visible.", device.hasObject(By.res("onboarding_next")))
        assertFalse(
            "Onboarding get-started button should not be visible.",
            device.hasObject(By.res("onboarding_get_started")),
        )
    }

    @Test
    fun debugIntentCanResolveHuggingFaceCandidateState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        device.pressHome()
        device.executeShellCommand(
            "am start -W " +
                "-n ${context.packageName}/.MainActivity " +
                "-a $ACTION_DEBUG_OPEN_MODEL_LIBRARY " +
                "--ez $EXTRA_DEBUG_SKIP_ONBOARDING true " +
                "--es $EXTRA_DEBUG_OPEN_SURFACE $DEBUG_OPEN_SURFACE_MODEL_LIBRARY " +
                "--es $EXTRA_DEBUG_HF_RESOLVE_URL https://example.com/not-a-model.gguf",
        )

        assertTrue(
            "Expected debug launch to open the Model library sheet.",
            device.wait(Until.hasObject(By.text("Model library")), 30_000),
        )
        assertTrue(
            "Expected debug HF resolution status to reflect the invalid URL.",
            device.wait(Until.hasObject(By.text("hf_blocked:INVALID_URL")), 10_000),
        )
    }

    private companion object {
        const val ACTION_DEBUG_OPEN_MODEL_LIBRARY = "com.pocketagent.android.DEBUG_OPEN_MODEL_LIBRARY"
        const val EXTRA_DEBUG_SKIP_ONBOARDING = "pocketagent.debug.skip_onboarding"
        const val EXTRA_DEBUG_OPEN_SURFACE = "pocketagent.debug.open_surface"
        const val EXTRA_DEBUG_CLEAR_RECENT_HF = "pocketagent.debug.clear_recent_hf"
        const val EXTRA_DEBUG_HF_RESOLVE_URL = "pocketagent.debug.hf_resolve_url"
        const val DEBUG_OPEN_SURFACE_MODEL_LIBRARY = "model_library"
    }
}
