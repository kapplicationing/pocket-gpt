package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceBetaContractTest {
    @Test
    fun `microphone permission blocks always on listening`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = false,
            assistantRoleSupported = true,
            assistantRoleHeld = false,
            batteryOptimizationIgnored = false,
            modelsReady = true,
        )

        assertEquals(VoiceBetaBlockingIssue.MICROPHONE_PERMISSION, contract.blockingIssue)
        assertFalse(contract.canEnableAlwaysOnListening)
        assertTrue(contract.needsAssistantRole)
        assertTrue(contract.needsBatteryGuidance)
    }

    @Test
    fun `missing models block always on listening after microphone is granted`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = true,
            assistantRoleSupported = true,
            assistantRoleHeld = false,
            batteryOptimizationIgnored = true,
            modelsReady = false,
        )

        assertEquals(VoiceBetaBlockingIssue.MODELS_MISSING, contract.blockingIssue)
        assertFalse(contract.canEnableAlwaysOnListening)
        assertTrue(contract.needsAssistantRole)
        assertFalse(contract.needsBatteryGuidance)
    }

    @Test
    fun `microphone blocker wins when microphone and models are both missing`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = false,
            assistantRoleSupported = true,
            assistantRoleHeld = false,
            batteryOptimizationIgnored = false,
            modelsReady = false,
        )

        assertEquals(VoiceBetaBlockingIssue.MICROPHONE_PERMISSION, contract.blockingIssue)
        assertFalse(contract.canEnableAlwaysOnListening)
    }

    @Test
    fun `ready beta keeps assistant and battery follow up advisory only`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = true,
            assistantRoleSupported = true,
            assistantRoleHeld = false,
            batteryOptimizationIgnored = false,
            modelsReady = true,
        )

        assertEquals(null, contract.blockingIssue)
        assertTrue(contract.canEnableAlwaysOnListening)
        assertTrue(contract.needsAssistantRole)
        assertTrue(contract.needsBatteryGuidance)
    }

    @Test
    fun `assistant role unsupported does not block ready beta`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = true,
            assistantRoleSupported = false,
            assistantRoleHeld = false,
            batteryOptimizationIgnored = true,
            modelsReady = true,
        )

        assertEquals(null, contract.blockingIssue)
        assertTrue(contract.canEnableAlwaysOnListening)
        assertFalse(contract.needsAssistantRole)
        assertFalse(contract.needsBatteryGuidance)
    }

    @Test
    fun `fully ready beta has no remaining setup requirements`() {
        val contract = evaluateVoiceBetaContract(
            microphonePermissionGranted = true,
            assistantRoleSupported = true,
            assistantRoleHeld = true,
            batteryOptimizationIgnored = true,
            modelsReady = true,
        )

        assertEquals(null, contract.blockingIssue)
        assertTrue(contract.canEnableAlwaysOnListening)
        assertFalse(contract.needsAssistantRole)
        assertFalse(contract.needsBatteryGuidance)
    }
}
