package com.pocketagent.android.baselineprofile

import android.os.SystemClock
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
            exerciseSessionDrawer()
            exerciseAdvancedSettings()
            exerciseCompletionSettings()
            openAndCloseSurface("open_model_library", "unified_model_sheet")
        }
    }

    private fun MacrobenchmarkScope.startCriticalShell() {
        pressHome()
        startActivityAndWait()

        val deadline = SystemClock.uptimeMillis() + SHELL_TIMEOUT_MS
        var onboardingDismissed = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.res("session_drawer_button")) != null) {
                return
            }
            if (!onboardingDismissed) {
                device.findObject(By.res("onboarding_skip"))?.let { skip ->
                    skip.click()
                    onboardingDismissed = true
                }
            }
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }
        error("Timed out waiting for the shell or dismissible onboarding")
    }

    private fun exerciseSessionDrawer() {
        val search = openSessionDrawerSearch()
        search.click()
        search.text = PROFILE_SEARCH_QUERY
        SystemClock.sleep(UI_SETTLE_MS)
        search.text = ""

        device.pressBack()
        requireResource("session_search_input")
        device.pressBack()
        check(device.wait(Until.gone(By.res("session_search_input")), UI_TIMEOUT_MS)) {
            "Session drawer did not close after hiding its IME"
        }
        requireResource("session_drawer_button")
    }

    private fun openSessionDrawerSearch(): UiObject2 {
        device.findObject(By.res("session_search_input"))?.let { return it }
        repeat(DRAWER_OPEN_ATTEMPTS) {
            requireResource("session_drawer_button").click()
            val search = device.wait(
                Until.findObject(By.res("session_search_input")),
                DRAWER_OPEN_ATTEMPT_MS,
            )
            if (search != null) {
                return search
            }
        }
        error("Timed out waiting for Compose semantics resource 'session_search_input'")
    }

    private fun exerciseAdvancedSettings() {
        requireResource("advanced_sheet_button").click()
        requireResource("advanced_settings_sheet")

        val horizontalCenter = device.displayWidth / 2
        val swipeCompleted = device.swipe(
            horizontalCenter,
            device.displayHeight * 3 / 4,
            horizontalCenter,
            device.displayHeight / 3,
            SCROLL_STEPS,
        )
        check(swipeCompleted) { "Advanced settings profile swipe did not complete" }
        device.waitForIdle()
        requireResource("advanced_tab_model").click()
        device.waitForIdle()
        requireResource("advanced_tab_about").click()
        device.waitForIdle()
        requireResource("advanced_tab_general").click()
        device.waitForIdle()

        device.pressBack()
        requireResource("advanced_sheet_button")
    }

    private fun exerciseCompletionSettings() {
        requireResource("completion_settings_button").click()
        requireResource("completion_system_prompt_input").click()
        SystemClock.sleep(IME_SETTLE_MS)

        device.pressBack()
        requireResource("completion_system_prompt_input")
        device.pressBack()
        requireResource("completion_settings_button")
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
        const val UI_POLL_INTERVAL_MS = 100L
        const val IME_SETTLE_MS = 300L
        const val UI_SETTLE_MS = 150L
        const val DRAWER_OPEN_ATTEMPT_MS = 2_500L
        const val DRAWER_OPEN_ATTEMPTS = 2
        const val SCROLL_STEPS = 12
        const val PROFILE_SEARCH_QUERY = "profile"
    }
}
