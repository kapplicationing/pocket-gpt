package com.pocketagent.android.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun normalNavigation() {
        baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
            startCriticalShell()
            openAndCloseSurface("session_drawer_button", "session_search_input")
            openAndCloseSurface("advanced_sheet_button", "advanced_settings_sheet")
            openAndCloseSurface("completion_settings_button", "completion_system_prompt_input")
            openAndCloseSurface("open_model_library", "unified_model_sheet")
        }
    }

    private fun MacrobenchmarkScope.startCriticalShell() {
        pressHome()
        startActivityAndWait()
        if (!device.hasObject(By.res("session_drawer_button"))) {
            val onboardingSkip =
                device.wait(
                    Until.findObject(By.res("onboarding_skip")),
                    SHELL_TIMEOUT_MS,
                )
            onboardingSkip?.click()
        }
        requireResource("session_drawer_button", SHELL_TIMEOUT_MS)
    }

    private fun openAndCloseSurface(
        triggerResource: String,
        visibleResource: String? = null,
    ) {
        requireResource(triggerResource).click()
        if (visibleResource != null) {
            requireResource(visibleResource)
        }
        device.pressBack()
        requireResource(triggerResource)
    }

    private fun requireResource(
        resourceName: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2 {
        val resource = device.wait(Until.findObject(By.res(resourceName)), timeoutMs)
        return checkNotNull(resource) {
            "Timed out waiting for Compose semantics resource '$resourceName'"
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.pocketagent.android"
        const val UI_TIMEOUT_MS = 10_000L
        const val SHELL_TIMEOUT_MS = 30_000L
    }
}
