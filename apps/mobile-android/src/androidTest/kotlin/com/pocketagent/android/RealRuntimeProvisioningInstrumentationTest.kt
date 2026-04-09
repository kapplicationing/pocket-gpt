package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeProvisioningInstrumentationTest {
    @Test
    fun seedModelsAndVerifyStartupChecks() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime provisioning sanity lane. Set stage2_enable_provisioning_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_PROVISIONING_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_1_7B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime provisioning sanity lane. Provide both model paths via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        val (seeded0, seeded2b) = runCatching {
            val seeded0 = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                absolutePath = modelPath0_8b,
            )
            val seeded2b = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                absolutePath = modelPath2b,
            )
            seeded0 to seeded2b
        }.getOrThrow()

        assertTrue(
            "Failed to activate seeded 0.8B version ${seeded0.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = seeded0.version,
            ),
        )
        assertTrue(
            "Failed to activate seeded 1.7B version ${seeded2b.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                version = seeded2b.version,
            ),
        )

        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        val state0 = snapshot.models.firstOrNull { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        val state2 = snapshot.models.firstOrNull { it.modelId == ModelCatalog.QWEN3_1_7B_Q4_K_M }
        assertNotNull("Missing 0.8B model state after provisioning.", state0)
        assertNotNull("Missing 1.7B model state after provisioning.", state2)

        requireNotNull(state0)
        requireNotNull(state2)
        assertEquals(seeded0.version, state0.activeVersion)
        assertEquals(seeded2b.version, state2.activeVersion)
        assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
        assertEquals(normalizePath(modelPath2b), normalizePath(state2.absolutePath.orEmpty()))
        assertEquals(
            "Expected one canonical entry for seeded 0.8B path.",
            1,
            state0.installedVersions.count { normalizePath(it.absolutePath) == normalizePath(modelPath0_8b) },
        )
        assertEquals(
            "Expected one canonical entry for seeded 1.7B path.",
            1,
            state2.installedVersions.count { normalizePath(it.absolutePath) == normalizePath(modelPath2b) },
        )

        AppRuntimeDependencies.installProductionRuntime(appContext)
        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertStartupChecksReadyWithOptionalWarnings(
            startupChecks = startupChecks,
            healthyModelIds = setOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4,
                ModelCatalog.QWEN3_1_7B_Q4_K_M,
            ),
            failurePrefix = "Startup checks failed after provisioning",
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())
    }

    private fun requireFile(value: String): String {
        val resolved = resolveModelPath(value)
        require(resolved != null) { "Model path does not exist: $value" }
        return resolved
    }

    private fun resolveModelPath(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val candidates = listOf(
            normalized,
            normalized.replace("/sdcard/", "/storage/emulated/0/"),
            normalized.replace("/storage/emulated/0/", "/sdcard/"),
        )
        return candidates
            .asSequence()
            .map { candidate -> File(candidate) }
            .firstOrNull(::isReadableRegularFile)
            ?.absolutePath
    }

    private fun isReadableRegularFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }
        return runCatching {
            file.inputStream().use { input -> input.read() }
            true
        }.getOrDefault(false)
    }

    private fun normalizePath(value: String): String {
        val canonical = runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
        return canonical
            .replace("/sdcard/", "/storage/emulated/0/")
            .replace("/storage/self/primary/", "/storage/emulated/0/")
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    companion object {
        private const val ARG_ENABLE_PROVISIONING_TEST = "stage2_enable_provisioning_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_1_7B = "stage2_model_1_7b_path"
    }
}
