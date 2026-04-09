package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResilienceGuardsTest {
    @Test
    fun `validate prompt truncates to configured max length`() {
        val guard = TaskGuard(maxPromptChars = 5)

        val prompt = guard.validatePrompt("123456789")

        assertEquals("12345", prompt)
    }

    @Test
    fun `can run long task only when battery is above threshold and thermal is safe`() {
        val guard = TaskGuard(minBatteryForLongTasks = 20)

        val lowBattery = guard.canRunTask(
            taskType = "long_text",
            deviceState = DeviceState(batteryPercent = 10, thermalLevel = 3, ramClassGb = 8),
        )
        val highThermal = guard.canRunTask(
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 8, ramClassGb = 8),
        )
        val safe = guard.canRunTask(
            taskType = "long_text",
            deviceState = DeviceState(batteryPercent = 55, thermalLevel = 4, ramClassGb = 8),
        )

        assertFalse(lowBattery)
        assertFalse(highThermal)
        assertTrue(safe)
    }

    @Test
    fun `startup check assessment separates blocking and recoverable checks`() {
        val assessor = StartupAssessor()

        val assessment = assessor.assessStartupChecks(
            listOf(
                "Artifact manifest invalid: qwen@1:MISSING_SHA256",
                "Runtime backend is ADB_FALLBACK. Native runtime required.",
                "Policy module rejected startup event type.",
            ),
        )

        assertFalse(assessment.canProceed)
        assertEquals(2, assessment.blockingChecks.size)
        assertEquals(1, assessment.recoverableChecks.size)
        assertTrue(assessment.blockingChecks.first().contains("Artifact manifest invalid"))
        assertTrue(assessment.blockingChecks.any { it.contains("Runtime backend is ADB_FALLBACK") })
        assertTrue(assessment.recoverableChecks.first().contains("Policy module rejected startup event type"))
    }

    @Test
    fun `optional model warning is recoverable but startup load failure is blocking`() {
        val assessor = StartupAssessor()

        val assessment = assessor.assessStartupChecks(
            listOf(
                "Optional runtime model unavailable: qwen3-1.7b-q4_k_m.",
                "Failed to load startup runtime model: qwen3.5-0.8b-q4.",
            ),
        )

        assertFalse(assessment.canProceed)
        assertEquals(1, assessment.blockingChecks.size)
        assertEquals(1, assessment.recoverableChecks.size)
        assertTrue(assessment.blockingChecks.first().contains("Failed to load startup runtime model"))
        assertTrue(assessment.recoverableChecks.first().contains("Optional runtime model unavailable"))
    }

    @Test
    fun `session reset is triggered by repeated failures or fatal signatures`() {
        val policy = SessionRecoveryPolicy(maxConsecutiveRuntimeFailures = 2)

        val repeatedFailure = policy.shouldResetSessionAfterFailure(
            consecutiveFailures = 2,
            errorMessage = "some transient error",
        )
        val fatalFailure = policy.shouldResetSessionAfterFailure(
            consecutiveFailures = 1,
            errorMessage = "Runtime returned no tokens.",
        )
        val recoverable = policy.shouldResetSessionAfterFailure(
            consecutiveFailures = 1,
            errorMessage = "temporary timeout",
        )

        assertTrue(repeatedFailure)
        assertTrue(fatalFailure)
        assertFalse(recoverable)
    }
}
