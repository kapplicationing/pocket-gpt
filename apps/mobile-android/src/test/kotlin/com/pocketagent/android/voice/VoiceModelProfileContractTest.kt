package com.pocketagent.android.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceModelProfileContractTest {
    @Test
    fun `developer provisioning and qualification use the production int8 profile`() {
        val repoRoot = File(checkNotNull(System.getProperty("user.dir"))).resolve("../..").canonicalFile
        val provisioner = File(repoRoot, "scripts/dev/provision-voice-models.sh").readText()
        val qualifier = File(repoRoot, "scripts/dev/voice-device-qualification.sh").readText()
        val deviceTest = File(
            repoRoot,
            "apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/voice/VoiceDeviceQualificationTest.kt",
        ).readText()
        val production = VoiceModelInstallManifest.PRODUCTION

        production.archive.files.forEach { modelFile ->
            assertTrue(provisioner.contains(modelFile.fileName), "Provisioner missing ${modelFile.fileName}")
            assertTrue(provisioner.contains(modelFile.sha256), "Provisioner hash drift for ${modelFile.fileName}")
            assertTrue(qualifier.contains(modelFile.fileName), "Qualifier missing ${modelFile.fileName}")
            assertTrue(qualifier.contains(modelFile.sha256), "Qualifier hash drift for ${modelFile.fileName}")
            assertTrue(deviceTest.contains(modelFile.fileName), "Device test missing ${modelFile.fileName}")
        }
        production.archive.files
            .filter { modelFile -> modelFile.fileName.endsWith(".int8.onnx") }
            .forEach { modelFile ->
                val fullPrecisionName = modelFile.fileName.replace(".int8.onnx", ".onnx")
                assertFalse(provisioner.contains("\"$fullPrecisionName\""))
                assertFalse(qualifier.contains("\"$fullPrecisionName\""))
                assertFalse(deviceTest.contains("\"$fullPrecisionName\""))
            }
        assertTrue(provisioner.contains("profile=${production.profileId}"))
        assertTrue(provisioner.contains("runtime=${VoiceModelInstaller.SHERPA_RUNTIME_VERSION}"))
    }
}
